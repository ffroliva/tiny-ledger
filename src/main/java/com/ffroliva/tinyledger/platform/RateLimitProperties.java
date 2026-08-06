package com.ffroliva.tinyledger.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spec §6.1: "limits are configuration, not constants" — the table's four rows, bound here rather
 * than hard-coded in {@link RateLimitFilter}. {@code capacity} is the steady-state rate per
 * {@code period}; {@code burst} is the extra headroom a bucket may hold above that rate (Bucket4j
 * bucket capacity = {@code capacity + burst}, refilled at {@code capacity} tokens per
 * {@code period}). Declared in {@code platform}, not {@code config}, so {@link RateLimitFilter}
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
