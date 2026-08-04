package com.ffroliva.tinyledger.ledger.adapter.out.spring;

import com.ffroliva.tinyledger.ledger.application.port.out.EventPublisherPort;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
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
