package com.ffroliva.tinyledger.ledger.application.port.out;

import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;

@FunctionalInterface
public interface EventPublisherPort {
    void publish(LedgerEvent event);
}
