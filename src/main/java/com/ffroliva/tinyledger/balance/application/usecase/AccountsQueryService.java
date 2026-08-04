package com.ffroliva.tinyledger.balance.application.usecase;

import com.ffroliva.tinyledger.balance.application.port.in.AccountView;
import com.ffroliva.tinyledger.balance.application.port.in.QueryAccountsUseCase;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import java.util.List;

public class AccountsQueryService implements QueryAccountsUseCase {
    private final BalanceProjectionPort projection;

    public AccountsQueryService(BalanceProjectionPort projection) {
        this.projection = projection;
    }

    @Override
    public List<AccountView> accountsOwnedBy(String owner) {
        return projection.accountsOwnedBy(owner);
    }
}
