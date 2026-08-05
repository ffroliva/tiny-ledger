package com.ffroliva.tinyledger.testsupport;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code @AutoConfigureMockMvc} is here, on the shared base, for the same reason the JWT key is: a per-class
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

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // The resource server trusts the committed test key instead of a Keycloak the suite does not run.
        // It lives here, on the shared base, rather than on SecurityConfigIT: a per-class @Import or
        // @TestConfiguration supplying a JwtDecoder bean would change the context cache key and fork the
        // `full` context (ADR 0003).
        //
        // The issuer must be blanked, and that is measured rather than assumed. Boot's KeyValueCondition
        // matches only when public-key-location has text AND neither jwk-set-uri nor issuer-uri does —
        // so with application-full.properties' issuer-uri still in play, IssuerUriCondition won and the
        // context got a SupplierJwtDecoder pointed at a Keycloak that is not running. It failed lazily, on
        // first decode, which means only aValidTokenIsAccepted caught it: both 401 assertions passed
        // because an absent token never reaches the decoder. Blanking it is necessary but not sufficient —
        // JwtDecoderConfiguration#getValidator adds a JwtIssuerValidator on `getIssuerUri() != null`, not on
        // hasText, so the minted token claims the same blank issuer. TestJwt.ISSUER is the single value.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwt.ISSUER);
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.public-key-location", () -> "classpath:test-jwt-public.pem");
    }
}
