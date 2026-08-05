package com.ffroliva.tinyledger.balance.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;

public interface QueryHistoryUseCase {
    /** §6.4: the caller is part of the query. */
    HistoryPage history(String caller, AccountId accountId, HistoryQuery query);
}
