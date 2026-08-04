package com.flaviooliva.ledger.balance.application;

import static org.assertj.core.api.Assertions.*;

import com.flaviooliva.ledger.balance.adapter.out.inmemory.InMemoryBalanceProjection;
import com.flaviooliva.ledger.balance.adapter.out.inmemory.MapBalanceCache;
import com.flaviooliva.ledger.balance.application.port.in.*;
import com.flaviooliva.ledger.balance.application.projection.BalanceProjector;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec §4.4 / §6.2: projector semantics — E4 idempotency, E5 ordering, cache lifecycle. */
class BalanceProjectorTest {
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Instant T0 = Instant.parse("2026-08-03T12:00:00Z");
    private static final Money ZERO = new Money(GBP, 0);

    /** The §6.2 TTL contract is time-dependent — this is the mutable clock the cache reads. */
    private Instant now = T0;

    private final InMemoryBalanceProjection projection = new InMemoryBalanceProjection();
    private final MapBalanceCache cache = new MapBalanceCache(Duration.ofSeconds(60), () -> now);
    private final BalanceProjector projector = new BalanceProjector(projection, cache);

    private final AccountId account = AccountId.random();

    @Test
    void appliesDepositAndServesBalanceWithStaleness() {
        projector.on(opened(account, "alice"));
        projector.on(deposit(account, 2, 10_000, 10_000, T0.plusSeconds(1)));

        BalanceView view = projection.balance(account).orElseThrow();
        assertThat(view.amount()).isEqualTo(new Money(GBP, 10_000));
        assertThat(view.streamVersion()).isEqualTo(2L);
        assertThat(view.asOf()).isEqualTo(T0.plusSeconds(1)); // staleness marker = the event's own time
    }

    @Test
    void duplicateDeliveryIsAppliedOnce() {
        projector.on(opened(account, "alice"));
        MoneyDeposited redelivered = deposit(account, 2, 10_000, 10_000, T0.plusSeconds(1));
        projector.on(redelivered);
        projector.on(redelivered); // E4: at-least-once delivery must not double-credit

        assertThat(projection.balance(account).orElseThrow().amount()).isEqualTo(new Money(GBP, 10_000));
        assertThat(projection.history(account, all()).transactions()).hasSize(1);
    }

    @Test
    void outOfOrderDeliveryIsNotApplied() {
        projector.on(opened(account, "alice"));
        projector.on(deposit(account, 3, 5_000, 15_000, T0.plusSeconds(3))); // gap at v2

        BalanceView held = projection.balance(account).orElseThrow();
        assertThat(held.amount()).isEqualTo(ZERO); // v3 is buffered, not applied
        assertThat(held.streamVersion()).isEqualTo(1L);
        assertThat(projection.history(account, all()).transactions()).isEmpty();

        projector.on(deposit(account, 2, 10_000, 10_000, T0.plusSeconds(2))); // gap fills

        BalanceView caughtUp = projection.balance(account).orElseThrow();
        assertThat(caughtUp.amount()).isEqualTo(new Money(GBP, 15_000)); // E5: v2 then v3, in order
        assertThat(caughtUp.streamVersion()).isEqualTo(3L);
        assertThat(projection.history(account, all()).transactions())
                .extracting(TransactionView::balanceAfter)
                .containsExactly(new Money(GBP, 15_000), new Money(GBP, 10_000)); // newest first
    }

    @Test
    void rejectionsAffectNeitherBalanceNorHistory() {
        projector.on(opened(account, "alice"));
        projector.on(deposit(account, 2, 10_000, 10_000, T0.plusSeconds(1)));
        projector.on(new MovementRejected(
                account,
                3,
                T0.plusSeconds(2),
                UUID.randomUUID(),
                MovementType.WITHDRAWAL,
                new Money(GBP, 99_000),
                "insufficient-funds"));

        // the feed shows settled movements only; the raw event stream is the auditor's view
        assertThat(projection.balance(account).orElseThrow().amount()).isEqualTo(new Money(GBP, 10_000));
        assertThat(projection.history(account, all()).transactions()).hasSize(1);
    }

    @Test
    void historyIsNewestFirstKeysetPaginated() {
        projector.on(opened(account, "alice"));
        projector.on(deposit(account, 2, 1_000, 1_000, T0.plusSeconds(1)));
        projector.on(deposit(account, 3, 2_000, 3_000, T0.plusSeconds(2)));
        projector.on(deposit(account, 4, 3_000, 6_000, T0.plusSeconds(3)));

        HistoryPage first = projection.history(account, new HistoryQuery(null, 2, null, null));
        assertThat(first.transactions())
                .extracting(TransactionView::balanceAfter)
                .containsExactly(new Money(GBP, 6_000), new Money(GBP, 3_000));
        assertThat(first.transactions().getFirst().direction()).isEqualTo("IN");
        assertThat(first.transactions().getFirst().status()).isEqualTo("SETTLED");
        assertThat(first.nextCursor()).isNotNull();

        HistoryPage second = projection.history(account, new HistoryQuery(first.nextCursor(), 2, null, null));
        assertThat(second.transactions())
                .extracting(TransactionView::balanceAfter)
                .containsExactly(new Money(GBP, 1_000));
        assertThat(second.nextCursor()).isNull(); // null cursor = end of the feed

        HistoryPage windowed =
                projection.history(account, new HistoryQuery(null, 10, T0.plusSeconds(2), T0.plusSeconds(2)));
        assertThat(windowed.transactions())
                .extracting(TransactionView::balanceAfter)
                .containsExactly(new Money(GBP, 3_000)); // inclusive bounds
    }

    @Test
    void accountsProjectionServesOwnerScopedList() {
        AccountId alices = AccountId.random();
        AccountId bobs = AccountId.random();
        projector.on(opened(alices, "alice"));
        projector.on(opened(bobs, "bob"));

        assertThat(projection.accountsOwnedBy("alice"))
                .extracting(AccountView::accountId)
                .containsExactly(alices); // N12: owner scoping is a projection mechanism
        assertThat(projection.account(bobs).orElseThrow().owner()).isEqualTo("bob");
        assertThat(projection.account(AccountId.random())).isEmpty();
    }

    @Test
    void cacheEvictedOnMovementEvents() {
        projector.on(opened(account, "alice"));
        cache.put(account, new BalanceView(account, ZERO, T0, 1));
        assertThat(cache.get(account)).isPresent();

        projector.on(deposit(account, 2, 10_000, 10_000, T0.plusSeconds(1)));

        assertThat(cache.get(account)).isEmpty(); // §6.2 event-driven eviction
    }

    @Test
    void mapCacheExpiresByTtl() {
        cache.put(account, new BalanceView(account, ZERO, T0, 1));
        assertThat(cache.get(account)).isPresent();

        now = T0.plusSeconds(61); // §6.2: 60 s TTL

        assertThat(cache.get(account)).isEmpty();
    }

    private static HistoryQuery all() {
        return new HistoryQuery(null, 50, null, null);
    }

    private static AccountOpened opened(AccountId id, String owner) {
        return new AccountOpened(id, 1, T0, owner, "ACC-" + owner, GBP);
    }

    private static MoneyDeposited deposit(AccountId id, long version, long amount, long balanceAfter, Instant at) {
        return new MoneyDeposited(
                id, version, at, UUID.randomUUID(), new Money(GBP, amount), "ref", new Money(GBP, balanceAfter));
    }
}
