package com.ffroliva.tinyledger.ledger.domain;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * The durable name of each ledger event, decoupled from its Java class name.
 *
 * <p>These strings are <strong>data, not code</strong>. They are written to {@code events.event_type}
 * and published as the {@code event-type} Kafka header, so once a value has been stored it has to stay
 * readable forever. <strong>A value here can never change</strong> — a new spelling is a new event
 * type, never a rename of an existing one.
 *
 * <p>Both call sites previously derived the name from {@code getClass().getSimpleName()}, which made an
 * IDE rename a silent data-loss event: the discriminator written from then on would change while every
 * row already stored kept the old spelling, and the reader throws on a name it does not know. The
 * root-package rename in {@code 5dd71a7} survived only because {@code getSimpleName()} drops the
 * package; renaming the class itself has no such protection.
 *
 * <p>{@code LedgerEventTypeTest} walks the sealed hierarchy rather than a hand-written list, so an event
 * type added without a name here fails the build instead of failing a replay.
 */
public final class LedgerEventType {

    private static final Map<Class<? extends LedgerEvent>, String> NAMES = Map.of(
            AccountOpened.class, "AccountOpened",
            MoneyDeposited.class, "MoneyDeposited",
            MoneyWithdrawn.class, "MoneyWithdrawn",
            MovementRejected.class, "MovementRejected");

    private static final Map<String, Class<? extends LedgerEvent>> TYPES =
            NAMES.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private LedgerEventType() {}

    /** The stored name of an event. */
    public static String of(LedgerEvent event) {
        String name = NAMES.get(event.getClass());
        if (name == null) {
            throw new IllegalStateException("No durable event type registered for " + event.getClass());
        }
        return name;
    }

    /** The class a stored name deserialises to. */
    public static Class<? extends LedgerEvent> classOf(String eventType) {
        Class<? extends LedgerEvent> type = TYPES.get(eventType);
        if (type == null) {
            throw new IllegalStateException("Unknown event type: " + eventType);
        }
        return type;
    }

    /** The registry itself, so the test can prove it covers the sealed hierarchy. */
    public static Map<Class<? extends LedgerEvent>, String> registered() {
        return NAMES;
    }
}
