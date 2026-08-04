package com.ffroliva.tinyledger.balance.application.usecase;

import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.Optional;

/** Spec §6.2: read through the cache, populate it on a miss; eviction is event-driven. */
public class BalanceQueryService implements QueryBalanceUseCase {
    private final BalanceProjectionPort projection;
    private final BalanceCachePort cache;

    public BalanceQueryService(BalanceProjectionPort projection, BalanceCachePort cache) {
        this.projection = projection;
        this.cache = cache;
    }

    @Override
    public Optional<BalanceView> balance(AccountId accountId) {
        Optional<BalanceView> cached = cache.get(accountId);
        if (cached.isPresent()) return cached;
        Optional<BalanceView> projected = projection.balance(accountId);
        projected.ifPresent(view -> cache.put(accountId, view));
        return projected;
    }
}
