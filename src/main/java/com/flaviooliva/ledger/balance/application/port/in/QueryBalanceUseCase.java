package com.flaviooliva.ledger.balance.application.port.in;

import com.flaviooliva.ledger.shared.AccountId;
import java.util.Optional;

public interface QueryBalanceUseCase {
    Optional<BalanceView> balance(AccountId accountId);
}
