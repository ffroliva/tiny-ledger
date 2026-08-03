package com.flaviooliva.ledger.ledger.application.usecase;

import com.flaviooliva.ledger.ledger.application.error.AccountNotFoundException;
import com.flaviooliva.ledger.ledger.application.error.OwnershipException;
import com.flaviooliva.ledger.ledger.application.port.in.QueryStrongBalanceUseCase;
import com.flaviooliva.ledger.ledger.application.port.in.StrongBalance;
import com.flaviooliva.ledger.ledger.application.port.out.ClockPort;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.domain.Account;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.shared.AccountId;
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
