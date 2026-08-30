package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec §6.1: the bucket decision — which bucket, which limit, is it exhausted — proved with no
 * container. {@link LocalRateLimiterStore} is the real {@code standalone} storage, not a fake:
 * Caffeine is pure JVM memory, so using it here already satisfies "zero containers" without a
 * second, test-only implementation to keep honest.
 *
 * <p>The per-IP backstop is {@link IpBackstopFilter}'s own test (review finding C1) — this class
 * covers the identity buckets {@link RateLimitFilter} still owns, plus the two things
 * {@link RateLimitFilter#probe} does that are shared with that filter (burst arithmetic, storage
 * failure handling — I2/I3), proved once here rather than duplicated there.
 */
class RateLimitFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static RateLimitProperties.Limit limit(int capacity, int burst) {
        return new RateLimitProperties.Limit(capacity, burst, Duration.ofMinutes(1));
    }

    private static RateLimitProperties properties(
            RateLimitProperties.Limit write,
            RateLimitProperties.Limit read,
            RateLimitProperties.Limit unauthenticated,
            RateLimitProperties.Limit backstop) {
        return new RateLimitProperties(write, read, unauthenticated, backstop, List.of());
    }

    private static final RateLimitProperties.Limit GENEROUS = limit(1_000, 0);

    /** {@code !standalone}, non-empty active profiles — CallerPrincipal.current() throws with no JWT set. */
    private static CallerPrincipal fullPrincipal() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("full");
        return new CallerPrincipal(environment);
    }

    private static void authenticateAs(String subject) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static MockHttpServletRequest request(String method, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/accounts");
        request.setRemoteAddr(ip);
        return request;
    }

    private static final class CountingChain implements FilterChain {
        int count;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
            count++;
        }
    }

    @Test
    void aRequestUnderTheLimitPassesThroughUnrejected() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new LocalRateLimiterStore(),
                properties(GENEROUS, GENEROUS, GENEROUS, GENEROUS),
                fullPrincipal(),
                MAPPER);
        authenticateAs("alice");
        CountingChain chain = new CountingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "203.0.113.1"), response, chain);

        assertThat(chain.count).isEqualTo(1);
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test // §6.1/§6.5: the catalogued 429, with Retry-After, once the identity bucket is exhausted
    void exhaustingTheWritePerPrincipalBucketAnswers429WithRetryAfterAndTheCataloguedType() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new LocalRateLimiterStore(),
                properties(limit(1, 0), GENEROUS, GENEROUS, GENEROUS),
                fullPrincipal(),
                MAPPER);
        authenticateAs("alice");
        CountingChain chain = new CountingChain();

        filter.doFilter(request("POST", "203.0.113.2"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("POST", "203.0.113.2"), second, chain);

        assertThat(chain.count).isEqualTo(1); // the second call never reached the chain
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isNotNull();
        assertThat(second.getContentAsString()).contains("/errors/rate-limit-exceeded");
    }

    @Test // I3 / §9.3 N9: "alice exceeds 100 writes in a minute" is the 101st, so burst must not extend capacity
    void burstDoesNotExtendBucketCapacity() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new LocalRateLimiterStore(),
                properties(limit(1, 20), GENEROUS, GENEROUS, GENEROUS), // capacity 1, burst 20
                fullPrincipal(),
                MAPPER);
        authenticateAs("alice");
        CountingChain chain = new CountingChain();

        filter.doFilter(request("POST", "203.0.113.6"), new MockHttpServletResponse(), chain); // spends the 1
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("POST", "203.0.113.6"), second, chain); // the burst does not buy a 2nd token

        assertThat(chain.count).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(429);
    }

    @Test // I2: a rate limiter is an abuse control, not a source of truth — a storage blip must not 500 the API
    void aStorageFailureFailsOpenInsteadOfPropagating() throws Exception {
        RateLimiterStore brokenStore = (key, configuration) -> {
            throw new RedisException("connection reset (simulated)");
        };
        RateLimitFilter filter = new RateLimitFilter(
                brokenStore, properties(limit(1, 0), GENEROUS, GENEROUS, GENEROUS), fullPrincipal(), MAPPER);
        authenticateAs("alice");
        CountingChain chain = new CountingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "203.0.113.7"), response, chain);

        assertThat(chain.count).isEqualTo(1); // allowed through despite the broken store
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    /**
     * The other half of I2, and the one that was missing: a broken store must not become an <em>absent</em>
     * limiter. The test above proves the request still flows; this one proves it is still counted.
     *
     * <p>Capacity is 1, so the first write consumes the fallback bucket and the second must be refused.
     * Red proof, run against the previous {@code ConsumptionProbe.consumed(Long.MAX_VALUE, 0)}:
     * {@code expected: 1 but was: 2} — the chain was reached twice, because under that version the store
     * could fail and every subsequent request passed unmetered. A §9.7 load run showed that branch being
     * taken 1,388 times in three minutes.
     *
     * <p>A distinct principal from the test above, deliberately: {@link RateLimitFilter} holds one static
     * fallback store for the whole process, so a shared subject would let that test's consumption decide
     * this one's outcome.
     */
    @Test // I2, completed: degraded to per-instance, not degraded to nothing
    void aBrokenStoreStillCountsAgainstAPerInstanceBucket() throws Exception {
        RateLimiterStore brokenStore = (key, configuration) -> {
            throw new RedisException("connection reset (simulated)");
        };
        RateLimitFilter filter = new RateLimitFilter(
                brokenStore, properties(limit(1, 0), GENEROUS, GENEROUS, GENEROUS), fullPrincipal(), MAPPER);
        authenticateAs("bob");
        CountingChain chain = new CountingChain();

        filter.doFilter(request("POST", "203.0.113.9"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("POST", "203.0.113.9"), second, chain);

        assertThat(chain.count).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(429);
    }

    @Test // §6.1 row 3: no principal resolves (CallerPrincipal throws outside standalone) — per-IP, not per-principal
    void withNoAuthenticatedPrincipalTheUnauthenticatedPerIpBucketApplies() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new LocalRateLimiterStore(),
                properties(GENEROUS, GENEROUS, limit(1, 0), GENEROUS),
                fullPrincipal(),
                MAPPER);
        CountingChain chain = new CountingChain();

        filter.doFilter(request("GET", "203.0.113.3"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("GET", "203.0.113.3"), second, chain);

        assertThat(chain.count).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(429);
    }

    @Test // product-owner addition: an exempt IP skips the identity bucket entirely, no matter how tiny it is
    void anExemptIpIsNeverChargedTheIdentityBucket() throws Exception {
        RateLimitProperties properties =
                new RateLimitProperties(limit(1, 0), GENEROUS, GENEROUS, GENEROUS, List.of("203.0.113.9"));
        RateLimitFilter filter = new RateLimitFilter(new LocalRateLimiterStore(), properties, fullPrincipal(), MAPPER);
        authenticateAs("alice");
        CountingChain chain = new CountingChain();

        for (int i = 0; i < 5; i++) { // well past the capacity-1 bucket, which never gets charged
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "203.0.113.9"), response, chain);
            assertThat(response.getStatus()).isNotEqualTo(429);
        }
        assertThat(chain.count).isEqualTo(5);
    }

    @Test // the differential: the same tiny limit still bites a non-exempt IP — without this, the test above proves
    // nothing
    void aNonExemptIpWithTheSameLimitIsStillThrottled() throws Exception {
        RateLimitProperties properties =
                new RateLimitProperties(limit(1, 0), GENEROUS, GENEROUS, GENEROUS, List.of("203.0.113.9"));
        RateLimitFilter filter = new RateLimitFilter(new LocalRateLimiterStore(), properties, fullPrincipal(), MAPPER);
        authenticateAs("alice");
        CountingChain chain = new CountingChain();

        filter.doFilter(request("POST", "203.0.113.10"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("POST", "203.0.113.10"), second, chain);

        assertThat(second.getStatus()).isEqualTo(429);
    }

    @Test // the production default (application.properties/application-full.properties declare nothing) exempts nobody
    void anEmptyExemptionListExemptsNobody() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new LocalRateLimiterStore(),
                properties(
                        limit(1, 0),
                        GENEROUS,
                        GENEROUS,
                        GENEROUS), // the 4-arg constructor: exemptIps defaults to List.of()
                fullPrincipal(),
                MAPPER);
        authenticateAs("alice");
        CountingChain chain = new CountingChain();

        filter.doFilter(request("POST", "203.0.113.11"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("POST", "203.0.113.11"), second, chain);

        assertThat(second.getStatus()).isEqualTo(429);
    }

    @Test // write and read are different rows in §6.1's table, and different buckets here
    void writeAndReadTrafficForTheSamePrincipalUseIndependentBuckets() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new LocalRateLimiterStore(),
                properties(limit(1, 0), GENEROUS, GENEROUS, GENEROUS),
                fullPrincipal(),
                MAPPER);
        authenticateAs("alice");
        CountingChain chain = new CountingChain();

        filter.doFilter(
                request("POST", "203.0.113.4"), new MockHttpServletResponse(), chain); // spends the write bucket
        MockHttpServletResponse read = new MockHttpServletResponse();
        filter.doFilter(request("GET", "203.0.113.4"), read, chain); // a different bucket, still full

        assertThat(chain.count).isEqualTo(2);
        assertThat(read.getStatus()).isNotEqualTo(429);
    }
}
