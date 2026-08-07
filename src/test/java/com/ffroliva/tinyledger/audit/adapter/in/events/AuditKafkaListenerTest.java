package com.ffroliva.tinyledger.audit.adapter.in.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

/**
 * §15.10/N18: the cutover comparison lives entirely in the header-to-column mapping, so it is provable
 * without Kafka, Postgres or Spring — just the listener and a fake {@link AuditTrailPort}. N18 itself
 * (a post-cutover event with no {@code actor}) cannot be driven through the HTTP API — no endpoint
 * emits an event without stamping one (§4.1 step 4) — so this is its only executable form (§9.3).
 *
 * <p>§15.11: the same is true of the header/payload disagreement check — it lives in this listener,
 * so it is provable the same way, with no Kafka needed to prove the throw. {@code KafkaAuditModuleIT}
 * proves the other half: that the throw actually reaches the dead-letter topic.
 */
class AuditKafkaListenerTest {

    private static final UUID ACCOUNT = UUID.randomUUID();

    private AuditTrailPort.AuditEntry recorded;

    // AuditTrailPort has three abstract methods, so it is not a functional interface — a capturing
    // lambda cannot implement it. This fake needs only `record`; the other two are unused here.
    private final SimpleTracer tracer = new SimpleTracer();

    private final AuditKafkaListener listener = new AuditKafkaListener(
            new AuditTrailPort() {
                @Override
                public void recordEntry(AuditEntry entry) {
                    recorded = entry;
                }

                @Override
                public Page eventStream(UUID accountId, String cursor, int limit) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Page trail(TrailQuery query) {
                    throw new UnsupportedOperationException();
                }
            },
            tracer);

    /**
     * §6.6's fan-out rule, proved here rather than only in {@code ObservabilityIT}: this needs no
     * broker, so it fails in seconds on the fast {@code verify} path instead of in minutes under
     * {@code -Pit}. The IT still asserts the same thing across a real hop, because only that can show
     * the {@code traceparent} was actually injected by the producer.
     */
    @Test
    void theConsumeSpanLinksToTheProducerAndIsARootRatherThanAChild() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        ConsumerRecord<String, String> record = consumed(Instant.parse("2026-08-10T00:00:00Z"), "trent", null);
        record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));

        listener.on(record);

        var span = tracer.onlySpan();
        assertThat(span.getName()).isEqualTo("ledger.audit.record");
        assertThat(span.getLinks())
                .as("a child would make the request span last until the slowest consumer finished")
                .hasSize(1);
        assertThat(span.getLinks().getFirst().getTraceContext().traceId())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(span.getEndTimestamp()).isNotNull();
    }

    @Test
    void aRecordWithNoTraceparentStillRecordsItsEntry() {
        listener.on(consumed(Instant.parse("2026-08-10T00:00:00Z"), "trent", null));

        assertThat(recorded.actor()).isEqualTo("trent");
        assertThat(tracer.onlySpan().getLinks())
                .as("telemetry is not a precondition of a compliance entry")
                .isEmpty();
    }

    @Test
    void aPresentActorHeaderIsStoredVerbatim() {
        listener.on(consumed(Instant.parse("2026-08-10T00:00:00Z"), "trent", null));

        assertThat(recorded.actor()).isEqualTo("trent");
    }

    @Test // §15.10: absence before the cutover reads as the owner — stored as literal absence, not guessed
    void aMissingActorHeaderBeforeTheCutoverIsStoredAsAbsent() {
        listener.on(consumed(AuditKafkaListener.CUTOVER.minusSeconds(1), null, null));

        assertThat(recorded.actor()).isNull();
    }

    @Test // N18: on/after the cutover every publisher stamps actor unconditionally — absence is a defect
    void aMissingActorHeaderOnOrAfterTheCutoverIsReportedAsUnknown() {
        listener.on(consumed(AuditKafkaListener.CUTOVER, null, null));

        assertThat(recorded.actor()).isEqualTo("unknown");
    }

    @Test // §15.11: a header dropped by a re-key/mirror/replay tool is not a contradiction — the payload
    // is the record, so its value is used rather than the entry being downgraded to null/"unknown"
    void aMissingActorHeaderWithAnActorInThePayloadUsesThePayloadValue() {
        listener.on(consumed(Instant.parse("2026-08-10T00:00:00Z"), null, "{\"actor\":\"trent\"}"));

        assertThat(recorded.actor()).isEqualTo("trent");
    }

    @Test // §15.11: the event payload is the record; a header that disagrees with it is a fault, not a guess
    void aHeaderThatDisagreesWithThePayloadsActorIsRejectedRatherThanStored() {
        ConsumerRecord<String, String> disagreeing =
                consumed(Instant.parse("2026-08-10T00:00:00Z"), "trent", "{\"actor\":\"alice\"}");

        assertThatThrownBy(() -> listener.on(disagreeing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trent")
                .hasMessageContaining("alice");
        assertThat(recorded).isNull();
    }

    private static ConsumerRecord<String, String> consumed(Instant occurredAt, String actor, String payload) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("event-type", "MoneyDeposited".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("stream-version", "2".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("occurred-at", occurredAt.toString().getBytes(StandardCharsets.UTF_8)));
        if (actor != null) headers.add(new RecordHeader("actor", actor.getBytes(StandardCharsets.UTF_8)));
        return new ConsumerRecord<>(
                "ledger.events",
                0,
                0L,
                0L,
                TimestampType.CREATE_TIME,
                -1,
                -1,
                ACCOUNT.toString(),
                payload == null ? "{}" : payload,
                headers,
                Optional.empty());
    }
}
