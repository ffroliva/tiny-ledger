package com.ffroliva.tinyledger.ledger.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The gate that makes {@link LedgerEventType} worth having. Nothing here tests behaviour — it tests
 * that a refactor cannot silently change data already written to {@code events.event_type} and to the
 * {@code event-type} Kafka header.
 */
class LedgerEventTypeTest {

    /** The concrete leaves of the sealed hierarchy — the events that can actually be stored. */
    private static List<Class<?>> storableEvents(Class<?> root) {
        Class<?>[] permitted = root.getPermittedSubclasses();
        if (permitted == null) {
            return List.of(root);
        }
        List<Class<?>> leaves = new ArrayList<>();
        for (Class<?> child : permitted) {
            leaves.addAll(storableEvents(child));
        }
        return leaves;
    }

    @Test
    void everyStorableEventHasADurableName() {
        // Walks the sealed hierarchy, not a hand-written list: a new event type that nobody registers
        // fails here rather than at replay, months later, on data that cannot be re-emitted.
        Set<Class<?>> registered = new HashSet<>(LedgerEventType.registered().keySet());
        assertThat(registered).containsExactlyInAnyOrderElementsOf(storableEvents(LedgerEvent.class));
    }

    @Test
    void theNamesThemselvesAreFrozen() {
        // Hard-coded on purpose. These four strings are in the events table and on the Kafka wire.
        // If this fails, the change is a rename of stored data — which is never what was intended.
        // A genuinely new shape is a NEW event type, added alongside; it never replaces a name here.
        assertThat(LedgerEventType.registered().values())
                .containsExactlyInAnyOrder("AccountOpened", "MoneyDeposited", "MoneyWithdrawn", "MovementRejected");
    }

    @Test
    void namesRoundTripBackToTheirClass() {
        LedgerEventType.registered().forEach((type, name) -> assertThat(LedgerEventType.classOf(name))
                .isEqualTo(type));
    }

    @Test
    void anUnknownStoredNameFailsLoudly() {
        assertThatThrownBy(() -> LedgerEventType.classOf("MoneyTeleported"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MoneyTeleported");
    }
}
