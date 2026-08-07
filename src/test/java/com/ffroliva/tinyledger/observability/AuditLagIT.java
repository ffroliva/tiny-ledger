package com.ffroliva.tinyledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.platform.AuditLagGauge;
import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

/**
 * Spec §9.3 case <strong>E9</strong> — "Lag is visible and does not gate."
 *
 * <p>Rewritten at spec v3.32 and corrected again at v3.37. The original case asked the readiness probe
 * to shed traffic on projection lag; that cannot happen here, because the balance projection is a
 * synchronous {@code @EventListener} on the publishing thread inside the write transaction (§4.3) and
 * its lag is structurally zero. This asserts the behaviour the system actually has, and that it is the
 * behaviour we want: the outbox falls behind, and the ledger keeps serving. ADR 0004.
 *
 * <p><strong>The broker is paused, not the audit consumer, and that is the whole design of this
 * test.</strong> {@code spring.modulith.events.completion-mode=DELETE} removes the publication row the
 * moment the Kafka <em>producer</em> is acknowledged, and {@code AuditKafkaListener} sits downstream of
 * that ack on its own consumer group — so pausing the consumer would leave
 * {@code ledger.outbox.pending.age.seconds} reading {@code 0.0} and this test would assert nothing.
 * v3.37 records that the gauge carried a name for a quantity it could not observe until this was found.
 *
 * <p>The pause mechanism is {@code KafkaOutageIT}'s, deliberately reused rather than reinvented (§9.2b):
 * pause rather than stop so the port mapping survives, and unpause in a {@code finally} so a failure
 * here cannot freeze Kafka for the classes that run afterwards inside the shared context (ADR 0003).
 *
 * <p><strong>Readiness is read through {@link HealthEndpoint}, not over HTTP.</strong> The probes bind
 * to {@code management.server.port} (ADR 0005) and this is a {@code MockMvc} context with no port, so
 * an HTTP assertion here could not reach the management listener at all. The endpoint is the same
 * object the probe renders, one layer below a transport this test does not need — and reading it
 * directly also keeps these assertions off {@code AbstractIntegrationTest}'s hand-counted IP budget.
 */
class AuditLagIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuditLagIT.class);

    /** §6.6's alerting threshold. Nothing gates on it — that is the point of the case. */
    private static final double ALERT_THRESHOLD_SECONDS = 5.0;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HealthEndpoint health;

    @Autowired
    private AuditLagGauge auditLagGauge;

    @Test
    void lagIsVisibleAndReadinessStaysUp() throws Exception {
        String token = bearer("alice");
        String accountUid = openAccount(token);

        // given: a drained outbox and a healthy baseline.
        //
        // The drain is not tidiness, it is what stops this test asserting nothing. The gauge reads
        // MIN(publication_date) across EVERY incomplete row, so one left behind by an earlier class in
        // this shared context would already exceed the threshold and the assertion below would pass
        // without the pause having done anything. Waiting for zero first means the reading that
        // crosses the threshold can only be the write this test makes.
        await("the outbox drains before the outage, so the rise below is attributable to this test")
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(auditLagGauge.lagSeconds()).isZero());
        assertThat(readiness()).isEqualTo(Status.UP);

        String containerId = KAFKA.getContainerId();
        try {
            DockerClientFactory.instance()
                    .client()
                    .pauseContainerCmd(containerId)
                    .exec();

            // when: writes continue while the outbox cannot drain
            mockMvc.perform(put("/api/v1/accounts/" + accountUid + "/deposits/" + UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(deposit(5_000)))
                    .andExpect(status().isCreated());

            // then: the gauge rises past §6.6's 5 s threshold. Awaitility, never Thread.sleep (§9.3's
            // method rule) — and the wait is genuine here rather than a poll for an event that has
            // already happened: the quantity under test is an age, so it takes five seconds of
            // wall-clock to become true.
            await("the outbox age becomes visible")
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(auditLagGauge.lagSeconds()).isGreaterThan(ALERT_THRESHOLD_SECONDS));

            // and: balances are exact, because the projection never depended on Kafka.
            //
            // consistency=strong is deliberate and must NOT be dropped to "simplify" this test. A plain
            // cached read is not guaranteed exact here: BalanceProjector:20-32 evicts inside the open
            // append transaction, so a read racing the commit can repopulate the cache with the
            // pre-write balance for up to the 60s TTL (§6.2, ADR 0004's correction section). That
            // window is real, bounded and unrelated to Kafka — asserting a cached read would make this
            // test flaky for a reason that has nothing to do with E9.
            assertThat(strongBalance(token, accountUid)).isEqualTo(5_000L);

            // and: readiness stays UP — the whole point of E9's rewrite. An instance that removed
            // itself here would fail E11, which requires the ledger to survive exactly this outage.
            assertThat(readiness())
                    .as("readiness must not gate on outbox lag — ADR 0004, and E11 depends on it")
                    .isEqualTo(Status.UP);

            log.info(
                    "E9: outbox pending age reached {} s with the broker paused; readiness {}",
                    auditLagGauge.lagSeconds(),
                    readiness());
        } finally {
            DockerClientFactory.instance()
                    .client()
                    .unpauseContainerCmd(containerId)
                    .exec();
        }

        // and finally: it drains again. Without this the test would be satisfied by an application that
        // had stopped publishing altogether — the same shape of gap the positive twins elsewhere in
        // this suite exist to close, and the reason KafkaOutageIT carries a recovery write of its own.
        await("the outbox drains once the broker returns")
                .atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(auditLagGauge.lagSeconds()).isZero());
    }

    private Status readiness() {
        return health.healthForPath("readiness").getStatus();
    }

    private String openAccount(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-E9\",\"currency\":\"GBP\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.accountUid");
    }

    private long strongBalance(String token, String accountUid) throws Exception {
        String body = mockMvc.perform(get("/api/v1/accounts/" + accountUid + "/balance")
                        .param("consistency", "strong")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.amount.minorUnits")).longValue();
    }

    private static String deposit(long minorUnits) {
        return "{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":" + minorUnits + "}}";
    }
}
