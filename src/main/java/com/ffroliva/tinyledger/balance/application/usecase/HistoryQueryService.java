package com.ffroliva.tinyledger.balance.application.usecase;

import com.ffroliva.tinyledger.balance.application.port.in.HistoryPage;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryQuery;
import com.ffroliva.tinyledger.balance.application.port.in.QueryHistoryUseCase;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.shared.AccountId;

public class HistoryQueryService implements QueryHistoryUseCase {
    private final BalanceProjectionPort projection;

    public HistoryQueryService(BalanceProjectionPort projection) {
        this.projection = projection;
    }

    @Override
    public HistoryPage history(AccountId accountId, HistoryQuery query) {
        return projection.history(accountId, query);
    }
}
