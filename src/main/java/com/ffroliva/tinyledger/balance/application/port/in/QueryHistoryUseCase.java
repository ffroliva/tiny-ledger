package com.ffroliva.tinyledger.balance.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;

public interface QueryHistoryUseCase {
    HistoryPage history(AccountId accountId, HistoryQuery query);
}
