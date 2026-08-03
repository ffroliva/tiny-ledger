package com.flaviooliva.ledger.ledger.application.port.in;

import com.flaviooliva.ledger.shared.AccountId;

public interface QueryStrongBalanceUseCase {
    StrongBalance strongBalance(String caller, AccountId accountId);
}
