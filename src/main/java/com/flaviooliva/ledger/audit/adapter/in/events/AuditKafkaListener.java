package com.flaviooliva.ledger.audit.adapter.in.events;

import com.flaviooliva.ledger.audit.application.port.out.AuditTrailPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Reads the externalized ledger stream (ADR 0001). Everything it needs arrives as the record key
 * and headers, so the audit module never parses a ledger event's JSON — the payload is stored
 * verbatim and the module stays independent of the write side's serialization format.
 */
public class AuditKafkaListener {

    private final AuditTrailPort trail;

    public AuditKafkaListener(AuditTrailPort trail) {
        this.trail = trail;
    }

    // Topic literal rather than a shared constant: the audit module consumes this stream as an
    // external contract, not as a compile-time dependency on the publisher.
    @KafkaListener(topics = "ledger.events", groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> record) {
        trail.record(new AuditTrailPort.AuditEntry(
                UUID.fromString(record.key()),
                header(record, "event-type"),
                Long.parseLong(header(record, "stream-version")),
                Instant.parse(header(record, "occurred-at")),
                record.value()));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) throw new IllegalStateException("ledger event without header: " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
