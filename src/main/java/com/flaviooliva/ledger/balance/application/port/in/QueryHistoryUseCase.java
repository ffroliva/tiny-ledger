package com.flaviooliva.ledger.balance.application.port.in;

import com.flaviooliva.ledger.shared.AccountId;

public interface QueryHistoryUseCase {
    HistoryPage history(AccountId accountId, HistoryQuery query);
}
