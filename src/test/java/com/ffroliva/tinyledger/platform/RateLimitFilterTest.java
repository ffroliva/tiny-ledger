package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.time.Duration;
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
        return new RateLimitProperties(write, read, unauthenticated, backstop);
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

    @Test // §6.1: "per principal AND per IP, whichever is more restrictive" — the backstop is shared by IP
    void theIpBackstopAppliesAcrossDifferentPrincipalsSharingAnIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                new LocalRateLimiterStore(),
                properties(GENEROUS, GENEROUS, GENEROUS, limit(1, 0)),
                fullPrincipal(),
                MAPPER);
        CountingChain chain = new CountingChain();

        authenticateAs("alice");
        filter.doFilter(request("POST", "203.0.113.5"), new MockHttpServletResponse(), chain);

        authenticateAs("bob"); // a different principal, own identity bucket still full
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("POST", "203.0.113.5"), second, chain);

        assertThat(chain.count).isEqualTo(1); // the shared per-IP backstop, not bob's own bucket, refused it
        assertThat(second.getStatus()).isEqualTo(429);
    }
}
