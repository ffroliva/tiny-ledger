package com.ffroliva.tinyledger.testsupport;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import com.redis.testcontainers.RedisContainer;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
@Import(ObservabilityTestConfig.class)
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
     * <b>11</b> charged calls: eight {@code openAnAccountAs("alice")} paths in {@code SecurityConfigIT},
     * the account opened for N16 in {@code RoleAuthorizationIT}, and <b>2</b> added by
     * {@code AuditLagIT} (E9's account and its one deposit). {@code mallory} is next at 4 in
     * {@code SecurityConfigIT} (her own account, the deposit into it, and the cross-account deposit and
     * withdrawal she is refused — a refused write still charges the bucket, the limiter runs ahead of
     * authorisation), then {@code carol} at 3 in {@code RoleAuthorizationIT} and {@code trent} at 2.
     * {@code LOWERED_WRITE_LIMIT} stays comfortably above all of them so
     * {@code RateLimitIT} is the only test that ever reaches it — {@code bob} is simply the one
     * fixture user no other test's write call touches at all.
     *
     * <p><strong>Raised from 20 to 150 for {@code ConcurrentWithdrawalIT}</strong>, whose ten racing
     * withdrawals retry their 409s and so cannot be counted in advance the way every other IT's writes can.
     * They are <em>bounded</em> instead: that test caps each branch at {@code MAX_ATTEMPTS = 12}, so its
     * ceiling is {@code 10 * 12 + 2} = <b>122</b> charged writes, never more, all as {@code alice}. Against
     * alice's existing 9 that is <b>131</b> — the margin here is 19, and it is a proof rather than an
     * estimate because the retry loop is capped. Lower {@code MAX_ATTEMPTS} or this number and re-check that
     * arithmetic; the test itself fails loudly on a 429 (it asserts {@code containsOnly(201, 422)}) rather
     * than reporting a wrong settled/refused split, so a budget mistake cannot read as a ledger defect.
     *
     * <p><strong>Recounted for {@code ObservabilityIT} (§14 step 9 part 2): alice moves 11 -> 18.</strong>
     * That class issues <b>7</b> requests, all as {@code alice} and all charged writes — three
     * {@code openAnAccountAs}, two deposits, one withdrawal and one <em>refused</em> withdrawal, since a
     * refused write still charges the bucket (the limiter runs ahead of authorisation). Against the
     * limit of 150 that leaves the {@code ConcurrentWithdrawalIT} arithmetic below unchanged in shape:
     * its ceiling of 122 plus alice's other 18 is <b>140</b>, so the margin narrows from 19 to <b>10</b>.
     * Still a proof rather than an estimate, because the retry loop is capped — but it is the number to
     * re-derive before adding another alice write, and the one to raise this constant for.
     *
     * <p><strong>Measured, not just bounded:</strong> the first full {@code -Pit} run spent <b>44</b>
     * withdrawal calls on its ten outcomes (34 conflicts), so the real figure sits at about a third of the
     * 122 ceiling. The ceiling is what this constant is sized against anyway — a run under heavier
     * contention is allowed to cost more without turning into a rate-limit failure.
     */
    public static final int LOWERED_WRITE_LIMIT = 150;

    /**
     * Review finding I5: every request any {@code *IT} test makes — not just writes — charges the
     * same {@code ip-backstop:127.0.0.1} bucket for the life of this shared context (MockMvc's
     * default remote address, uniform across every test class). Nine IT classes' fixed call paths
     * contribute <strong>69</strong> requests — counted from every {@code MockMvc.perform} path,
     * expanding {@code SecurityConfigIT}'s {@code openAnAccountAs} helper against its call sites
     * (23 direct + 9 helper = 32), plus {@code RoleAuthorizationIT} (14), {@code AudienceValidationIT} (2) and
     * {@code RateLimitIT}'s 21-request write-limit proof; {@code RateLimitIT}'s flood test is excluded
     * because it sets {@code 203.0.113.222} and charges a different bucket, and the other five IT
     * classes make no HTTP request at all.
     *
     * <p><strong>Recounted after {@code ConcurrentWithdrawalIT}, and the enumerated figure is now ~322.</strong>
     * Two things moved together. {@code RateLimitIT}'s write-limit proof derives its loop from
     * {@link #LOWERED_WRITE_LIMIT}, so raising that 20 -> 150 turned its 21 requests into <b>151</b> (+130).
     * {@code ConcurrentWithdrawalIT} itself adds a bounded <b>123</b> — 2 setup writes, at most
     * {@code 10 * 12} = 120 withdrawal attempts, and one strong read. {@code AuditLagIT} adds a fixed
     * <b>3</b> (E9's account, its deposit and one strong read; its readiness assertions go through
     * {@code HealthEndpoint} rather than HTTP and are charged nothing, which is one reason they do).
     * <b>{@code ObservabilityIT} adds a fixed 7</b> (three accounts, two deposits, two withdrawals of
     * which one is refused; its span assertions read the exporter directly and cost nothing).
     * Enumerated total: 69 - 21 + 151 + 123 + 3 + 7 =
     * <b>332</b>, against the ~300 Awaitility ceiling below for a worst case near <b>632</b> — still
     * comfortably inside the 1000 configured here, so this constant is deliberately <em>not</em> raised. The
     * 69/68 accounting below is kept intact because it is the audit trail the recount was done against.
     *
     * <p><strong>69 requests, 68 of them charged.</strong>
     * {@code SecurityConfigIT#anErrorDispatchDoesNotEchoTheRequestPath} sets
     * {@code ERROR_REQUEST_URI}, and both limiters ({@code RateLimitFilter},
     * {@code IpBackstopFilter}) extend {@code OncePerRequestFilter}, whose {@code skipDispatch} treats
     * the presence of that attribute as an error dispatch and skips the filter — the same mechanism
     * that test's own javadoc documents as measured for {@code BearerTokenAuthenticationFilter}. So it
     * issues a request and is charged nothing. Both numbers are given because a future recount will
     * land on 69 and should not think it has found a discrepancy.
     *
     * <p><strong>Plus P9's audit Awaitility poll, which dominates the fixed count.</strong> Read out of
     * the pinned {@code awaitility-4.3.0} jar with {@code javap}, not from the documentation:
     * {@code Awaitility.<clinit>} sets {@code DEFAULT_POLL_DELAY = null} (meaning "use the poll
     * interval") and {@code DEFAULT_POLL_INTERVAL = new FixedPollInterval(ONE_HUNDRED_MILLISECONDS)},
     * and {@code Durations.ONE_HUNDRED_MILLISECONDS} is {@code Duration.ofMillis(100)}. Over this
     * suite's {@code atMost(30s)} that is a ceiling of ~300 polled requests, one per attempt —
     * <em>more than four times the enumerated 68</em>, for a worst case near <strong>368</strong>. Not
     * a flake risk: a run that actually polls 300 times has already failed on the 30s timeout, and the
     * trail normally arrives in about a second. But "69 against 1000" would read as 931 of headroom
     * when the true figure is nearer 630, so the bound is stated rather than left as "bounded retries".
     *
     * <p><strong>Two ceilings, and the count is quoted against both — the comparison below is a
     * counterfactual, not a description of what runs.</strong> §6.1's production value is 300/minute,
     * and 68 charged would fit under it with room to spare; that is the reassurance a future editor
     * wants before adding a request. But this bucket is deliberately <em>not</em> left at 300, because
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
        // Derived, not chosen: period / capacity = 5400s / LOWERED_WRITE_LIMIT (150) = 36 seconds per
        // greedily-refilled token — far longer than the 151-request proof itself takes, so its
        // configured-capacity assertion cannot be erased by a token arriving mid-loop.
        //
        // Re-derived when the capacity went 20 -> 150 for ConcurrentWithdrawalIT, per the standing
        // instruction this comment has always carried. The period moved 10m -> 90m in the *same* edit
        // and for exactly that reason: at the old 600s the margin would have collapsed to 4 seconds,
        // and RateLimitIT's loop is now 151 real Postgres+Kafka writes, which does not reliably finish
        // inside 4 seconds on a loaded runner. Raising the period alongside the capacity keeps the
        // derived margin at 36s — *better* than the 30s it replaced — at no cost but a larger
        // Retry-After, which RateLimitIT only asserts is non-null. Change either number and redo this
        // division; a thinned margin here shows up as an intermittent RateLimitIT failure, not as a
        // rate-limiting bug.
        registry.add("ledger.rate-limit.write-per-principal.period", () -> "90m");

        // §14 step 9 part 2. Boot SILENCES telemetry export in tests:
        // TracingContextCustomizerFactory injects `management.tracing.export.enabled=false` unless
        // `spring.test.tracing.export` is set — read out of the bytecode of
        // spring-boot-micrometer-tracing-test-4.1.0, not from documentation. Without it the
        // InMemorySpanExporter bean is never wired to a processor and §9.4's assertions read an empty
        // list, which looks exactly like "no spans are produced".
        //
        // Supplied as a PROPERTY through this existing source rather than with @AutoConfigureTracing:
        // the annotation is a per-class declaration and would fork the context, which is precisely what
        // ADR 0003 §1 forbids and what trap 5 warns costs a whole new set of Kafka consumers.
        registry.add("spring.test.tracing.export", () -> "true");
        // The batch span processor's default delay is 5s. Lowered so ObservabilityIT's Awaitility window
        // is spent waiting for the Kafka hop rather than for a scheduler.
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");

        registry.add("ledger.rate-limit.ip-backstop.capacity", () -> String.valueOf(RAISED_IP_BACKSTOP_LIMIT));
        registry.add("ledger.rate-limit.ip-backstop.burst", () -> "0");
        registry.add("ledger.rate-limit.ip-backstop.period", () -> "10m");
    }
}
