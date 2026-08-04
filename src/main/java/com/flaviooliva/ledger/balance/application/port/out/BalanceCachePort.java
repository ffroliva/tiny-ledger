package com.flaviooliva.ledger.balance.application.port.out;

import com.flaviooliva.ledger.balance.application.port.in.BalanceView;
import com.flaviooliva.ledger.shared.AccountId;
import java.util.Optional;

public interface BalanceCachePort {
    Optional<BalanceView> get(AccountId accountId);

    /** Implementations honour the 60 s TTL (§6.2). */
    void put(AccountId accountId, BalanceView view);

    void evict(AccountId accountId);
}
