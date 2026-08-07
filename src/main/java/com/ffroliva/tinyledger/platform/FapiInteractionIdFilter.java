package com.ffroliva.tinyledger.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Open Banking: {@code x-fapi-interaction-id} correlates a call across the ASPSP. Echo the caller's value when
 * it is a well-formed RFC 4122 UUID, so their logs and ours agree; mint one otherwise - whether the header is
 * absent or the supplied value does not conform - so every response is correlatable either way. A
 * non-conforming value is <strong>replaced, not echoed</strong>: this filter runs ahead of the security chain
 * (see below), so an unauthenticated caller could otherwise write arbitrary bytes - including newlines - into
 * both the response header and the log line this class emits via MDC, which is log forging. Validation is by
 * full match against the UUID shape rather than by stripping characters such as {@code \n}: an allowlist
 * cannot be defeated by an encoding the stripper did not anticipate, and FAPI requires a UUID anyway, so the
 * stricter rule is also the correct one. The rejected value is deliberately never logged - doing so would
 * reintroduce the very injection this filter exists to prevent. Also placed in the MDC as
 * {@link #MDC_KEY}, which is the key {@link ErrorHandlingAdvice} and {@link SecurityProblemHandler} read when
 * decorating a problem response.
 *
 * <p><strong>The MDC key is {@code interactionId} and NOT {@code traceId}, since §14 step 9 part 2.</strong>
 * It was {@code traceId} until tracing arrived, and then two different identifiers claimed one MDC key:
 * Micrometer Tracing's {@code Slf4JEventListener} writes the real OTel trace id under {@code traceId} the
 * moment a span goes into scope, which is inside this filter's {@code chain.doFilter}. The interaction id was
 * therefore overwritten before any problem handler could read it, and every 401 and 403 body carried a
 * 32-hex trace id where the client's own UUID belonged. Measured on CI, not reasoned about:
 * {@code SecurityConfigIT#anUnauthenticatedRefusalStillCarriesTheInteractionId} and
 * {@code #theAuditTrailIsRefusedToAnOrdinaryToken} both failed with
 * {@code expected:<c3f1a9e2-…> but was:<569e577ccb5eda177020b2d332aa3f3a>}.
 *
 * <p>Micrometer's keys had to win — §6.6 requires {@code trace_id} and {@code span_id} on every log line, and
 * {@code Slf4JEventListener} hardcodes those names. <strong>The wire contract is unchanged:</strong> the
 * problem body still publishes this value under the JSON property {@code traceId}. That name is now a
 * misnomer and is recorded as one in §6.5 rather than quietly renamed, because it is a published field.
 *
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} is load-bearing, not tidiness. A {@code @Component Filter} registers at
 * {@code Ordered.LOWEST_PRECEDENCE}, while {@code springSecurityFilterChain} registers at
 * {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER = -100} (Boot 4.1 moved that constant off
 * {@code SecurityProperties}, which now holds only {@code getUser()} — verified against the jar). Without it
 * every 401 and every chain-level role-refusal 403 (a {@code hasAuthority} matcher failing in
 * {@code fullChain}) is written by the security chain <em>before</em> this filter runs — no header, and an
 * empty MDC for {@link SecurityProblemHandler} to read — so the claim above would be false for exactly the
 * error responses FAPI requires the header on. Measured: removing the annotation turns
 * {@code SecurityConfigIT#anUnauthenticatedRefusalStillCarriesTheInteractionId} red.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FapiInteractionIdFilter extends OncePerRequestFilter {

    static final String HEADER = "x-fapi-interaction-id";

    /**
     * The MDC key this filter owns. Deliberately not {@code traceId} — see the class javadoc; that key
     * belongs to Micrometer Tracing and collides. Public so the three problem writers read one constant
     * rather than three string literals that can drift apart silently.
     */
    public static final String MDC_KEY = "interactionId";

    private static final Pattern RFC_4122 =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static String sanitise(String supplied) {
        return supplied != null && RFC_4122.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String interactionId = sanitise(request.getHeader(HEADER));
        response.setHeader(HEADER, interactionId);
        MDC.put(MDC_KEY, interactionId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Container threads are pooled: leaving this set would attribute the next request's logs to this one.
            MDC.remove(MDC_KEY);
        }
    }
}
