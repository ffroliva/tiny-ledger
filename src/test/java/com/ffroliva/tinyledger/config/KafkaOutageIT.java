package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

/**
 * E11 — Kafka unavailable, and the write side keeps its promises.
 *
 * <p>ADR 0002 is the claim under test: <strong>Postgres is the system of record and Kafka is a delivery
 * path</strong>, not a participant in the write. If that separation is real then a Kafka outage costs the
 * projection its freshness and costs the write nothing — the append commits, the movement is durable, and
 * {@code ?consistency=strong}, which folds the event stream rather than reading the projection, is still
 * exactly right. If the separation were not real, a broker outage would surface as failed or hung writes,
 * and the ledger would be unavailable for a reason ADR 0002 says it should not be.
 *
 * <p><strong>This is the sibling of {@link RedisOutageIT} and it exists for the same reason.</strong> That
 * one found a 64-second write, because a client on the request path had no timeout while its neighbour did.
 * The bounded-stall assertion here is not decoration: it is the same question asked of the other piece of
 * infrastructure on the write path, and asking it is the only way that class of defect gets found.
 *
 * <p>Pause rather than stop, and unpause in a {@code finally}, for the reasons {@link RedisOutageIT}
 * documents: the container keeps its port mapping, the outage is reversible inside the shared context
 * (ADR 0003), and a failure here must not leave Kafka frozen for the classes that run afterwards.
 */
class KafkaOutageIT extends AbstractIntegrationTest {

    /**
     * <strong>Derived from a measurement, not guessed.</strong> With the broker paused the write took
     * <b>164 ms</b> running this class alone and <b>48 ms</b> within the full {@code -Pit} suite — the
     * difference is a cold context, not the outage. Either way it is indistinguishable from a healthy write,
     * because Spring Modulith writes the publication row to Postgres inside the append transaction and
     * attempts delivery afterwards. ADR 0002's separation is real, and the request never touches the broker.
     *
     * <p>Two seconds is therefore a real guard rather than a nominal one, at roughly 12x the measured cost.
     * The first version of this constant was 15 s, which would have passed just as happily if delivery had
     * become synchronous and the write had blocked on the producer — and "it passed" would have spanned two
     * completely different findings. Kafka's own defaults are far larger than this bound
     * ({@code max.block.ms} is 60 s), so a regression that put the broker on the request path fails here
     * loudly instead of hiding under a generous ceiling.
     */
    private static final Duration BOUNDED = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(KafkaOutageIT.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aWriteStillCommitsAndReadsBackStronglyWhileKafkaIsDown() throws Exception {
        String token = bearer("alice");
        String accountUid = openAccount(token);

        String containerId = KAFKA.getContainerId();
        long elapsedMillis;
        try {
            DockerClientFactory.instance()
                    .client()
                    .pauseContainerCmd(containerId)
                    .exec();

            long startedAt = System.nanoTime();
            mockMvc.perform(put("/api/v1/accounts/" + accountUid + "/deposits/" + UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(deposit(7_500)))
                    .andExpect(status().isCreated());
            elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            // The assertion that matters: the record is Postgres, and the strong read folds the stream
            // rather than consulting the projection Kafka feeds. A broker outage must not be able to make
            // the ledger lie about a balance it has already committed.
            assertThat(strongBalance(token, accountUid)).isEqualTo(7_500L);
        } finally {
            DockerClientFactory.instance()
                    .client()
                    .unpauseContainerCmd(containerId)
                    .exec();
        }

        // Logged, not just asserted. The bound is loose on purpose, so "it passed" spans everything from
        // "delivery is genuinely off the request path" to "it blocked for 14 seconds and squeaked under" —
        // and those are different findings. E10's entire result was a number, not a boolean.
        log.info("E11: a write with Kafka paused took {} ms (bound {} ms)", elapsedMillis, BOUNDED.toMillis());

        assertThat(Duration.ofMillis(elapsedMillis))
                .as("a write must not block on the delivery path — ADR 0002 makes Kafka the courier, not the record")
                .isLessThan(BOUNDED);

        // Recovery. Without this the test would be satisfied by an application that had simply stopped
        // publishing altogether, which is the same shape of gap the positive twins elsewhere in this suite
        // exist to close.
        mockMvc.perform(put("/api/v1/accounts/" + accountUid + "/deposits/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deposit(2_500)))
                .andExpect(status().isCreated());
        assertThat(strongBalance(token, accountUid)).isEqualTo(10_000L);
    }

    private String openAccount(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-E11\",\"currency\":\"GBP\"}"))
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
