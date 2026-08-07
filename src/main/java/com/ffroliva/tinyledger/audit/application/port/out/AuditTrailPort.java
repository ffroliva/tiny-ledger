package com.ffroliva.tinyledger.audit.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Spec §14 step 7: the append-only trail the auditor endpoints read. */
public interface AuditTrailPort {

    /** Idempotent on {@code (accountId, streamVersion)} — Kafka delivery is at-least-once. */
    void recordEntry(AuditEntry entry);

    /** Spec §7 {@code getEvents}: one page of an account's own events, in stream order. */
    Page eventStream(UUID accountId, String cursor, int limit);

    /** Spec §7 {@code listAuditEntries}: one page of the trail across accounts, newest first. */
    Page trail(TrailQuery query);

    record AuditEntry(
            UUID accountId,
            String eventType,
            long streamVersion,
            Instant occurredAt,
            Instant recordedAt,
            String payload,
            String actor) {}

    /** A {@code null} bound is an absent filter; {@code null} {@code accountId} spans every account. */
    record TrailQuery(UUID accountId, String cursor, int limit, Instant from, Instant to) {}

    /** {@code nextCursor} is null once the page is the last one (§7). */
    record Page(List<AuditEntry> entries, String nextCursor) {}
}
