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

    /**
     * A syntactically valid account UUID that is never opened, for the tests whose refusal happens at the
     * filter chain and therefore never dereferences it. Using this instead of a real account keeps those
     * tests from spending a charged write on a fixture they do not use — see {@link #LOWERED_WRITE_LIMIT}.
     * Lives here rather than in one IT class because two of them need the same literal.
     */
    public static final String ANY_UID = "11111111-1111-4111-8111-111111111111";

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
     * {@code post(}/{@code put(} call site under {@code src/test} before picking this number, and
     * recounted after every change to these classes: the heaviest existing writer is {@code alice} at
     * 8 charged calls: seven {@code openAnAccountAs("alice")} paths in {@code SecurityConfigIT}, plus
     * the account opened for N16 in {@code RoleAuthorizationIT}. {@code carol} is next at 3 charged
     * paths in {@code RoleAuthorizationIT}, then {@code mallory} at 3 and {@code trent} at 2 in
     * {@code SecurityConfigIT}. {@code LOWERED_WRITE_LIMIT} stays comfortably above all of them so
     * {@code RateLimitIT} is the only test that ever reaches it — {@code bob} is simply the one
     * fixture user no other test's write call touches at all.
     */
    public static final int LOWERED_WRITE_LIMIT = 20;

    /**
     * Review finding I5: every request any {@code *IT} test makes — not just writes — charges the
     * same {@code ip-backstop:127.0.0.1} bucket for the life of this shared context (MockMvc's
     * default remote address, uniform across every test class). Nine IT classes' fixed call paths
     * contribute <strong>67</strong> requests — counted from every {@code MockMvc.perform} path,
     * expanding {@code SecurityConfigIT}'s {@code openAnAccountAs} helper against its call sites (30),
     * plus {@code RoleAuthorizationIT} (14), {@code AudienceValidationIT} (2) and
     * {@code RateLimitIT}'s 21-request write-limit proof; {@code RateLimitIT}'s flood test is excluded
     * because it sets {@code 203.0.113.222} and charges a different bucket, and the other five IT
     * classes make no HTTP request at all — plus bounded retries from P9's audit Awaitility poll.
     *
     * <p><strong>Two ceilings, and the 67 is quoted against both — the comparison below is a
     * counterfactual, not a description of what runs.</strong> §6.1's production value is 300/minute,
     * and 67 would fit under it with room to spare; that is the reassurance a future editor wants
     * before adding a request. But this bucket is deliberately <em>not</em> left at 300, because
     * {@code RateLimitIT}'s flood test (C1) must exhaust whatever this constant is — so it is raised
     * to the value below, far past anything ambient traffic could reach. Nothing in this suite ever
     * runs against 300: the flood test exercises the backstop's behaviour now, and the production
     * number is unexercised here as a result (recorded in the task report, not hidden).
     * {@code CucumberSpringConfig} needs no equivalent override: it boots
     * {@code standalone} (no {@code @ActiveProfiles}, so {@code spring.profiles.default=standalone}
     * applies), and {@code application-standalone.properties} exempts {@code 127.0.0.1} from every
     * bucket outright — an override there would raise a ceiling nothing can ever be charged against.
     * An earlier version of this class carried exactly that dead override; removed on review.
     *
     * <p>Measured on CI, not assumed: the first version of this override used the production period
     * (60s), and {@code RateLimitIT}'s flood test failed — {@code refillGreedy} drips tokens back in
     * continuously while the test is running, and at 1000 tokens/60s (~16.7/s) a ~1.8s flood of 1001
     * requests refills ~30 of them mid-flight, so {@code capacity + 1} requests landed just short of
     * exhausting the bucket. The period below is minutes, not seconds, purely to make that refill
     * negligible over a flood test's few seconds of wall-clock time — it does not change the capacity
     * ceiling ambient traffic is measured against, so the I5 safety margin above is unaffected.
     */
    public static final int RAISED_IP_BACKSTOP_LIMIT = 1000;

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
        // Derived, not chosen: period / capacity = 600s / LOWERED_WRITE_LIMIT (20) = 30 seconds per
        // greedily-refilled token — far longer than the 21-request proof itself takes, so its
        // configured-capacity assertion cannot be erased by a token arriving mid-loop. Raise
        // LOWERED_WRITE_LIMIT and this shrinks proportionally (600s / 40 = 15s), so re-derive the
        // margin here rather than assuming "30 seconds" still holds.
        registry.add("ledger.rate-limit.write-per-principal.period", () -> "10m");

        registry.add("ledger.rate-limit.ip-backstop.capacity", () -> String.valueOf(RAISED_IP_BACKSTOP_LIMIT));
        registry.add("ledger.rate-limit.ip-backstop.burst", () -> "0");
        registry.add("ledger.rate-limit.ip-backstop.period", () -> "10m");
    }
}
