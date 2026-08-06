package com.ffroliva.tinyledger.platform;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec §6.1 row 4, split out of {@link RateLimitFilter} (review finding C1): "any traffic, per IP"
 * means <em>any</em>, including a request whose bearer token is invalid, expired, wrong-audience or
 * simply garbage. {@code BearerTokenAuthenticationFilter} answers 401 for those itself, inline,
 * without calling the rest of the chain — so a filter placed where {@link RateLimitFilter} sits
 * (after authentication, before authorisation) never sees them, and a flood of junk
 * {@code Authorization} headers opts out of every §6.1 scope for free. Worse than free: an
 * unrecognised {@code kid} can drive a real JWKS refetch against the issuer per attempt.
 *
 * <p>The fix is position, not logic: this filter needs only {@code getRemoteAddr()}, never a
 * principal, so it is threaded in with {@code addFilterBefore(this, BearerTokenAuthenticationFilter
 * .class)} — ahead of authentication entirely, the same reasoning that puts
 * {@code FapiInteractionIdFilter} at {@code HIGHEST_PRECEDENCE} ahead of the whole security chain:
 * a response the chain answers itself is invisible to anything placed inside it. It is
 * <strong>not</strong> placed there too (outside the chain, {@code @Component}-style) for the same
 * double-registration reason {@link RateLimitFilter}'s javadoc gives.
 *
 * <p>An earlier version of this fix tried {@code addFilterBefore(RateLimitFilter,
 * BearerTokenAuthenticationFilter.class)} — moving the <em>whole</em> filter, identity bucket
 * included. Wrong: that runs before {@code SecurityContext} population, so
 * {@code CallerPrincipal.current()} throws for every request, including a validly authenticated
 * one, and every real caller falls into the 20/minute unauthenticated bucket instead of their own
 * 100 or 1000/minute one — catastrophically more restrictive than intended. Only the IP-keyed,
 * identity-free backstop can move; the identity buckets stay exactly where they were.
 */
public class IpBackstopFilter extends OncePerRequestFilter {

    private final RateLimiterStore store;
    private final RateLimitProperties properties;
    private final ObjectMapper mapper;

    public IpBackstopFilter(RateLimiterStore store, RateLimitProperties properties, ObjectMapper mapper) {
        this.store = store;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // §6.1: getRemoteAddr(), never a raw X-Forwarded-For — an operator fronting the app with a
        // trusted proxy sets server.forward-headers-strategy, which rewrites what this call returns
        // before this filter ever runs; this filter never parses the header itself.
        String key = "ip-backstop:" + request.getRemoteAddr();
        ConsumptionProbe probe = RateLimitFilter.probe(store, key, properties.ipBackstop());
        if (!probe.isConsumed()) {
            RateLimitFilter.reject(response, mapper, probe);
            return;
        }
        chain.doFilter(request, response);
    }
}
