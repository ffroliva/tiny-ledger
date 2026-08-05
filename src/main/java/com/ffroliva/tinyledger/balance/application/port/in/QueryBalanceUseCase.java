package com.ffroliva.tinyledger.balance.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import java.util.Optional;

public interface QueryBalanceUseCase {
    /** §6.4: the caller is part of the query — a read the caller may not make is not a read. */
    Optional<BalanceView> balance(String caller, AccountId accountId);
}
