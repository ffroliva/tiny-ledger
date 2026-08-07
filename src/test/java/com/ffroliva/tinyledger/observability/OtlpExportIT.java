package com.ffroliva.tinyledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * §14 step 9's second done-when half: <strong>a Collector container receives real spans and metrics
 * over OTLP.</strong> The nearest a build can get to "the dashboard works" — no build can assert a
 * hosted backend, which is why §14's original wording was withdrawn at v3.40 and replaced by this.
 *
 * <p><strong>{@code standalone}, and ONE container.</strong> Nothing asserted here needs Postgres,
 * Redis, Kafka or Keycloak. This is a separate profile context, in the same category as
 * {@code CucumberSpringConfig} and {@code LedgerEventsListenerTest} — it does not fork the shared
 * {@code full} context, so ADR 0003's forking conditions never arise and CR13 cannot recur. §9.4
 * described this test as forking {@code full} deliberately and as "the only fork in the suite"; both
 * sentences were written before it existed and are corrected at v3.41.
 *
 * <p><strong>Three defaults would each make this test lie, and all three are overridden below.</strong>
 * Boot silences telemetry export in tests, under two separate properties. The Micrometer OTLP metrics
 * registry's step interval defaults to <em>sixty seconds</em>, so a test that does not lower it either
 * waits a minute or fails for a reason unconnected to its subject. And {@code application.properties}
 * pins the management port to 9090, which would collide with any other test holding it.
 */
@SpringBootTest(classes = TinyLedgerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("standalone")
class OtlpExportIT {

    /**
     * Pinned, like every other version here, and <strong>checked to exist</strong> rather than guessed:
     * {@code 0.158.0} is the current release, published 2026-08-04. The plan's draft named a tag from
     * 2024 that also exists — which is exactly why "it resolves" is not the same check as "it is the
     * one you meant".
     */
    static final GenericContainer<?> COLLECTOR = new GenericContainer<>(
                    DockerImageName.parse("otel/opentelemetry-collector-contrib:0.158.0"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("otel-collector-test.yaml"), "/etc/otelcol/config.yaml")
            .withCommand("--config=/etc/otelcol/config.yaml")
            .withExposedPorts(4318)
            // Wait on READINESS, not on a listening port. Wait.forListeningPort() is what let a dead
            // Collector through on CI run 31218191269 — Docker had published the mapping, so the check
            // passed against a process that had already exited, and the test then blamed the
            // application for sixty seconds. This line is why the config keeps log level `info`:
            // `warn` suppresses the message.
            .waitingFor(Wait.forLogMessage(".*Everything is ready.*\\n", 1));

    static {
        COLLECTOR.start();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void pointTheExportersAtTheCollector(DynamicPropertyRegistry registry) {
        String base = "http://" + COLLECTOR.getHost() + ":" + COLLECTOR.getMappedPort(4318);
        // TWO DIFFERENT PROPERTY SHAPES, and this is where an afternoon goes: tracing takes a full
        // per-signal `endpoint`, while the Micrometer metrics registry takes a `url`. Both are the Boot
        // 4.1 spellings, read from each jar's configuration metadata — `management.otlp.tracing.*` is
        // the deprecated alias and setting it here would do nothing visible.
        registry.add("management.tracing.export.otlp.enabled", () -> "true");
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> base + "/v1/traces");
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");
        registry.add("management.otlp.metrics.export.enabled", () -> "true");
        registry.add("management.otlp.metrics.export.url", () -> base + "/v1/metrics");
        // Sixty seconds by default. Without this the metric assertion below is a coin flip against the
        // Awaitility window rather than a test.
        registry.add("management.otlp.metrics.export.step", () -> "1s");
        // Boot silences both in tests unless these are set — TracingContextCustomizerFactory and
        // MetricsContextCustomizerFactory, read from the shipped jars' bytecode.
        registry.add("spring.test.tracing.export", () -> "true");
        registry.add("spring.test.metrics.export", () -> "true");
        // application.properties pins 9090; a random port keeps this off any other test's listener.
        registry.add("management.server.port", () -> "0");
    }

    @Test
    void aRealDepositReachesTheCollectorAsBothASpanAndAMetric() {
        RestClient http = RestClient.create();
        String created = http.post()
                .uri("http://127.0.0.1:" + port + "/api/v1/accounts")
                .header("Content-Type", "application/json")
                .body("{\"name\":\"ACC-OTLP\",\"currency\":\"GBP\"}")
                .retrieve()
                .body(String.class);
        String account = JsonPath.read(created, "$.accountUid");

        http.put()
                .uri("http://127.0.0.1:" + port + "/api/v1/accounts/" + account + "/deposits/" + UUID.randomUUID())
                .header("Content-Type", "application/json")
                .body("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":2500}}")
                .retrieve()
                .toBodilessEntity();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            String received = COLLECTOR.getLogs();
            assertThat(received)
                    .as("the use-case span must reach the Collector over OTLP")
                    .contains("ledger.record-movement");
            assertThat(received)
                    .as("...carrying the domain attribute §6.6 mandates on every span")
                    .contains("ledger.account_id");
            assertThat(received)
                    .as("...the meter must arrive on the same pipeline — a trace-only export would pass"
                            + " a weaker assertion while every dashboard stayed empty")
                    .contains("ledger.movements");
            assertThat(received)
                    .as("...and it must carry the resource identity part 1 declared (§6.6, ADR 0005):"
                            + " without it twenty replicas emit one indistinguishable stream")
                    .contains("service.namespace");
        });
    }
}
