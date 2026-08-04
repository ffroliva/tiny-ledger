package com.flaviooliva.ledger.audit.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Spec §14 step 7: the append-only trail the auditor endpoints read. */
public interface AuditTrailPort {

    /** Idempotent on {@code (accountId, streamVersion)} — Kafka delivery is at-least-once. */
    void record(AuditEntry entry);

    List<AuditEntry> entriesFor(UUID accountId);

    record AuditEntry(UUID accountId, String eventType, long streamVersion, Instant occurredAt, String payload) {}
}
