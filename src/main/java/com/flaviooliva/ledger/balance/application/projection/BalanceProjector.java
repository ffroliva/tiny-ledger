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
        switch (event) {
            case MoneyDeposited d -> cache.evict(d.accountId());
            case MoneyWithdrawn w -> cache.evict(w.accountId());
            default -> {
                /* AccountOpened has nothing to invalidate; MovementRejected moves no money */
            }
        }
    }
}
