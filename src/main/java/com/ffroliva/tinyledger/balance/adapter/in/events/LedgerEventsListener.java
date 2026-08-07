package com.ffroliva.tinyledger.balance.adapter.in.events;

import com.ffroliva.tinyledger.balance.application.projection.BalanceProjector;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The only Spring-touching piece of {@code balance}: turns published ledger events into projection writes.
 *
 * <p>RATIFIED (spec v3.5, §4.3 "Standalone caveat"): the brief mandates {@code @ApplicationModuleListener},
 * but that annotation is not on this classpath at all ({@code spring-modulith-starter-core} does not pull
 * {@code spring-modulith-events-api}, and {@code spring-tx} is test-scope only). Adding those two
 * dependencies makes it compile but the method never fires: it is meta-annotated
 * {@code @TransactionalEventListener}, whose {@code fallbackExecution} defaults to {@code false}, and
 * Plan 1 standalone publishes outside any transaction with no {@code PlatformTransactionManager}.
 * Adding {@code spring-modulith-events-core} (its {@code @Async}/{@code @EnableAsync} support) breaks
 * startup outright — {@code EventPublicationAutoConfiguration} demands an {@code EventPublicationRegistry},
 * i.e. a database. Plain {@code @EventListener} delivers synchronously with zero new dependencies;
 * {@code LedgerEventsListenerTest} is the standing proof.
 *
 * <p>The full profile did <em>not</em> flip it back (spec v3.8): only the Kafka externalisation leg is
 * a persisted listener, so this projection stays synchronous in both run modes and read-your-writes is
 * identical in each — see ADR 0001, "Only persisted listeners get a publication row". Moving the
 * projection off-thread is a Plan 3 question, not a difference between the modes.
 */
@Component
public class LedgerEventsListener {
    private final BalanceProjector projector;
    private final Tracer tracer;

    public LedgerEventsListener(BalanceProjector projector, Tracer tracer) {
        this.projector = projector;
        this.tracer = tracer;
    }

    /**
     * §6.6's "projection apply" span. A genuine CHILD, unlike the Kafka hop's link — and the shape of
     * the trace is itself the evidence for the claim the rest of §6.6 rests on. This listener is
     * synchronous, on the publishing thread, inside the write transaction, so its cost is part of the
     * request's cost and belongs nested inside the request's span. Projection lag here is structurally
     * zero (ADR 0004, §4.3), and a span that turned out NOT to be nested would be the first observation
     * to contradict that — which is why {@code ObservabilityIT} asserts the parent id rather than merely
     * asserting the span exists.
     */
    @EventListener
    void on(LedgerEvent event) {
        Span span = tracer.nextSpan().name("ledger.projection.apply").start();
        span.tag("ledger.account_id", event.accountId().value().toString());
        span.tag("ledger.stream_version", Long.toString(event.version()));
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            projector.on(event);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
