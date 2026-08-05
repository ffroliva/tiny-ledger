package com.ffroliva.tinyledger.balance.application.usecase;

import com.ffroliva.tinyledger.balance.application.port.in.AccountView;
import com.ffroliva.tinyledger.balance.application.port.in.QueryAccountsUseCase;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;
import java.util.Optional;

public class AccountsQueryService implements QueryAccountsUseCase {
    private final BalanceProjectionPort projection;

    public AccountsQueryService(BalanceProjectionPort projection) {
        this.projection = projection;
    }

    @Override
    public List<AccountView> accountsOwnedBy(String owner) {
        return projection.accountsOwnedBy(owner);
    }

    /**
     * §6.4/§6.5: a single-row lookup, not a filtered scan — {@code PostgresBalanceProjection} selects on
     * {@code WHERE account_id = ?} and the in-memory adapter is a map {@code get}. That matters because
     * every authorised balance and history read goes through it.
     */
    @Override
    public Optional<AccountView> account(AccountId accountId) {
        return projection.account(accountId);
    }
}
