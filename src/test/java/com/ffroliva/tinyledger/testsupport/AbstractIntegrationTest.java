package com.ffroliva.tinyledger.testsupport;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import com.redis.testcontainers.RedisContainer;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * {@code @AutoConfigureMockMvc} is here, on the shared base, for the same reason the Keycloak container is: a per-class
 * declaration would change the context cache key and fork the {@code full} context (ADR 0003). It is what makes
 * an autowired {@code MockMvc} assemble its filter chain from the application's filter <em>registrations</em> —
 * {@code SpringBootMockMvcBuilderCustomizer$FilterRegistrationBeans extends ServletContextInitializerBeans},
 * so plain {@code Filter} beans are adapted and sorted by {@code @Order} exactly as a servlet container sorts
 * them, and {@code securityFilterChainRegistration} keeps its {@code DEFAULT_FILTER_ORDER = -100}. Measured:
 * a hand-built {@code MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())} registers
 * <em>only</em> the security filter, so {@code FapiInteractionIdFilter} never ran and its header was null on
 * every response — filter ordering is unobservable there, because there is nothing to order.
 */
@SpringBootTest(classes = TinyLedgerApplication.class)
@ActiveProfiles("full")
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("tiny_ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    public static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    public static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    public static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                    DockerImageName.parse("quay.io/keycloak/keycloak:26.4"))
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/keycloak/realm-tiny-ledger.json"),
                    "/opt/keycloak/data/import/realm-tiny-ledger.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/tiny-ledger/.well-known/openid-configuration")
                    .forPort(8080)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        KEYCLOAK.start();
    }

    protected static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/tiny-ledger";
    }

    /** A real `Authorization` header value for one of the realm's fixture users. */
    protected static String bearer(String username) {
        return "Bearer " + KeycloakTokens.accessToken(issuerUri(), username);
    }

    /**
     * Task 4 ({@code RateLimitIT}): "limits are configuration" (§6.1), demonstrated by lowering one
     * here rather than issuing 100+ real requests. Declared on the shared base — not a per-class
     * {@code @TestPropertySource} — because either fork the one Spring context {@code missCount = 1}
     * relies on (ADR 0003); this is the same mechanism the Testcontainers properties below already
     * use, so nothing new is added to the risk this class carries.
     *
     * <p>Safe only because of the margin, not because the key is per-principal: the write-per-principal
     * *limit value* is one number shared by every principal's bucket, so lowering it affects
     * {@code alice} and {@code carol} too, not just {@code bob}. Grepped against every
     * {@code post(}/{@code put(} call site under {@code src/test} before picking this number: the
     * heaviest existing writer is {@code alice} at 4 calls across {@code SecurityConfigIT}
     * ({@code openAnAccountAs}, once per test method, accumulating in one shared bucket for her
     * subject), then {@code carol} at 3 in {@code RoleAuthorizationIT}. {@code LOWERED_WRITE_LIMIT}
     * stays comfortably above both so {@code RateLimitIT} is the only test that ever reaches it —
     * {@code bob} is simply the one fixture user no other test's write call touches at all.
     */
    public static final int LOWERED_WRITE_LIMIT = 10;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/tiny-ledger");

        registry.add("ledger.rate-limit.write-per-principal.capacity", () -> String.valueOf(LOWERED_WRITE_LIMIT));
        registry.add("ledger.rate-limit.write-per-principal.burst", () -> "0");
        registry.add("ledger.rate-limit.write-per-principal.period", () -> "60s");
    }
}
