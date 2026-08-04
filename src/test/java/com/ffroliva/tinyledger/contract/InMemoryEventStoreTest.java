package com.ffroliva.tinyledger.contract;

import com.ffroliva.tinyledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;

class InMemoryEventStoreTest extends EventStoreContract {
    private final InMemoryEventStore store = new InMemoryEventStore();

    @Override
    protected EventStorePort store() {
        return store;
    }
}
