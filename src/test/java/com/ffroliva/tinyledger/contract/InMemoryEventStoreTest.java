package com.ffroliva.tinyledger.contract;

import com.ffroliva.tinyledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;

class InMemoryEventStoreTest implements EventStoreContract {
    private final InMemoryEventStore store = new InMemoryEventStore();

    @Override
    public EventStorePort store() {
        return store;
    }
}
