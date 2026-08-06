package com.ffroliva.tinyledger.platform;

import java.time.Duration;
import java.util.List;
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
 *
 * <p>{@code exemptIps} is a later, additive change to §6.1: source addresses excused from both
 * buckets entirely, so an operator can unblock local development or a load-testing rig without a
 * redeploy. <strong>Empty by default</strong> — declared nowhere in {@code application.properties}
 * or {@code application-full.properties}, only in {@code application-standalone.properties}
 * ({@code 127.0.0.1}), so `full`'s production posture stays strict and unexempted by default.
 * Matched against exactly the same {@code getRemoteAddr()} source the buckets themselves read
 * (never a header) — an allowlist keyed on a spoofable value would be a total bypass of the whole
 * control for anyone able to set that header. Exact string match only, no CIDR: nobody has asked
 * for a range, and Java ships no subnet parser to build one against without adding a dependency
 * for a feature nobody needs yet — a maintainer who does can extend {@link RateLimitFilter#isExempt}
 * without touching this record's shape.
 *
 * <p><strong>Never default this list to {@code 127.0.0.1} for every profile.</strong> A reverse
 * proxy fronting this app on the same host makes {@code getRemoteAddr()} loopback for
 * <em>every</em> caller, proxied or not — a loopback exemption in that deployment would silently
 * disable rate limiting entirely, which is the exact failure this control exists to prevent. The
 * {@code standalone}-only entry above is safe only because {@code standalone} is documented as
 * running unfronted (§1); it must never migrate into the shared base file.
 */
@ConfigurationProperties(prefix = "ledger.rate-limit")
public record RateLimitProperties(
        Limit writePerPrincipal,
        Limit readPerPrincipal,
        Limit unauthenticatedPerIp,
        Limit ipBackstop,
        List<String> exemptIps) {

    // Measured on CI, not assumed: an auxiliary 4-arg constructor here (a "no exemptions" convenience
    // overload) broke @ConfigurationProperties binding outright — Boot's binder saw two constructors,
    // could not tell which one is canonical, and fell back to looking for a no-arg default
    // constructor, which a record never has ("No default constructor found"). A record bound by
    // @ConfigurationProperties must have exactly one constructor; every call site below passes
    // exemptIps explicitly instead.
    public RateLimitProperties {
        exemptIps = exemptIps == null ? List.of() : List.copyOf(exemptIps);
    }

    public record Limit(int capacity, int burst, Duration period) {}
}
