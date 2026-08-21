package com.ffroliva.tinyledger.ledger.domain;

import static org.assertj.core.api.Assertions.*;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Nothing here tests behaviour. It tests that a refactor cannot silently change data already written to
 * {@code events.event_type} and to the {@code event-type} Kafka header.
 *
 * <p>Most of that guarantee is the compiler's: {@link LedgerEvent#eventType()} is abstract, so a new
 * event type cannot join the hierarchy unnamed, and renaming a class breaks the {@code TYPE} references
 * in {@link LedgerEventType}. What is left here is what the compiler cannot see — that the read table
 * covers every event, that the literals themselves have not moved, and that naming an event did not
 * quietly change the shape of what gets stored.
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
    void everyStorableEventCanBeReadBack() {
        // The compiler forces an event to NAME itself; it cannot force anyone to register that name for
        // reads. Walking the sealed hierarchy closes the gap: a new event type missing from the table
        // fails here, not at a replay months later on data that cannot be re-emitted.
        Set<Class<?>> registered = new HashSet<>(LedgerEventType.registered().values());
        assertThat(registered).containsExactlyInAnyOrderElementsOf(storableEvents(LedgerEvent.class));
    }

    @Test
    void theNamesThemselvesAreFrozen() {
        // Hard-coded on purpose. These four strings are in the events table and on the Kafka wire.
        // A failure here is a rename of stored data, which is never what was intended: a genuinely new
        // shape is a NEW event type added alongside, never a new spelling of an existing one.
        assertThat(LedgerEventType.registered().keySet())
                .containsExactlyInAnyOrder(
                        "AccountOpened", "MoneyDeposited", "MoneyWithdrawn", "AssetTransferred", "MovementRejected");
    }

    @Test
    void namesRoundTripBackToTheirClass() {
        LedgerEventType.registered().forEach((name, type) -> assertThat(LedgerEventType.classOf(name))
                .isEqualTo(type));
    }

    @Test
    void anUnknownStoredNameFailsLoudly() {
        assertThatThrownBy(() -> LedgerEventType.classOf("MoneyTeleported"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MoneyTeleported");
    }

    @Test
    void namingAnEventDoesNotChangeWhatIsStored() {
        // eventType() is an accessor on the event, and the discriminator already lives in its own column.
        // If Jackson picked it up as a property it would be silently added to every payload written from
        // now on — a schema change smuggled in by the very fix meant to prevent one.
        AccountOpened opened = new AccountOpened(
                AccountId.random(),
                1L,
                Instant.parse("2026-08-10T07:00:00Z"),
                "alice",
                "ACC-001",
                Currency.getInstance("GBP"));

        assertThat(new ObjectMapper().writeValueAsString(opened)).doesNotContain("eventType");
    }
}
