package com.ffroliva.tinyledger.platform;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec §6.1. Constructed by hand and threaded into {@code SecurityConfig}'s two chains via
 * {@code addFilterBefore(this, AuthorizationFilter.class)} — deliberately <strong>not</strong> a
 * {@code @Component}. A {@code @Component Filter} is <em>also</em> auto-registered by Boot as a
 * plain servlet filter outside the security chain (the same mechanism {@link FapiInteractionIdFilter}
 * relies on); adding this one to {@code HttpSecurity} as well would register it twice and
 * double-charge every request against its own bucket.
 *
 * <p>That position — after Spring Security's authentication filters, before
 * {@code AuthorizationFilter} — is load-bearing, not incidental. It runs after
 * {@code BearerTokenAuthenticationFilter} has populated the {@code SecurityContext} for a
 * presented, valid token, so a real caller is limited by identity; and it runs before
 * {@code AuthorizationFilter} decides 401/403, so a token-less request is throttled by the
 * unauthenticated-per-IP bucket instead of reaching the security chain's own (free, for an
 * attacker) refusal on every attempt. An <em>invalid</em> bearer token is a case this position
 * cannot reach: {@code BearerTokenAuthenticationFilter} answers 401 for one itself, inline,
 * without calling the rest of the chain — closing that gap means re-implementing token
 * authentication in this filter, which is out of scope for §6.1's rate limiter.
 *
 * <p>{@link CallerPrincipal#current()} is reused rather than re-derived: it already is the
 * codebase's one definition of "who is the caller", including the standalone contract (always
 * {@code local}, never absent) and the full-mode refusal (an {@link IllegalStateException}) this
 * filter treats as "no authenticated principal" — exactly the unauthenticated row of §6.1's table.
 *
 * <p>Two buckets are always in play, "whichever is more restrictive" (§6.1): the identity bucket —
 * per authenticated principal, split by write/read since they carry different limits; per
 * unauthenticated IP otherwise — and the per-IP backstop, unconditionally. The identity bucket is
 * checked first: if it is exhausted, the backstop is never touched, so one misbehaving principal
 * never spends another principal's or the shared backstop's tokens. If the identity bucket allows
 * the request but the backstop does not, the identity bucket has already spent a token for a
 * request this filter still refuses — a one-sided cost that only ever makes the compound limit
 * *more* restrictive than nominal, never less, which is the safe direction for a security control.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterStore store;
    private final RateLimitProperties properties;
    private final CallerPrincipal callerPrincipal;
    private final ObjectMapper mapper;

    public RateLimitFilter(
            RateLimiterStore store,
            RateLimitProperties properties,
            CallerPrincipal callerPrincipal,
            ObjectMapper mapper) {
        this.store = store;
        this.properties = properties;
        this.callerPrincipal = callerPrincipal;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // §6.1: never a raw X-Forwarded-For. This is deliberately the only IP source read here — an
        // operator fronting the app with a trusted proxy sets server.forward-headers-strategy, which
        // rewrites what getRemoteAddr() itself returns before this filter ever runs (Tomcat's
        // RemoteIpValve under `native`, Spring's ForwardedHeaderFilter under `framework`); parsing
        // X-Forwarded-For by hand here would let any client supply it and spoof past the per-IP
        // buckets on a deployment that never configured a trusted proxy.
        String ip = request.getRemoteAddr();
        boolean write = isWrite(request.getMethod());

        String identityKey;
        RateLimitProperties.Limit identityLimit;
        String principal = currentPrincipalOrNull();
        if (principal != null) {
            identityKey = "principal:" + principal + ':' + (write ? "write" : "read");
            identityLimit = write ? properties.writePerPrincipal() : properties.readPerPrincipal();
        } else {
            identityKey = "unauth-ip:" + ip;
            identityLimit = properties.unauthenticatedPerIp();
        }

        ConsumptionProbe identityProbe = probe(identityKey, identityLimit);
        if (!identityProbe.isConsumed()) {
            reject(response, identityProbe);
            return;
        }

        ConsumptionProbe backstopProbe = probe("ip-backstop:" + ip, properties.ipBackstop());
        if (!backstopProbe.isConsumed()) {
            reject(response, backstopProbe);
            return;
        }

        chain.doFilter(request, response);
    }

    private String currentPrincipalOrNull() {
        try {
            return callerPrincipal.current();
        } catch (IllegalStateException noAuthenticatedPrincipal) {
            return null;
        }
    }

    private static boolean isWrite(String httpMethod) {
        return !("GET".equals(httpMethod) || "HEAD".equals(httpMethod));
    }

    private ConsumptionProbe probe(String key, RateLimitProperties.Limit limit) {
        Supplier<BucketConfiguration> configuration = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity() + limit.burst())
                        .refillGreedy(limit.capacity(), limit.period())
                        .build())
                .build();
        Bucket bucket = store.resolveBucket(key, configuration);
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    /** §6.1/§6.5: the catalogued 429, with the header the spec requires alongside it. */
    private void reject(HttpServletResponse response, ConsumptionProbe probe) throws IOException {
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        ErrorCode code = ErrorCode.RATE_LIMIT_EXCEEDED;
        ProblemDetail body = ProblemDetail.forStatus(code.status());
        body.setType(URI.create(code.type()));
        body.setTitle(code.title());
        // §6.5/§6.6: the same correlating id ErrorHandlingAdvice and SecurityProblemHandler attach.
        String traceId = MDC.get("traceId");
        if (traceId != null) body.setProperty("traceId", traceId);
        response.setStatus(code.status());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
