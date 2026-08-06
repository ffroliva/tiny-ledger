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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

/**
 * E10 — Redis unavailable, and the application keeps serving.
 *
 * <p><strong>Why this is not covered by {@code RateLimitFilterTest}.</strong> That test already proves the
 * fail-open branch: hand {@code probe} a store that throws {@link io.lettuce.core.RedisException} and the
 * request passes unmetered. What it cannot prove is the premise it simulates — that a <em>real</em> Redis
 * outage produces a {@code RedisException} at all, and produces it quickly. If the real failure mode were a
 * different exception type, the catch at {@code RateLimitFilter:171} would miss it and every request would
 * become a 500; if the 250 ms timeout on {@code RateLimitConfig:93} were not applied, the request would
 * instead hang on Lettuce's default and saturate Tomcat's worker pool — which that javadoc calls out as
 * strictly worse than the 500 it replaces. Both are assumptions about live infrastructure, and until now
 * neither had ever been executed.
 *
 * <p><strong>Pause, not stop.</strong> {@code docker pause} freezes the process with its port mapping and
 * connections intact, so Lettuce's established connection simply stops receiving replies — which is the
 * shape of a real outage and, unlike stopping the container, is reversible inside a shared Spring context
 * (ADR 0003). The unpause is in a {@code finally}: a failure here must not leave Redis frozen for every
 * class that runs after this one. Failsafe is not configured for parallel execution — verified against
 * {@code pom.xml}, which sets no {@code parallel}, {@code forkCount} or {@code threadCount} — so no other
 * test class is in flight while this one holds Redis down.
 */
class RedisOutageIT extends AbstractIntegrationTest {

    /**
     * The 250 ms command timeout has to fire twice per request — once for the identity bucket and once for
     * the IP backstop — so the floor for a fail-open request is ~500 ms. Two seconds is a deliberately loose
     * ceiling: the assertion being made is "bounded", not "fast", and the failure it must catch is Lettuce's
     * unbounded default, which is 60 seconds. A tighter bound would trade the finding for flakiness on a
     * loaded runner.
     */
    private static final Duration BOUNDED = Duration.ofSeconds(2);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aWriteStillSucceedsWhileRedisIsDownAndDoesNotHangOnIt() throws Exception {
        String token = bearer("alice");
        String accountUid = openAccount(token);

        String containerId = REDIS.getContainerId();
        long elapsedMillis;
        try {
            DockerClientFactory.instance()
                    .client()
                    .pauseContainerCmd(containerId)
                    .exec();

            long startedAt = System.nanoTime();
            // Fails open, so this write is never charged to any bucket — which is also why it cannot
            // disturb the budgets AbstractIntegrationTest derives for the rest of the suite.
            mockMvc.perform(put("/api/v1/accounts/" + accountUid + "/deposits/" + UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(deposit(2_500)))
                    .andExpect(status().isCreated());
            elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            // The write is the record (ADR 0002), and the record is Postgres — so the strong read must
            // still be exactly right with the cache unreachable. This is the assertion that separates
            // "rate limiting failed open" from "the ledger degraded".
            assertThat(strongBalance(token, accountUid)).isEqualTo(2_500L);
        } finally {
            DockerClientFactory.instance()
                    .client()
                    .unpauseContainerCmd(containerId)
                    .exec();
        }

        assertThat(Duration.ofMillis(elapsedMillis))
                .as("a fail-open request must cost a bounded stall, not Lettuce's 60s default")
                .isLessThan(BOUNDED);

        // Recovery, and the positive twin of everything above: without it, an implementation that had
        // simply stopped rate-limiting altogether would satisfy this whole test.
        mockMvc.perform(put("/api/v1/accounts/" + accountUid + "/deposits/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deposit(1_500)))
                .andExpect(status().isCreated());
        assertThat(strongBalance(token, accountUid)).isEqualTo(4_000L);
    }

    private String openAccount(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-E10\",\"currency\":\"GBP\"}"))
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
