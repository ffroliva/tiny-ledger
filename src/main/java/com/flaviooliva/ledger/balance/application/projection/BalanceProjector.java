package com.flaviooliva.ledger.balance.application.projection;

import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.ledger.domain.MoneyDeposited;
import com.flaviooliva.ledger.ledger.domain.MoneyWithdrawn;

/** Spec §4.4/§6.2: applies the event, then evicts the cache entry the event invalidated. */
public class BalanceProjector {
    private final BalanceProjectionPort projection;
    private final BalanceCachePort cache;

    public BalanceProjector(BalanceProjectionPort projection, BalanceCachePort cache) {
        this.projection = projection;
        this.cache = cache;
    }

    public void on(LedgerEvent event) {
        projection.apply(event);
        // ponytail: eviction fires here, inside the still-open append transaction, before commit —
        // a concurrent read between this eviction and the commit can repopulate the cache with the
        // pre-write balance, stale for up to the 60s TTL. Bounded today by the staleness markers
        // (asOf/streamVersion) and consistency=strong; move eviction to post-commit (e.g. a
        // TransactionSynchronization) if that window ever needs to close.
        switch (event) {
            case MoneyDeposited d -> cache.evict(d.accountId());
            case MoneyWithdrawn w -> cache.evict(w.accountId());
            default -> {
                /* AccountOpened has nothing to invalidate; MovementRejected moves no money */
            }
        }
    }
}
