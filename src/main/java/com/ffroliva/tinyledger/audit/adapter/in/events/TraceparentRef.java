package com.ffroliva.tinyledger.audit.adapter.in.events;

import io.micrometer.tracing.TraceContext;
import java.util.Optional;

/**
 * The W3C {@code traceparent} header, split into the two ids a span LINK needs.
 *
 * <p>Parsed here rather than through {@code Propagator.extract}, and that is the whole point:
 * extraction hands back a span builder with a remote <em>parent</em>, and a parent is precisely what
 * §6.6 refuses for fan-out. The format is fixed and four fields wide —
 * {@code 00-<32 hex traceId>-<16 hex spanId>-<2 hex flags>}.
 *
 * <p><strong>Every failure is an empty result, never an exception.</strong> This header is telemetry;
 * the record it rides on is a compliance entry. Losing an audit entry because a tracing header was
 * malformed would invert their importance exactly — and it would do it by throwing on the path that
 * dead-letters, which is the mechanism written to prevent a silent hole in the trail.
 */
record TraceparentRef(String traceId, String spanId, boolean sampled) {

    private static final int TRACE_ID_LENGTH = 32;
    private static final int SPAN_ID_LENGTH = 16;
    private static final int FIELDS = 4;
    private static final int SAMPLED_FLAG = 0x01;

    static Optional<TraceparentRef> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String[] parts = header.split("-");
        if (parts.length != FIELDS) {
            return Optional.empty();
        }
        String traceId = parts[1];
        String spanId = parts[2];
        String flags = parts[3];
        if (!isHexOfLength(traceId, TRACE_ID_LENGTH) || !isHexOfLength(spanId, SPAN_ID_LENGTH)) {
            return Optional.empty();
        }
        // The W3C invalid sentinel. A link to an all-zero context is not a weaker link, it is a
        // link to nothing — and it would read on a trace view as though correlation had worked.
        if (isAllZeroes(traceId) || isAllZeroes(spanId)) {
            return Optional.empty();
        }
        if (!isHexOfLength(flags, 2)) {
            return Optional.empty();
        }
        return Optional.of(new TraceparentRef(traceId, spanId, (Integer.parseInt(flags, 16) & SAMPLED_FLAG) != 0));
    }

    TraceContext toTraceContext(TraceContext.Builder builder) {
        return builder.traceId(traceId).spanId(spanId).sampled(sampled).build();
    }

    private static boolean isHexOfLength(String value, int length) {
        return value.length() == length && value.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    private static boolean isAllZeroes(String value) {
        return value.chars().allMatch(c -> c == '0');
    }
}
