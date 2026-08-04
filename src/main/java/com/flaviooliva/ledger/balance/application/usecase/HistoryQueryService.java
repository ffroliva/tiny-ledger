package com.flaviooliva.ledger.balance.application.usecase;

import com.flaviooliva.ledger.balance.application.port.in.HistoryPage;
import com.flaviooliva.ledger.balance.application.port.in.HistoryQuery;
import com.flaviooliva.ledger.balance.application.port.in.QueryHistoryUseCase;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.shared.AccountId;

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
