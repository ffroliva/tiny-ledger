package com.flaviooliva.ledger.ledger.adapter.out.spring;

import com.flaviooliva.ledger.ledger.application.port.out.EventPublisherPort;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import org.springframework.context.ApplicationEventPublisher;

/** Spec §4.3: single publisher implementation, both modes. */
public class SpringEventPublisher implements EventPublisherPort {
    private final ApplicationEventPublisher delegate;

    public SpringEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(LedgerEvent event) {
        delegate.publishEvent(event);
    }
}
