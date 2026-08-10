package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;

public sealed interface LedgerEvent permits AccountOpened, MovementEvent {
    AccountId accountId();

    long version();

    Instant occurredAt();

    /**
     * §2.3/§2.4: the principal that issued the command. On the three movement events it is a record
     * component, stamped by the use case (Account.deposit/withdraw) from the command's caller — never
     * from the account's owner. On {@link AccountOpened} it is derived from {@code owner()}: an account
     * has no owner to act on behalf of until it exists (§15.8). A legacy payload written before this
     * field existed deserialises with it absent (null) rather than failing to read at all — §15.9
     * records how that absence is interpreted.
     */
    String actor();

    /**
     * The permanent name of this event in storage — written to {@code events.event_type} and published as
     * the {@code event-type} Kafka header.
     *
     * <p><strong>Abstract on purpose.</strong> It was previously derived from
     * {@code getClass().getSimpleName()}, which made a class rename a silent data-loss event, and briefly
     * lived in a lookup table, which made an unregistered new event type a *test* failure rather than a
     * compile failure. Declared here, the compiler will not let a new event type join this hierarchy
     * without naming itself. Each implementation returns its own {@code TYPE} constant, and
     * {@link LedgerEventType} maps the same constants back for reads.
     *
     * <p>The returned value is data: once written it is readable forever, so a value never changes. A new
     * shape is a new event type standing alongside the old one, never a rename of it.
     */
    String eventType();
}
