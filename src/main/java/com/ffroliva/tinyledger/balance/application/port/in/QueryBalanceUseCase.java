package com.ffroliva.tinyledger.balance.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import java.util.Optional;

public interface QueryBalanceUseCase {
    Optional<BalanceView> balance(AccountId accountId);
}
