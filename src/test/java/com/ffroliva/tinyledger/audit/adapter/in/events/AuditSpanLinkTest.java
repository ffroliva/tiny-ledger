package com.ffroliva.tinyledger.audit.adapter.in.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.otel.bridge.OtelTraceContextBuilder;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The parsing half of the Kafka-hop link, with no broker and no tracer. The linking half is asserted
 * end to end by {@code ObservabilityIT} against a real hop.
 *
 * <p>This class exists for one reason: a malformed {@code traceparent} must degrade to "no link" and
 * never to a thrown exception. The header is telemetry; the record it rides on is a compliance entry.
 * Losing an audit entry because a tracing header was malformed would invert their importance exactly,
 * and it would do it on the dead-letter path — the one written to prevent a silent hole in the trail.
 */
class AuditSpanLinkTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    private static String traceparent(String flags) {
        return "00-" + TRACE_ID + "-" + SPAN_ID + "-" + flags;
    }

    @Test
    void aWellFormedTraceparentYieldsItsTraceAndSpanIds() {
        Optional<TraceparentRef> parsed = TraceparentRef.parse(traceparent("01"));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().traceId()).isEqualTo(TRACE_ID);
        assertThat(parsed.get().spanId()).isEqualTo(SPAN_ID);
        assertThat(parsed.get().sampled()).isTrue();
    }

    @Test
    void anUnsampledFlagIsCarriedThroughRatherThanForcedTrue() {
        assertThat(TraceparentRef.parse(traceparent("00")))
                .get()
                .extracting(TraceparentRef::sampled)
                .isEqualTo(false);
    }

    @Test
    void aMalformedOrAbsentHeaderYieldsNoLinkAndNeverThrows() {
        assertThat(TraceparentRef.parse(null)).isEmpty();
        assertThat(TraceparentRef.parse("")).isEmpty();
        assertThat(TraceparentRef.parse("garbage")).isEmpty();
        assertThat(TraceparentRef.parse("00-tooshort-" + SPAN_ID + "-01")).isEmpty();
        assertThat(TraceparentRef.parse("00-" + TRACE_ID + "-" + SPAN_ID)).isEmpty();
        assertThat(TraceparentRef.parse("00-" + TRACE_ID.replace('4', 'z') + "-" + SPAN_ID + "-01"))
                .as("non-hex is not an id, and Integer.parseInt must not be reached with it")
                .isEmpty();
    }

    @Test
    void anAllZeroIdIsRefused() {
        assertThat(TraceparentRef.parse("00-" + "0".repeat(32) + "-" + SPAN_ID + "-01"))
                .as("W3C: an all-zero id is the invalid sentinel, and a link to it is worse than none")
                .isEmpty();
        assertThat(TraceparentRef.parse("00-" + TRACE_ID + "-" + "0".repeat(16) + "-01"))
                .isEmpty();
    }

    @Test
    void itBuildsATraceContextThroughTheTracersOwnBuilder() {
        TraceContext context =
                TraceparentRef.parse(traceparent("01")).orElseThrow().toTraceContext(new OtelTraceContextBuilder());

        assertThat(context.traceId()).isEqualTo(TRACE_ID);
        assertThat(context.spanId()).isEqualTo(SPAN_ID);
    }
}
