package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;

public interface QueryStrongBalanceUseCase {
    StrongBalance strongBalance(String caller, AccountId accountId);
}
