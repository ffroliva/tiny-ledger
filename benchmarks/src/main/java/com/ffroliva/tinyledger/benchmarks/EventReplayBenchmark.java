package com.ffroliva.tinyledger.benchmarks;

import com.ffroliva.tinyledger.ledger.domain.Account;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.ledger.domain.MoneyDeposited;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * §9.7's second JMH target: event replay.
 *
 * <p>This is the benchmark that matters for an event-sourced ledger, because replay cost is what
 * decides whether a strong read stays inside §9.7's p99, and it grows with an account's history —
 * unlike almost everything else in the system, it gets slower the longer the account has existed.
 *
 * <p>Parameterised by history length rather than fixed at one size: a single number tells you nothing
 * about whether {@link Account#rehydrate} is linear. If the per-event cost at 10,000 is materially
 * worse than at 10, something in the fold is not O(1) per event, and that is a finding a single
 * measurement would hide.
 *
 * <p>Deliberately no snapshotting is exercised, because none exists. This measures what the code does.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EventReplayBenchmark {

    /** Chosen to span three orders of magnitude, so non-linearity is visible rather than inferred. */
    @Param({"10", "100", "1000", "10000"})
    public int historyLength;

    private List<LedgerEvent> history;

    @Setup
    public void setup() {
        AccountId id = new AccountId(UUID.randomUUID());
        Currency gbp = Currency.getInstance("GBP");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        List<LedgerEvent> events = new ArrayList<>(historyLength + 1);
        events.add(new AccountOpened(id, 1L, t0, "alice", "current", gbp));

        // Deposits only: a withdrawal would have to respect the running balance, and a benchmark that
        // spends its setup avoiding an InsufficientFunds refusal is measuring its own fixture.
        long balance = 0L;
        for (int i = 0; i < historyLength; i++) {
            balance += 100L;
            events.add(new MoneyDeposited(
                    id,
                    i + 2L,
                    t0.plusSeconds(i + 1L),
                    UUID.randomUUID(),
                    new Money(gbp, 100L),
                    "bench-" + i,
                    new Money(gbp, balance),
                    "alice"));
        }
        history = List.copyOf(events);
    }

    @Benchmark
    public Account rehydrate() {
        return Account.rehydrate(history);
    }
}
