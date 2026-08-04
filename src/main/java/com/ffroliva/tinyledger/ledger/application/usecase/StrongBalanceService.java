package com.ffroliva.tinyledger.ledger.application.usecase;

import com.ffroliva.tinyledger.ledger.application.error.AccountNotFoundException;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.ledger.application.port.in.QueryStrongBalanceUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.StrongBalance;
import com.ffroliva.tinyledger.ledger.application.port.out.ClockPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.domain.Account;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;

public class StrongBalanceService implements QueryStrongBalanceUseCase {
    private final EventStorePort store;
    private final ClockPort clock;

    public StrongBalanceService(EventStorePort store, ClockPort clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public StrongBalance strongBalance(String caller, AccountId accountId) {
        List<LedgerEvent> history = store.read(accountId);
        if (history.isEmpty()) throw new AccountNotFoundException(accountId);
        Account account = Account.rehydrate(history);
        if (!account.owner().equals(caller)) throw new OwnershipException(caller, accountId);
        return new StrongBalance(accountId, account.balance(), clock.now(), account.version());
    }
}
