package com.ffroliva.tinyledger.audit.adapter.in.events;

import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the externalized ledger stream (ADR 0001). The record key and headers carry everything this
 * listener needs to route and stamp an entry, so it never deserialises the payload into the ledger's
 * domain types — the payload is stored verbatim and the module stays independent of the write side's
 * event shapes.
 *
 * <p>{@code actor} is the one field read back out of the payload, generically — the same "parse it
 * back as JSON, not as domain knowledge" stance {@code AuditController} already takes reading the
 * trail out again (a {@code Map}, never a domain type). It exists to check one thing: **the event
 * payload is the record; the audit trail is a projection of it.** `actor` crosses to this module as a
 * fourth header (§4.3/§6.4/§15.11), not a payload parse — but a header that disagrees with the
 * payload it was derived from is a fault, not a value to silently prefer, so {@link #actorOf} throws
 * instead of recording it. {@code FullAdapterConfig}'s error handler parks the thrown record on
 * {@code ledger.events.DLT}, the same path a record this listener cannot otherwise process already
 * takes. A rebuild is this same listener replaying from offset zero (§14 step 7), not a second code
 * path, so the check holds on rebuild exactly as it does live.
 */
public class AuditKafkaListener {

    /**
     * §15.10: absence of the `actor` header reads as `actor = owner` (stored as a literal {@code null},
     * interpreted by convention at the API boundary — §7) only for events that occurred before this
     * instant. On or after it, every publisher stamps `actor` unconditionally, so a missing header is
     * a defect — the trail records the literal string {@code "unknown"} rather than silently looking
     * like pre-feature behaviour.
     */
    static final Instant CUTOVER = Instant.parse("2026-08-07T00:00:00Z");

    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PAYLOAD_FIELDS = new TypeReference<>() {};

    private final AuditTrailPort trail;

    public AuditKafkaListener(AuditTrailPort trail) {
        this.trail = trail;
    }

    // Topic literal rather than a shared constant: the audit module consumes this stream as an
    // external contract, not as a compile-time dependency on the publisher.
    @KafkaListener(topics = "ledger.events", groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> record) {
        Instant occurredAt = Instant.parse(header(record, "occurred-at"));
        trail.record(new AuditTrailPort.AuditEntry(
                UUID.fromString(record.key()),
                header(record, "event-type"),
                Long.parseLong(header(record, "stream-version")),
                occurredAt,
                // §7's recordedAt: when the audit module saw the event, which is here — the Kafka hop is
                // exactly the gap between this and occurredAt.
                Instant.now(),
                record.value(),
                actorOf(record, occurredAt)));
    }

    private static String actorOf(ConsumerRecord<String, String> record, Instant occurredAt) {
        Header header = record.headers().lastHeader("actor");
        String headerActor = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        String payloadActor = payloadActor(record.value());
        if (headerActor != null && payloadActor != null && !headerActor.equals(payloadActor)) {
            // §15.11: the header disagrees with the record it was derived from — a fault, surfaced by
            // refusing to record it at all rather than guessing which of the two to trust.
            throw new IllegalStateException("audit fault: actor header '%s' disagrees with payload actor '%s' for %s"
                    .formatted(headerActor, payloadActor, record.key()));
        }
        if (headerActor != null) return headerActor;
        return occurredAt.isBefore(CUTOVER) ? null : "unknown";
    }

    /** One flat field, read generically — not the domain event this listener otherwise never knows. */
    private static String payloadActor(String payload) {
        Object actor = PAYLOAD_READER.readValue(payload, PAYLOAD_FIELDS).get("actor");
        return actor == null ? null : actor.toString();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) throw new IllegalStateException("ledger event without header: " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
