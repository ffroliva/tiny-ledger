package com.flaviooliva.ledger.balance.adapter.in.events;

import com.flaviooliva.ledger.balance.application.projection.BalanceProjector;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The only Spring-touching piece of {@code balance}: turns published ledger events into projection writes.
 *
 * <p>DEVIATION (pending spec-deviation ruling): the brief mandates {@code @ApplicationModuleListener}.
 * That annotation is not on this classpath at all ({@code spring-modulith-starter-core} does not pull
 * {@code spring-modulith-events-api}, and {@code spring-tx} is test-scope only). Adding those two
 * dependencies makes it compile but the method never fires: it is meta-annotated
 * {@code @TransactionalEventListener}, whose {@code fallbackExecution} defaults to {@code false}, and
 * Plan 1 standalone publishes outside any transaction with no {@code PlatformTransactionManager}.
 * Adding {@code spring-modulith-events-core} (its {@code @Async}/{@code @EnableAsync} support) breaks
 * startup outright — {@code EventPublicationAutoConfiguration} demands an {@code EventPublicationRegistry},
 * i.e. a database. Plain {@code @EventListener} delivers synchronously with zero new dependencies;
 * {@code LedgerEventsListenerTest} is the standing proof.
 */
@Component
public class LedgerEventsListener {
    private final BalanceProjector projector;

    public LedgerEventsListener(BalanceProjector projector) {
        this.projector = projector;
    }

    @EventListener
    void on(LedgerEvent event) {
        projector.on(event);
    }
}
