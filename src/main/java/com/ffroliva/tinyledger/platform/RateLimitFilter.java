package com.ffroliva.tinyledger.platform;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.lettuce.core.RedisException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec §6.1's <em>identity</em> buckets — per authenticated principal (split write/read) or per
 * unauthenticated IP. The fourth row, the unconditional per-IP backstop, is deliberately not here
 * any more: see {@link IpBackstopFilter}'s javadoc for why that scope needed a different position
 * in the chain than this one does, and why folding it back into this filter would reopen review
 * finding C1 (a bypass for every request carrying an invalid bearer token).
 *
 * <p>Constructed by hand and threaded into {@code SecurityConfig}'s two chains via
 * {@code addFilterBefore(this, AuthorizationFilter.class)} — deliberately <strong>not</strong> a
 * {@code @Component}. A {@code @Component Filter} is <em>also</em> auto-registered by Boot as a
 * plain servlet filter outside the security chain (the same mechanism {@link FapiInteractionIdFilter}
 * relies on); adding this one to {@code HttpSecurity} as well would register it twice and
 * double-charge every request against its own bucket.
 *
 * <p>That position — after Spring Security's authentication filters, before
 * {@code AuthorizationFilter} — is load-bearing, not incidental. It runs after
 * {@code BearerTokenAuthenticationFilter} has populated the {@code SecurityContext} for a
 * presented, <em>valid</em> token, so a real caller is limited by identity; and it runs before
 * {@code AuthorizationFilter} decides 401/403, so a token-less request is throttled by the
 * unauthenticated-per-IP bucket instead of reaching the security chain's own (free, for an
 * attacker) refusal on every attempt. An invalid bearer token never reaches this position —
 * {@code BearerTokenAuthenticationFilter} answers 401 for one itself, inline, without calling the
 * rest of the chain — which is exactly the gap {@link IpBackstopFilter} closes from further up.
 *
 * <p>{@link CallerPrincipal#current()} is reused rather than re-derived: it already is the
 * codebase's one definition of "who is the caller", including the standalone contract (always
 * {@code local}, never absent) and the full-mode refusal (an {@link IllegalStateException}) this
 * filter treats as "no authenticated principal" — exactly the unauthenticated row of §6.1's table.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

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
        if (isExempt(properties, request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }

        boolean write = isWrite(request.getMethod());

        String identityKey;
        RateLimitProperties.Limit identityLimit;
        String principal = currentPrincipalOrNull();
        if (principal != null) {
            identityKey = "principal:" + principal + ':' + (write ? "write" : "read");
            identityLimit = write ? properties.writePerPrincipal() : properties.readPerPrincipal();
        } else {
            // §6.1 row 3: never a raw X-Forwarded-For — see IpBackstopFilter's javadoc, which reads
            // the same getRemoteAddr() for the same reason.
            identityKey = "unauth-ip:" + request.getRemoteAddr();
            identityLimit = properties.unauthenticatedPerIp();
        }

        ConsumptionProbe identityProbe = probe(store, identityKey, identityLimit);
        if (!identityProbe.isConsumed()) {
            reject(response, mapper, identityProbe);
            return;
        }

        chain.doFilter(request, response);
    }

    private String currentPrincipalOrNull() {
        try {
            return callerPrincipal.current();
        } catch (IllegalStateException _) {
            return null;
        }
    }

    private static boolean isWrite(String httpMethod) {
        return !("GET".equals(httpMethod) || "HEAD".equals(httpMethod));
    }

    /**
     * Shared by {@link IpBackstopFilter}: an exempt IP skips both buckets — identity and backstop —
     * entirely, so the check has to happen, and mean the same thing, in both filters. See
     * {@link RateLimitProperties}'s javadoc for why this is empty by default, why it is matched
     * against {@code getRemoteAddr()} and nothing else, and why it must never default to loopback.
     * DEBUG rather than WARN: an exemption *firing* is expected, routine traffic once configured —
     * {@code RateLimitConfig} logs WARN once, loudly, at startup for the configuration itself.
     */
    static boolean isExempt(RateLimitProperties properties, String ip) {
        boolean exempt = properties.exemptIps().contains(ip);
        if (exempt) {
            log.debug("rate limit exemption matched for {}", ip);
        }
        return exempt;
    }

    /**
     * Shared by {@link IpBackstopFilter}: same bucket math, same storage failure handling, one
     * place to keep both honest. Package-visible rather than a third class, since the two filters
     * that call it are both in {@code platform} and there is no third caller (§6.1 scope discipline).
     *
     * <p>Review I3 / §9.3 N9: "{@code alice} exceeds 100 writes in a minute → 429" is the
     * <strong>101st</strong> write, not the 121st, so bucket capacity is {@code limit.capacity()}
     * alone — {@code burst} is not added on top. {@code RateLimitProperties} still carries
     * {@code burst} (§6.1's table states it, and {@code application.properties} still declares the
     * literal 20), but under this reading it has no effect on the bucket's capacity or refill.
     * Whether "burst" keeps a distinct meaning is a spec-text question for Task 5, not a code one.
     *
     * <p>Review I2: a Lettuce {@link RedisException} from the {@code full}-profile store is not a
     * reason to fail the request. A rate limiter is an abuse control, not a source of truth —
     * {@code RedisBalanceCache} already degrades the same way for the same reason (see its javadoc)
     * — so a storage hiccup fails <strong>open</strong>, loudly logged, rather than surfacing as an
     * uncaught exception that {@code ErrorHandlingAdvice} (a {@code @ControllerAdvice}, blind to
     * filter exceptions) cannot translate and that would otherwise fall through to Boot's default
     * error page — the exact request-path leak Task 2 closed.
     *
     * <p><strong>Corrected failure mode</strong> (the first version of this fix understated it): the
     * {@code RedisClient} {@code RateLimitConfig} builds sets a 250ms command timeout, so a Redis
     * outage costs <em>one bounded 250ms stall per request</em>, then fails open — not the ~60s
     * stall Lettuce's default timeout would otherwise cause per request (Tomcat's worker pool
     * saturates in seconds at that rate, which is a total outage, strictly worse than the 500 this
     * fail-open replaces). See {@code RateLimitConfig#rateLimitRedisClient}'s javadoc for why the
     * timeout is bounded on the Lettuce side rather than Bucket4j's own {@code ClientSideConfig}.
     */
    static ConsumptionProbe probe(RateLimiterStore store, String key, RateLimitProperties.Limit limit) {
        Supplier<BucketConfiguration> configuration = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period())
                        .build())
                .build();
        try {
            Bucket bucket = store.resolveBucket(key, configuration);
            return bucket.tryConsumeAndReturnRemaining(1);
        } catch (RedisException storageUnavailable) {
            log.warn(
                    "rate limiter storage unavailable for key '{}', allowing the request unmetered",
                    key,
                    storageUnavailable);
            return ConsumptionProbe.consumed(Long.MAX_VALUE, 0);
        }
    }

    /** §6.1/§6.5: the catalogued 429, with the header the spec requires alongside it. */
    static void reject(HttpServletResponse response, ObjectMapper mapper, ConsumptionProbe probe) throws IOException {
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
