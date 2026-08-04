package com.flaviooliva.ledger.balance.application.usecase;

import com.flaviooliva.ledger.balance.application.port.in.AccountView;
import com.flaviooliva.ledger.balance.application.port.in.QueryAccountsUseCase;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
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
