package com.ffroliva.tinyledger.balance.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.balance.application.port.in.*;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.ledger.domain.*;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresBalanceProjectionIT extends AbstractIntegrationTest
        implements com.ffroliva.tinyledger.contract.BalanceProjectionContract {

    @Autowired
    private BalanceProjectionPort projection;

    @Override
    public BalanceProjectionPort projection() {
        return projection;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Currency GBP = Currency.getInstance("GBP");

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE account_history, balance_projections CASCADE");
    }

    @Test
    void applyAccountOpenedCreatesBalanceAndAccount() {
        AccountId id = AccountId.random();
        Instant now = Instant.parse("2026-08-04T12:00:00Z");

        projection.apply(new AccountOpened(id, 1, now, "alice", "ACC-001", GBP));

        Optional<BalanceView> balance = projection.balance(id);
        assertThat(balance).isPresent();
        assertThat(balance.get().amount().minorUnits()).isZero();
        assertThat(balance.get().streamVersion()).isEqualTo(1);

        Optional<AccountView> account = projection.account(id);
        assertThat(account).isPresent();
        assertThat(account.get().name()).isEqualTo("ACC-001");
        assertThat(account.get().owner()).isEqualTo("alice");
    }

    @Test
    void applyDepositUpdatesBalanceAndCreatesHistory() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        Instant t1 = Instant.parse("2026-08-04T12:01:00Z");
        UUID movementUid = UUID.randomUUID();

        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));
        projection.apply(new MoneyDeposited(
                id, 2, t1, movementUid, Money.of("GBP", 5000), "salary", Money.of("GBP", 5000), null));

        BalanceView balance = projection.balance(id).orElseThrow();
        assertThat(balance.amount().minorUnits()).isEqualTo(5000);
        assertThat(balance.streamVersion()).isEqualTo(2);

        HistoryPage page = projection.history(id, new HistoryQuery(null, 10, null, null));
        assertThat(page.transactions()).hasSize(1);
        assertThat(page.transactions().getFirst().type()).isEqualTo(MovementType.DEPOSIT);
        assertThat(page.transactions().getFirst().amount().minorUnits()).isEqualTo(5000);
    }

    @Test
    void applyIsIdempotent() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        Instant t1 = Instant.parse("2026-08-04T12:01:00Z");

        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));
        projection.apply(new MoneyDeposited(
                id, 2, t1, UUID.randomUUID(), Money.of("GBP", 5000), "salary", Money.of("GBP", 5000), null));
        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP)); // replay of the opening

        BalanceView balance = projection.balance(id).orElseThrow();
        assertThat(balance.amount().minorUnits()).isEqualTo(5000);
        assertThat(balance.streamVersion()).isEqualTo(2);
        // The staleness marker is a high-water mark: a replayed opening must not drag it backwards.
        assertThat(balance.asOf()).isEqualTo(t1);
    }

    @Test // §9.2b: the same-millisecond tie-break the in-memory projection has to mirror
    void sameMillisecondTiesBreakOnUuidBytewiseUnsigned() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));

        // Straddling the sign boundary of the most, then the least, significant bits.
        List<UUID> descending = List.of(
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("00000000-0000-0000-8000-000000000000"),
                UUID.fromString("00000000-0000-0000-7fff-ffffffffffff"));
        long version = 2;
        for (UUID uid : descending) {
            projection.apply(new MoneyDeposited(
                    id, version++, t0.plusSeconds(60), uid, Money.of("GBP", 100), "tx", Money.of("GBP", 100), null));
        }

        HistoryPage page = projection.history(id, new HistoryQuery(null, 10, null, null));

        assertThat(page.transactions().stream().map(TransactionView::transactionUid))
                .containsExactlyElementsOf(descending);
    }

    @Test // transaction_time is stored truncated to millis, so a full-precision bound must be too
    void historyMinBoundIncludesTheRowItNames() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));

        Instant microsecondPrecision = t0.plusSeconds(60).plusNanos(500_000);
        projection.apply(new MoneyDeposited(
                id,
                2,
                microsecondPrecision,
                UUID.randomUUID(),
                Money.of("GBP", 100),
                "tx",
                Money.of("GBP", 100),
                null));

        HistoryPage page = projection.history(id, new HistoryQuery(null, 10, microsecondPrecision, null));

        assertThat(page.transactions()).hasSize(1);
    }

    // The mirror of the min bound: transaction_time is stored floored to the millisecond, so a max
    // bound landing earlier inside that same millisecond still names the row. Keeping the stored value
    // at full precision would push it past the bound here and diverge from the in-memory projection.
    @Test
    void historyMaxBoundIncludesTheRowItNames() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));

        Instant storedAt = t0.plusSeconds(60).plusNanos(700_000);
        projection.apply(new MoneyDeposited(
                id, 2, storedAt, UUID.randomUUID(), Money.of("GBP", 100), "tx", Money.of("GBP", 100), null));

        Instant earlierInTheSameMilli = t0.plusSeconds(60).plusNanos(200_000);
        HistoryPage page = projection.history(id, new HistoryQuery(null, 10, null, earlierInTheSameMilli));

        assertThat(page.transactions()).hasSize(1);
    }

    @Test
    void historySupportsKeysetPagination() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));

        for (int i = 0; i < 5; i++) {
            Instant t = t0.plusSeconds(60 * (i + 1));
            projection.apply(new MoneyDeposited(
                    id,
                    2 + i,
                    t,
                    UUID.randomUUID(),
                    Money.of("GBP", 100 * (i + 1)),
                    "tx-" + i,
                    Money.of("GBP", 100 * (i + 1) * (i + 1)),
                    null));
        }

        // Page 1: limit 2
        HistoryPage page1 = projection.history(id, new HistoryQuery(null, 2, null, null));
        assertThat(page1.transactions()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();

        // Page 2: keyset cursor
        HistoryPage page2 = projection.history(id, new HistoryQuery(page1.nextCursor(), 2, null, null));
        assertThat(page2.transactions()).hasSize(2);
        assertThat(page2.nextCursor()).isNotNull();

        // Page 3: last page
        HistoryPage page3 = projection.history(id, new HistoryQuery(page2.nextCursor(), 2, null, null));
        assertThat(page3.transactions()).hasSize(1);
        assertThat(page3.nextCursor()).isNull();
    }

    @Test // F2: cursor.epochMilli() truncates sub-millisecond precision — rows sharing the boundary
    // row's millisecond but differing only below it must not fall through both cursor arms.
    void historyPaginationDoesNotDropRowsSharingAMillisecond() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));

        Instant sameMillis = t0.plusSeconds(60);
        List<UUID> uids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            UUID movementUid = UUID.randomUUID();
            uids.add(movementUid);
            projection.apply(new MoneyDeposited(
                    id,
                    1 + i,
                    sameMillis.plusNanos(i * 100_000L), // distinct microseconds, same millisecond
                    movementUid,
                    Money.of("GBP", 100 * i),
                    "tx-" + i,
                    Money.of("GBP", 100 * i),
                    null));
        }

        HistoryPage page1 = projection.history(id, new HistoryQuery(null, 2, null, null));
        assertThat(page1.transactions()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();

        HistoryPage page2 = projection.history(id, new HistoryQuery(page1.nextCursor(), 2, null, null));

        List<UUID> seenAcrossPages = Stream.concat(page1.transactions().stream(), page2.transactions().stream())
                .map(TransactionView::transactionUid)
                .toList();
        assertThat(seenAcrossPages).containsExactlyInAnyOrderElementsOf(uids);
    }

    @Test
    void accountsOwnedByReturnsOnlyMatchingOwner() {
        AccountId id1 = AccountId.random();
        AccountId id2 = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");

        projection.apply(new AccountOpened(id1, 1, t0, "alice", "ACC-001", GBP));
        projection.apply(new AccountOpened(id2, 1, t0.plusSeconds(1), "bob", "ACC-002", GBP));

        List<AccountView> aliceAccounts = projection.accountsOwnedBy("alice");
        assertThat(aliceAccounts).hasSize(1);
        assertThat(aliceAccounts.getFirst().accountId()).isEqualTo(id1);
    }
}
