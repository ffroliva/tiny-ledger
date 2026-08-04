package com.ffroliva.tinyledger.ledger.application.usecase;

import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.out.ClockPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventPublisherPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.application.port.out.IdGeneratorPort;
import com.ffroliva.tinyledger.ledger.domain.Account;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;

public class OpenAccountService implements OpenAccountUseCase {
    private final EventStorePort store;
    private final EventPublisherPort publisher;
    private final ClockPort clock;
    private final IdGeneratorPort ids;

    public OpenAccountService(
            EventStorePort store, EventPublisherPort publisher, ClockPort clock, IdGeneratorPort ids) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public OpenedAccount open(OpenAccount cmd) {
        AccountId accountId = new AccountId(ids.next());
        List<LedgerEvent> events = Account.open(accountId, cmd, clock.now());
        store.append(accountId, 0, events);
        events.forEach(publisher::publish);
        return new OpenedAccount(
                accountId, events.getLast().version(), events.getLast().occurredAt());
    }
}
