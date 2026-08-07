package com.ffroliva.tinyledger.audit.adapter.in.events;

import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the externalized ledger stream (ADR 0001). The record key and headers carry everything this
 * listener needs to route and stamp an entry, so it never deserialises the payload into the ledger's
 * domain types — the payload is stored verbatim and the module stays independent of the write side's
 * event shapes.
 *
 * <p>{@code actor} is the one field read back out of the payload, generically — a {@code Map} lookup,
 * never a domain type, the same way {@code FullAdapterConfig} gives {@code PostgresEventStore} its own
 * {@link ObjectMapper} rather than the shared web one, so a schema change made for one side cannot
 * silently reshape what the other reads. It exists to check one thing: **the event payload is the
 * record; the audit trail is a projection of it.** `actor` crosses to this module as a fourth header
 * (§4.3/§6.4/§15.11) — but the header is an optimisation over the record, not a second source of
 * truth, so {@link #actorOf} treats the two asymmetrically: a disagreement between a *present* header
 * and a *present* payload value is irreconcilable and throws (parked on {@code ledger.events.DLT} by
 * {@code FullAdapterConfig}'s error handler, the same path a record this listener cannot otherwise
 * process already takes); a header dropped by a re-key, mirror or replay tool — §14 step 7's rebuild
 * chief among them — is not a contradiction, so the payload's own value is used and a WARN is logged
 * rather than the record being downgraded to {@code null}/{@code "unknown"}. A rebuild is this same
 * listener replaying from offset zero, not a second code path, so the check holds on rebuild exactly
 * as it does live.
 */
public class AuditKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(AuditKafkaListener.class);

    /**
     * §15.10 records this literal — this constant exists only because Java code cannot read the spec
     * file, so if the two ever disagree, §15.10 is correct and this should be changed to match, not
     * the reverse. Absence of the `actor` header reads as `actor = owner` (stored as a literal
     * {@code null}, interpreted by convention at the API boundary — §7) only for events that occurred
     * before this instant. On or after it, every publisher stamps `actor` unconditionally, so a header
     * missing **and unrecoverable from the payload** is a defect — the trail records the literal
     * string {@code "unknown"} rather than silently looking like pre-feature behaviour.
     */
    static final Instant CUTOVER = Instant.parse("2026-08-06T00:00:00Z");

    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PAYLOAD_FIELDS = new TypeReference<>() {};

    private final AuditTrailPort trail;
    private final Tracer tracer;

    public AuditKafkaListener(AuditTrailPort trail, Tracer tracer) {
        this.trail = trail;
        this.tracer = tracer;
    }

    /**
     * <strong>The consume span is LINKED to the producing span, not parented by it (§6.6).</strong>
     * One write fans out to balance, notification and audit concurrently; modelling those as children
     * of the HTTP span would make the request appear to last until the slowest of them finished, and
     * would misreport {@code http.server.duration} to every dashboard built on it. A link is the OTel
     * semantic for asynchronous fan-out, and it keeps the request's own duration honest.
     *
     * <p>Which is also why {@code spring.kafka.listener.observation-enabled} is declared {@code false}
     * rather than left unset: Spring Kafka's listener observation would create exactly the child this
     * refuses, and an omitted property gives a future reader no way to tell intent from oversight.
     */
    // Topic literal rather than a shared constant: the audit module consumes this stream as an
    // external contract, not as a compile-time dependency on the publisher.
    @KafkaListener(topics = "ledger.events", groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> consumed) {
        Span span = consumeSpan(consumed);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("ledger.account_id", consumed.key());
            span.tag("ledger.stream_version", header(consumed, "stream-version"));
            Instant occurredAt = Instant.parse(header(consumed, "occurred-at"));
            trail.recordEntry(new AuditTrailPort.AuditEntry(
                    UUID.fromString(consumed.key()),
                    header(consumed, "event-type"),
                    Long.parseLong(header(consumed, "stream-version")),
                    occurredAt,
                    // §7's recordedAt: when the audit module saw the event, which is here — the Kafka hop is
                    // exactly the gap between this and occurredAt.
                    Instant.now(),
                    consumed.value(),
                    actorOf(consumed, occurredAt)));
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * A new root, linked back to the producer — never a child. An absent or malformed
     * {@code traceparent} yields a span with no link rather than a failure: see {@link TraceparentRef}.
     */
    private Span consumeSpan(ConsumerRecord<String, String> consumed) {
        Span.Builder builder = tracer.spanBuilder().name("ledger.audit.record").setNoParent();
        Header traceparent = consumed.headers().lastHeader("traceparent");
        TraceparentRef.parse(traceparent == null ? null : new String(traceparent.value(), StandardCharsets.UTF_8))
                .map(ref -> ref.toTraceContext(tracer.traceContextBuilder()))
                .ifPresent(context -> builder.addLink(new Link(context)));
        return builder.start();
    }

    /**
     * §15.11's presence/value table:
     *
     * <pre>
     * header    payload            outcome
     * present   present, agrees    use it
     * present   present, disagrees fault -> DLT (irreconcilable)
     * absent    present            use the payload's value, and WARN (a dropped header, not a fault)
     * absent    absent             cutover logic
     * </pre>
     */
    private static String actorOf(ConsumerRecord<String, String> consumed, Instant occurredAt) {
        Header header = consumed.headers().lastHeader("actor");
        String headerActor = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        String payloadActor = payloadActor(consumed.value());
        if (headerActor != null) {
            if (payloadActor != null && !headerActor.equals(payloadActor)) {
                // The record it was derived from disagrees — irreconcilable, so refuse to record either
                // value rather than guess which of the two to trust.
                throw new IllegalStateException(
                        "audit fault: actor header '%s' disagrees with payload actor '%s' for %s"
                                .formatted(headerActor, payloadActor, consumed.key()));
            }
            return headerActor;
        }
        if (payloadActor != null) {
            // The event payload is the record (§15.11): a dropped header is a transport gap, not a
            // contradiction, and a compliance trail losing a correctly-attributable entry is worse than
            // recording it with a warning.
            log.warn("actor header missing for {}, using payload's actor '{}'", consumed.key(), payloadActor);
            return payloadActor;
        }
        return occurredAt.isBefore(CUTOVER) ? null : "unknown";
    }

    /** One flat field, read generically — not the domain event this listener otherwise never knows. */
    private static String payloadActor(String payload) {
        Object actor = PAYLOAD_READER.readValue(payload, PAYLOAD_FIELDS).get("actor");
        return actor == null ? null : actor.toString();
    }

    private static String header(ConsumerRecord<String, String> consumed, String name) {
        Header header = consumed.headers().lastHeader(name);
        if (header == null) throw new IllegalStateException("ledger event without header: " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
