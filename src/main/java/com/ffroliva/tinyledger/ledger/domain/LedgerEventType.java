package com.ffroliva.tinyledger.ledger.domain;

import java.util.Map;

/**
 * Resolves a stored event name back to the class that reads it — the one direction the compiler cannot
 * help with, because the input is a string arriving from the database.
 *
 * <p>The forward direction is not here: {@link LedgerEvent#eventType()} is abstract, so the compiler
 * refuses an event type that has not named itself, and writers ask the event directly. This table is the
 * boundary parse for reads, keyed on the very same {@code TYPE} constants — so a name cannot be
 * mistyped here, and cannot drift from the one the writer used.
 *
 * <p>What is left for a test rather than the compiler is *coverage*: nothing forces a new event type to
 * appear in this map. {@code LedgerEventTypeTest} walks the sealed hierarchy and fails the build if one
 * is missing.
 *
 * <p>These names are data. Once a value has been written it stays readable forever, so a value never
 * changes — a new shape is a new event type alongside the old one, never a rename of it.
 */
public final class LedgerEventType {

    private static final Map<String, Class<? extends LedgerEvent>> TYPES = Map.of(
            AccountOpened.TYPE, AccountOpened.class,
            MoneyDeposited.TYPE, MoneyDeposited.class,
            MoneyWithdrawn.TYPE, MoneyWithdrawn.class,
            MovementRejected.TYPE, MovementRejected.class);

    private LedgerEventType() {}

    /** The class a stored name deserialises to. */
    public static Class<? extends LedgerEvent> classOf(String eventType) {
        Class<? extends LedgerEvent> type = TYPES.get(eventType);
        if (type == null) {
            throw new IllegalStateException("Unknown event type: " + eventType);
        }
        return type;
    }

    /** The table itself, so the test can prove it covers the sealed hierarchy. */
    public static Map<String, Class<? extends LedgerEvent>> registered() {
        return TYPES;
    }
}
