package com.flaviooliva.ledger.balance.adapter.out.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.flaviooliva.ledger.balance.application.port.in.HistoryPage;
import com.flaviooliva.ledger.balance.application.port.in.HistoryQuery;
import com.flaviooliva.ledger.balance.application.port.in.TransactionView;
import com.flaviooliva.ledger.ledger.domain.AccountOpened;
import com.flaviooliva.ledger.ledger.domain.MoneyDeposited;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spec §9.2b: the two run modes must order identically. Postgres compares {@code uuid} bytewise and
 * unsigned, so it is the reference — {@link UUID#compareTo} is signed on the two longs and puts
 * {@code 80…} below {@code 7f…}, which is the opposite answer on a same-millisecond tie.
 */
class InMemoryBalanceProjectionTest {

    // Straddling the sign boundary of the most significant bits, then of the least significant bits.
    private static final UUID MSB_HIGH = UUID.fromString("80000000-0000-0000-0000-000000000000");
    private static final UUID MSB_LOW = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID LSB_HIGH = UUID.fromString("00000000-0000-0000-8000-000000000000");
    private static final UUID LSB_LOW = UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Instant T0 = Instant.parse("2026-08-04T12:00:00Z");

    private final InMemoryBalanceProjection projection = new InMemoryBalanceProjection();
    private final AccountId account = AccountId.random();

    @Test
    void sameMillisecondTiesBreakInPostgresUuidOrder() {
        seed();

        HistoryPage page = projection.history(account, new HistoryQuery(null, 10, null, null));

        assertThat(uids(page.transactions())).containsExactly(MSB_HIGH, MSB_LOW, LSB_HIGH, LSB_LOW);
    }

    @Test // the cursor's tie-break has to agree with the sort, or a page boundary drops or repeats a row
    void theKeysetCursorWalksTheSameOrder() {
        seed();

        HistoryPage first = projection.history(account, new HistoryQuery(null, 2, null, null));
        HistoryPage second = projection.history(account, new HistoryQuery(first.nextCursor(), 2, null, null));

        assertThat(uids(Stream.concat(first.transactions().stream(), second.transactions().stream())
                        .toList()))
                .containsExactly(MSB_HIGH, MSB_LOW, LSB_HIGH, LSB_LOW);
    }

    /**
     * The mirror of {@code PostgresBalanceProjectionIT.historyMinBoundIncludesTheRowItNames}: Postgres
     * stores {@code transaction_time} truncated to millis and truncates the bound too, so a bound
     * landing later inside the stored row's millisecond still includes it. Comparing full precision
     * here answered the opposite, which is the §9.2b divergence this closes.
     */
    @Test
    void aFilterBoundInsideTheRowsMillisecondStillIncludesIt() {
        projection.apply(new AccountOpened(account, 1, T0, "alice", "ACC-001", GBP));
        Instant storedAt = T0.plusSeconds(60).plusNanos(200_000); // …000200
        projection.apply(new MoneyDeposited(
                account, 2, storedAt, UUID.randomUUID(), Money.of("GBP", 100), "tx", Money.of("GBP", 100)));

        Instant laterInTheSameMilli = T0.plusSeconds(60).plusNanos(700_000); // …000700
        HistoryPage page = projection.history(account, new HistoryQuery(null, 10, laterInTheSameMilli, null));

        assertThat(page.transactions()).hasSize(1);
    }

    private void seed() {
        projection.apply(new AccountOpened(account, 1, T0, "alice", "ACC-001", GBP));
        long version = 2;
        // One millisecond, four movements: the uid is the only tie-break left.
        for (UUID uid : List.of(LSB_LOW, MSB_HIGH, LSB_HIGH, MSB_LOW)) {
            projection.apply(new MoneyDeposited(
                    account, version++, T0.plusSeconds(60), uid, Money.of("GBP", 100), "tx", Money.of("GBP", 100)));
        }
    }

    private static List<UUID> uids(List<TransactionView> transactions) {
        return transactions.stream().map(TransactionView::transactionUid).toList();
    }
}
