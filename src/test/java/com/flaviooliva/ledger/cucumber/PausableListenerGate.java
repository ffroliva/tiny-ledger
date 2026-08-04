package com.flaviooliva.ledger.cucumber;

import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.balance.application.projection.BalanceProjector;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Spec §9.3 E1–E5: the deliberate lag.
 *
 * <p>Standalone publishes events synchronously (spec v3.5, §4.3), so a write is projected before the
 * {@code PUT} returns and there is no window to observe. This gate is a {@code @Primary} {@link
 * BalanceProjector} registered in {@link CucumberSpringConfig}: while paused it holds events instead of
 * projecting them, which is the whole stale window E1 asserts and E2 closes. It also decides the
 * <em>order</em> events reach the projection, which is how E4 (redelivery) and E5 (ahead-of-stream
 * delivery) are stated without touching the transport.
 *
 * <p>Production code knows nothing about it: {@code LedgerEventsListener} asks for a {@code BalanceProjector}
 * and Spring hands it the primary one. Nothing in {@code src/main} consults test code.
 */
public class PausableListenerGate extends BalanceProjector {

    private final List<LedgerEvent> held = new ArrayList<>();
    private volatile boolean paused;

    public PausableListenerGate(BalanceProjectionPort projection, BalanceCachePort cache) {
        super(projection, cache);
    }

    @Override
    public void on(LedgerEvent event) {
        synchronized (held) {
            if (paused) {
                held.add(event);
                return;
            }
        }
        super.on(event);
    }

    public void pause() {
        paused = true;
    }

    /** Delivers everything still held, in arrival order, and reopens the gate. */
    public void resume() {
        List<LedgerEvent> pending;
        synchronized (held) {
            pending = List.copyOf(held);
            held.clear();
            paused = false;
        }
        deliver(pending);
    }

    public List<LedgerEvent> heldEvents() {
        synchronized (held) {
            return List.copyOf(held);
        }
    }

    /** Hands the given events straight to the projection, in the order given — redelivery included. */
    public void deliver(List<LedgerEvent> events) {
        events.forEach(super::on);
    }
}
