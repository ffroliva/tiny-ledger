package com.ffroliva.tinyledger.balance.application.port.out;

import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.Optional;

public interface BalanceCachePort {
    Optional<BalanceView> get(AccountId accountId);

    /** Implementations honour the 60 s TTL (§6.2). */
    void put(AccountId accountId, BalanceView view);

    void evict(AccountId accountId);
}
