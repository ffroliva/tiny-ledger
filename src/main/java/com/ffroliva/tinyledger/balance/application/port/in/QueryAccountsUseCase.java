package com.ffroliva.tinyledger.balance.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;
import java.util.Optional;

public interface QueryAccountsUseCase {
    List<AccountView> accountsOwnedBy(String owner);

    /** §6.4/§6.5: one account, so authorisation can tell "not yours" from "not there". */
    Optional<AccountView> account(AccountId accountId);
}
