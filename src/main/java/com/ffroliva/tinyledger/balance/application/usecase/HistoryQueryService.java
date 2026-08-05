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
    public HistoryPage history(String caller, AccountId accountId, HistoryQuery query) {
        // The caller is checked by the authorisation decorator at the port boundary (§6.4), not here —
        // this service answers the question, it does not decide who may ask it.
        return projection.history(accountId, query);
    }
}
