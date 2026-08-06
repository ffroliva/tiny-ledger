package com.ffroliva.tinyledger.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Open Banking: {@code x-fapi-interaction-id} correlates a call across the ASPSP. Echo the caller's value when
 * they supply one so their logs and ours agree; mint one when they do not, so every response is correlatable
 * either way. Also placed in the MDC as {@code traceId}, which is the key {@link ErrorHandlingAdvice} and
 * {@link SecurityProblemHandler} already read when decorating a problem response.
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String interactionId =
                supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
        response.setHeader(HEADER, interactionId);
        MDC.put("traceId", interactionId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Container threads are pooled: leaving this set would attribute the next request's logs to this one.
            MDC.remove("traceId");
        }
    }
}
