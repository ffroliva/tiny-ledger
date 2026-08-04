package com.flaviooliva.ledger.contract;

import com.flaviooliva.ledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;

class InMemoryEventStoreTest extends EventStoreContract {
    private final InMemoryEventStore store = new InMemoryEventStore();

    @Override
    protected EventStorePort store() {
        return store;
    }
}
