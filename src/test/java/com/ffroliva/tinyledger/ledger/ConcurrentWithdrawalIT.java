package com.ffroliva.tinyledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * N2 — concurrent withdrawals, individually affordable, collectively over balance.
 *
 * <p>The invariant under test is the event store's optimistic version check, which is a Postgres
 * guarantee ({@code PostgresEventStore:67}), so this belongs here rather than only in the e2e suite: it is
 * the lowest layer that can still fail for the right reason, and stage {@code integration} actually runs on
 * every push while stage {@code e2e} has never executed.
 *
 * <p><strong>A bare 409 is not a terminal outcome.</strong> {@code RecordMovementService.record} does not
 * catch {@link com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException} — it
 * propagates to the caller as a 409. Under optimistic concurrency, retrying is the contract (§6.3), so each
 * branch retries until it reaches 201 or 422; a test that counted 409s as failures would be asserting the
 * opposite of the design.
 *
 * <p><strong>Both outcomes append.</strong> A refusal is a {@code MovementRejected} event, so all ten
 * branches contend for the same stream version, not just the five that settle — which is why the write
 * budget below is sized against retries and not against ten calls.
 *
 * <p><strong>MockMvc really is concurrent here.</strong> {@code MockMvc.perform} builds a fresh
 * {@code MockHttpServletRequest} and a fresh {@code MockFilterChain} per call and invokes the servlet on the
 * calling thread, so ten pool threads are ten genuinely overlapping requests through the real chain.
 */
class ConcurrentWithdrawalIT extends AbstractIntegrationTest {

    private static final int WRITERS = 10;

    /**
     * The retry ceiling that makes this test's write count <em>provable</em> rather than estimated, which is
     * what {@link AbstractIntegrationTest#LOWERED_WRITE_LIMIT} is derived from: {@code WRITERS *
     * MAX_ATTEMPTS} = 120 withdrawal calls plus the two setup writes, worst case, and never more. Twelve
     * because a single branch can in principle lose to each of the other nine and still need one winning
     * attempt of its own; below ten this test could fail for a reason that is not a ledger defect.
     */
    private static final int MAX_ATTEMPTS = 12;

    private static final String OPEN_BODY = """
            {"name":"ACC-N2","currency":"GBP"}""";

    private static final Logger log = LoggerFactory.getLogger(ConcurrentWithdrawalIT.class);

    /** Every withdrawal call made, retries included — the evidence that the branches actually raced. */
    private final AtomicInteger attempts = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tenParallelWithdrawalsSettleExactlyFiveAndNeverGoNegative() throws Exception {
        // Minted once, for the same reason RateLimitIT mints once: bearer() is a real password-grant round
        // trip, and ten threads doing that at the barrier would measure Keycloak, not the ledger.
        String token = bearer("alice");
        String accountUid = openAccountAndDeposit(token, 10_000L);

        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await(); // release all ten together, so the race is real and not a staircase
                    return withdrawUntilTerminal(token, accountUid, 2_000L);
                }));
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            statuses = futures.stream().map(ConcurrentWithdrawalIT::valueOf).toList();
        }

        log.info("N2: statuses={} across {} withdrawal calls", statuses, attempts.get());

        // containsOnly first: a 429 from the write limiter or a stray 500 must fail loudly here, naming the
        // status, rather than silently reducing the 201 count and reading as a ledger defect.
        assertThat(statuses)
                .as("one terminal HTTP status per branch")
                .hasSize(WRITERS)
                .containsOnly(201, 422);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(5);
        assertThat(strongBalanceMinorUnits(token, accountUid)).isZero();

        // Trap 1 in a new dress: ten branches that never overlapped would produce exactly this 5/5 split and
        // this zero balance while proving nothing whatsoever about concurrency — the version check would
        // never have been exercised. At least one 409 means at least two appends were genuinely in flight
        // against the same stream version. Measured on the first full -Pit run: statuses
        // [201,422,201,422,422,201,201,422,201,422] across 44 withdrawal calls — 34 conflicts for 10
        // outcomes, so contention is real and well inside the 120 ceiling, not merely assumed.
        assertThat(attempts.get())
                .as("withdrawal calls made; %d would mean no branch ever conflicted and this test is vacuous", WRITERS)
                .isGreaterThan(WRITERS)
                .isLessThanOrEqualTo(WRITERS * MAX_ATTEMPTS);
    }

    /** POST an account and PUT one deposit into it. Two charged writes. */
    private String openAccountAndDeposit(String token, long minorUnits) throws Exception {
        String created = mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_BODY))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String accountUid = JsonPath.read(created, "$.accountUid");

        mockMvc.perform(put("/api/v1/accounts/" + accountUid + "/deposits/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(minorUnits)))
                .andExpect(status().isCreated());
        return accountUid;
    }

    /**
     * Retry the same {@code movementUid} until the ledger answers something terminal. Reusing the uid across
     * attempts is deliberate and safe: a 409 means the append was rejected before any event was written, so
     * the uid is still unclaimed — and if a retry ever did land on a stored event, §6.3's replay path would
     * return the original answer rather than double-spending.
     *
     * <p>Any status that is neither 201, 422 nor 409 is returned as-is so the assertion can name it.
     */
    private int withdrawUntilTerminal(String token, String accountUid, long minorUnits) throws Exception {
        String movementUid = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            attempts.incrementAndGet();
            int status = mockMvc.perform(put("/api/v1/accounts/" + accountUid + "/withdrawals/" + movementUid)
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(movementBody(minorUnits)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (status != 409) return status;
        }
        return 409; // still conflicting after MAX_ATTEMPTS — surfaced by containsOnly(201, 422)
    }

    private long strongBalanceMinorUnits(String token, String accountUid) throws Exception {
        String body = mockMvc.perform(get("/api/v1/accounts/" + accountUid + "/balance")
                        .param("consistency", "strong")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.amount.minorUnits")).longValue();
    }

    private static String movementBody(long minorUnits) {
        return "{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":" + minorUnits + "}}";
    }

    private static int valueOf(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("a withdrawal branch failed outright", e);
        }
    }
}
