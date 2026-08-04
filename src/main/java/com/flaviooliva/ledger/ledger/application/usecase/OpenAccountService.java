package com.flaviooliva.ledger.ledger.application.usecase;

import com.flaviooliva.ledger.ledger.application.port.in.OpenAccount;
import com.flaviooliva.ledger.ledger.application.port.in.OpenAccountUseCase;
import com.flaviooliva.ledger.ledger.application.port.in.OpenedAccount;
import com.flaviooliva.ledger.ledger.application.port.out.ClockPort;
import com.flaviooliva.ledger.ledger.application.port.out.EventPublisherPort;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.application.port.out.IdGeneratorPort;
import com.flaviooliva.ledger.ledger.domain.Account;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.shared.AccountId;
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
