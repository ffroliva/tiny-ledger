package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Spec §6.6 / ADR 0004: the probes must answer without a credential, and nothing else under
 * {@code /actuator} may be reachable at all. A probe that needs a bearer token fails during exactly
 * the outage it exists to report.
 *
 * <p><strong>This class forks the Spring context, deliberately and once.</strong> {@code AGENTS.md}
 * trap 5 requires a written reason and ADR 0005 is it: the probes bind to
 * {@code management.server.port}, and {@code MockMvc} has no port, so this is the one test in the
 * repository that needs a real server. Do not "fix" it by folding it back into
 * {@code AbstractIntegrationTest}, and do not add a second fork by reaching for
 * {@code @TestPropertySource}.
 *
 * <p>Runs under {@code standalone}, so it starts no containers and stays on the fast {@code verify}
 * path (ADR 0003). The management chain is profile-independent, so {@code full} would prove nothing
 * extra here — and {@code standalone} proves one thing {@code full} could not: see
 * {@link #redisBeingDownDoesNotMakeTheInstanceUnready()}.
 *
 * <p>{@code management.server.port=0} takes a random free port so parallel runs cannot collide;
 * {@code @LocalManagementPort} reads back which one. {@code management.server.address} is inherited
 * from {@code application-standalone.properties}, which is the point of that line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("standalone")
class ActuatorProbeTest {

    @LocalManagementPort
    int managementPort;

    @LocalServerPort
    int apiPort;

    /**
     * {@code RestClient.create()} rather than an autowired {@code RestClient.Builder}: this
     * application declares no builder bean and Boot 4.1 auto-configures none on this classpath
     * (measured — the field failed to autowire), and a test that only needs a status code has no use
     * for the converters and interceptors a builder would carry anyway.
     */
    private final RestClient http = RestClient.create();

    /** Status only, never thrown: a 4xx is the answer under test, not an error. */
    private int statusOf(int port, String path) {
        return http.get()
                .uri("http://127.0.0.1:" + port + "/actuator/" + path)
                .exchange((request, response) -> response.getStatusCode().value(), false);
    }

    private int statusOfManagement(String path) {
        return statusOf(managementPort, path);
    }

    @Test
    void theLivenessProbeAnswersWithoutACredential() {
        assertThat(statusOfManagement("health/liveness")).isEqualTo(200);
    }

    @Test
    void theReadinessProbeAnswersWithoutACredential() {
        assertThat(statusOfManagement("health/readiness")).isEqualTo(200);
    }

    /**
     * ADR 0004 / E10: the ledger must keep serving while Redis is down, so {@code redis} is
     * deliberately absent from the readiness group. Measured 2026-08-07 and this is why the test can
     * live on the fast path: {@code spring-boot-starter-data-redis} is an unconditional dependency, so
     * Boot auto-configures {@code RedisHealthIndicator} under {@code standalone} too — where no Redis
     * exists and none is wanted, {@code RateLimitConfig} using Caffeine there. Its contributor
     * therefore reads {@code DOWN} with no container running at all, which makes this the assertion
     * that fails the moment someone "completes" the readiness group. E10's own coverage needs a real
     * outage under {@code -Pit}; this needs nothing.
     */
    @Test
    void redisBeingDownDoesNotMakeTheInstanceUnready() {
        assertThat(statusOfManagement("health/readiness")).isEqualTo(200);
    }

    /**
     * The health root, asserted as <strong>403 exactly</strong> — and the exactness is the whole test.
     *
     * <p>It sits apart from {@link #noOtherActuatorEndpointIsReachable(String)} because the not-200
     * assertion that is right for the other nineteen is <em>vacuous</em> here, and this was found by
     * running the red proof rather than by reading. Exposing {@code health} maps the root, so unlike
     * the others it is a live endpoint; under {@code standalone} it aggregates a {@code redis}
     * contributor that can never be UP, so it answers {@code 503}. Replacing the two literal permits
     * with {@code EndpointRequest.to(HealthEndpoint.class)} therefore grants the root to an
     * unauthenticated caller and the suite <strong>stays green</strong> — measured, 28/28 — because
     * {@code 503 != 200} either way. The §6.6 grant this repository spent a council round identifying
     * would have reopened silently.
     *
     * <p>{@code 403} is the answer only {@code denyAll} produces: {@code 404} would mean the root was
     * never mapped, {@code 503} that it was mapped, reached and rendered. Pinning it is what makes the
     * refactor visible.
     */
    @Test
    void theHealthRootIsDeniedRatherThanRendered() {
        assertThat(statusOfManagement("health")).isEqualTo(403);
    }

    /**
     * Layer 2 of the exposure decision. Most of these are unreachable because
     * {@code exposure.include=health} never web-maps them — but that is one properties line, and this
     * test is what makes widening it a visible, deliberate act rather than a silent one. Each name is
     * an endpoint the §6.6 assessment rejected for a stated reason: {@code heapdump} renders balances
     * and bearer tokens, {@code env} and {@code configprops} the issuer-uri and datasource URL,
     * {@code loggers} is a runtime write, {@code httpexchanges} is PII, {@code threaddump} is the stack
     * traces §6.5 forbids leaking from {@code /error}.
     *
     * <p>{@code health} is deliberately <strong>not</strong> in this list.
     * {@link #theHealthRootIsDeniedRatherThanRendered()} owns it, because the not-200 assertion below
     * cannot fail for the root — see that method for the measurement.
     *
     * <p>Asserts <strong>not-200</strong> rather than a specific code: layer 1 answers {@code 404}
     * (never mapped) and layer 2 answers {@code 403} (mapped but denied), and which one replies is the
     * implementation detail this test should not pin. Pinning {@code 404} would turn the security rule
     * into a test proving only that the endpoint was never enabled.
     *
     * <p>If you are here because this failed after you exposed an endpoint: that is the test working.
     * Add the endpoint to §6.6's assessment with its reasoning, or do not expose it.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "info",
                "metrics",
                "prometheus",
                "env",
                "configprops",
                "beans",
                "mappings",
                "heapdump",
                "threaddump",
                "loggers",
                "httpexchanges",
                "auditevents",
                "caches",
                "conditions",
                "shutdown",
                "liquibase",
                "sbom",
                "startup",
                "scheduledtasks"
            })
    void noOtherActuatorEndpointIsReachable(String endpoint) {
        assertThat(statusOfManagement(endpoint)).isNotEqualTo(200);
    }

    /**
     * ADR 0005: Actuator is served on the management port and <em>not also</em> on the API port. That
     * is not a tautology — Boot maps the endpoints into whichever context owns them, and a
     * configuration that put the management context back on the main port, or an additional-path
     * registration, would serve them from 8080 where §6.1's IP backstop and the {@code full} chain's
     * {@code anyRequest().authenticated()} apply instead of this chain. It is also why no
     * {@code /actuator/} exemption is owed in either rate-limit filter: there is nothing under that
     * path on the API port to exempt.
     *
     * <p><strong>What this does NOT prove, stated because the red proof measured it:</strong> that
     * {@code application.properties} declares {@code management.server.port} at all. Deleting that line
     * leaves this class green — {@code @SpringBootTest(properties = "management.server.port=0")} above
     * supplies the split itself, and it has to, or parallel runs would contend for 9090. <strong>No
     * gate enforces the base property.</strong> What would catch its loss is the {@code full} chain
     * refusing an unauthenticated {@code /actuator/health/liveness} on 8080 — an integration concern,
     * not covered here, and named rather than implied.
     */
    @ParameterizedTest
    @ValueSource(strings = {"health", "health/liveness", "health/readiness", "env", "heapdump"})
    void actuatorIsNotServedOnTheApiPort(String endpoint) {
        assertThat(statusOf(apiPort, endpoint)).isNotEqualTo(200);
    }
}
