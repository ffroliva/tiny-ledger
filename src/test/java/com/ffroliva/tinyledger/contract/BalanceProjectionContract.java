package com.ffroliva.tinyledger.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.balance.application.port.in.AccountView;
import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryPage;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryQuery;
import com.ffroliva.tinyledger.balance.application.port.in.TransactionView;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.ledger.domain.MoneyDeposited;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * §9.2b for {@code BalanceProjectionPort} — "for every outbound port with more than one implementation, a
 * single abstract contract suite defines the port's semantics, and each adapter runs it".
 *
 * <p><strong>Why this exists.</strong> Three outbound ports have two implementations
 * ({@code EventStorePort}, {@code BalanceProjectionPort}, {@code BalanceCachePort}) and only the first had
 * a contract suite. This port's two adapters were checked by two <em>independent</em> classes whose
 * javadoc hand-mirrors the other — {@code InMemoryBalanceProjectionTest} literally says "the mirror of
 * {@code PostgresBalanceProjectionIT.historyMinBoundIncludesTheRowItNames}". Hand-mirroring is the thing
 * §9.2b replaces, and it had already drifted: as of 2026-08-07 the Postgres side asserted idempotent
 * apply, owner-filtered account listing and same-millisecond paging, and the in-memory side — which
 * serves the <em>entire</em> {@code standalone} mode — asserted none of the three.
 *
 * <p>An interface with {@code default} tests rather than an abstract class, for the reason
 * {@link EventStoreContract} already established: {@code PostgresBalanceProjectionIT} must also extend
 * {@code AbstractIntegrationTest} to get the containers, and Java has one superclass.
 *
 * <p>Scoped to the behaviours that were one-sided. The ordering and time-bound cases already exist on
 * both sides and are deliberately left where they are — moving working tests at the same time as adding
 * missing ones would make a failure ambiguous between the two.
 *
 * <p><strong>No divergence was found; the mechanism is the deliverable.</strong> Both adapters passed all
 * three on the first run, so nothing was broken — what was missing was anything that would notice if it
 * broke later, which is exactly what §9.2b asks for.
 *
 * <p>On red runs: {@code applyingTheSameEventTwiceDoesNotDoubleCount} cannot be falsified by a one-line
 * mutation of {@code InMemoryBalanceProjection}, because idempotency there is enforced <em>twice</em> —
 * the {@code version() <= applied} guard, and the pending buffer being keyed by version so a redelivery
 * collapses into the same entry. Flipping the guard alone leaves the suite green. Defeating both does
 * fail it, along with six other tests. Recorded rather than dressed up: "it went red" and "it went red
 * for this reason" are different claims, and this is the same defence-in-depth pattern
 * {@code docs/performance-findings.md} §6.7 documents for idempotent writes.
 */
public interface BalanceProjectionContract {

    Currency GBP = Currency.getInstance("GBP");
    Instant T0 = Instant.parse("2026-08-04T12:00:00Z");

    BalanceProjectionPort projection();

    /**
     * E4's port-level twin. The BDD scenario proves duplicate <em>delivery</em> is harmless end to end;
     * this proves the projection itself is idempotent, which is the property that makes it so. Kafka is
     * at-least-once, so the adapter that cannot do this silently doubles balances under redelivery.
     */
    @Test
    default void applyingTheSameEventTwiceDoesNotDoubleCount() {
        AccountId id = AccountId.random();
        UUID movementUid = UUID.randomUUID();
        projection().apply(new AccountOpened(id, 1, T0, "alice", "ACC-001", GBP, null));
        MoneyDeposited deposit = new MoneyDeposited(
                id, 2, T0.plusSeconds(60), movementUid, Money.of("GBP", 5_000), "salary", Money.of("GBP", 5_000), null);

        projection().apply(deposit);
        projection().apply(deposit);

        BalanceView balance = projection().balance(id).orElseThrow();
        assertThat(balance.amount().minorUnits()).isEqualTo(5_000);
        assertThat(balance.streamVersion()).isEqualTo(2);
        assertThat(projection()
                        .history(id, new HistoryQuery(null, 10, null, null))
                        .transactions())
                .hasSize(1);
    }

    /** Ownership is enforced above this port too, but an adapter that ignored it would leak account lists. */
    @Test
    default void accountsOwnedByReturnsOnlyTheCallersAccounts() {
        AccountId mine = AccountId.random();
        AccountId theirs = AccountId.random();
        projection().apply(new AccountOpened(mine, 1, T0, "alice", "ACC-MINE", GBP, null));
        projection().apply(new AccountOpened(theirs, 1, T0, "mallory", "ACC-THEIRS", GBP, null));

        assertThat(projection().accountsOwnedBy("alice").stream()
                        .map(AccountView::accountId)
                        .toList())
                .contains(mine)
                .doesNotContain(theirs);
    }

    /**
     * The page boundary, at the one place it is hardest: several movements inside a single millisecond, so
     * the timestamp cannot break the tie and the cursor must fall back to the uid. Walking one row at a
     * time crosses every boundary there is, and the union must be the whole set, once each — a repeat, a
     * skip and a reorder are three different faults and this refuses all of them.
     */
    @Test
    default void pagingOneAtATimeDropsNoRowSharingAMillisecond() {
        AccountId id = AccountId.random();
        projection().apply(new AccountOpened(id, 1, T0, "alice", "ACC-001", GBP, null));
        Instant sameMilli = T0.plusSeconds(60);
        List<UUID> uids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        long version = 2;
        for (UUID uid : uids) {
            projection()
                    .apply(new MoneyDeposited(
                            id, version++, sameMilli, uid, Money.of("GBP", 100), "tx", Money.of("GBP", 100), null));
        }

        List<UUID> unpaged = projection().history(id, new HistoryQuery(null, 50, null, null)).transactions().stream()
                .map(TransactionView::transactionUid)
                .toList();

        List<UUID> paged = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page <= uids.size(); page++) {
            HistoryPage next = projection().history(id, new HistoryQuery(cursor, 1, null, null));
            next.transactions().stream().map(TransactionView::transactionUid).forEach(paged::add);
            cursor = next.nextCursor();
            if (cursor == null) break;
        }

        assertThat(unpaged).containsExactlyInAnyOrderElementsOf(uids);
        assertThat(paged)
                .as("paging one at a time must return the same rows, once each, in the same order")
                .containsExactlyElementsOf(unpaged);
    }
}
