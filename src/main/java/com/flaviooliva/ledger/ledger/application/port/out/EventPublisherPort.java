package com.flaviooliva.ledger.ledger.application.port.out;

import com.flaviooliva.ledger.ledger.domain.LedgerEvent;

@FunctionalInterface
public interface EventPublisherPort {
    void publish(LedgerEvent event);
}
