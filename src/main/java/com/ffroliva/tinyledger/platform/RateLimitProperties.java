package com.ffroliva.tinyledger.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spec §6.1: "limits are configuration, not constants" — the table's four rows, bound here rather
 * than hard-coded in {@link RateLimitFilter}. {@code capacity} is the steady-state rate per
 * {@code period}, refilled at {@code capacity} tokens per {@code period}. {@code burst} is still
 * bound here — the table states it, and {@code application.properties} still carries the literal
 * 20 — but review finding I3 established that §9.3 N9 ("alice exceeds 100 writes in a minute →
 * 429" is the 101st write) reads §6.1's "100/minute, burst 20" more strictly than "capacity + burst"
 * would: {@link RateLimitFilter#probe} does not add it to bucket capacity. See that method's
 * javadoc; whether "burst" keeps a distinct meaning is left for the spec text itself (Task 5), not
 * decided here. Declared in {@code platform}, not {@code config}, so {@link RateLimitFilter}
 * can depend on it directly without {@code platform} reaching into {@code config} — the same
 * one-way boundary {@link ErrorHandlingAdvice}'s javadoc documents for
 * {@link SecurityProblemHandler}.
 *
 * <p>Defaults live in {@code application.properties} as the table's own numbers, true in both run
 * modes — only the storage backing these buckets differs by profile (§6.1, {@code RateLimitConfig}).
 */
@ConfigurationProperties(prefix = "ledger.rate-limit")
public record RateLimitProperties(
        Limit writePerPrincipal, Limit readPerPrincipal, Limit unauthenticatedPerIp, Limit ipBackstop) {

    public record Limit(int capacity, int burst, Duration period) {}
}
