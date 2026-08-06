package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec §6.1 row 4 (review finding C1). No identity is involved here at all — that is the point:
 * this filter never decodes the {@code Authorization} header, so a request with no token, a
 * garbage token, and a valid one are indistinguishable to it, all charged the same per-IP bucket.
 * {@code RateLimitIT#aFloodOfGarbageBearerTokensEventuallyTripsTheIpBackstop} is the proof that
 * matters — that this filter actually sits ahead of {@code BearerTokenAuthenticationFilter} in the
 * real chain, which no unit test can observe. This class covers the bucket decision itself.
 */
class IpBackstopFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RateLimitProperties.Limit limit(int capacity) {
        return new RateLimitProperties.Limit(capacity, 0, Duration.ofMinutes(1));
    }

    private static RateLimitProperties properties(RateLimitProperties.Limit backstop) {
        RateLimitProperties.Limit generous = limit(1_000);
        return new RateLimitProperties(generous, generous, generous, backstop);
    }

    private static MockHttpServletRequest request(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        request.setRemoteAddr(ip);
        request.addHeader("Authorization", "Bearer not-a-jwt");
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
        IpBackstopFilter filter = new IpBackstopFilter(new LocalRateLimiterStore(), properties(limit(1_000)), MAPPER);
        CountingChain chain = new CountingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("198.51.100.1"), response, chain);

        assertThat(chain.count).isEqualTo(1);
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test // §6.1/§6.5: the catalogued 429, with Retry-After, once the per-IP backstop is exhausted
    void exhaustingTheBackstopAnswers429WithRetryAfterAndTheCataloguedType() throws Exception {
        IpBackstopFilter filter = new IpBackstopFilter(new LocalRateLimiterStore(), properties(limit(1)), MAPPER);
        CountingChain chain = new CountingChain();

        filter.doFilter(request("198.51.100.2"), new MockHttpServletResponse(), chain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request("198.51.100.2"), second, chain);

        assertThat(chain.count).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isNotNull();
        assertThat(second.getContentAsString()).contains("/errors/rate-limit-exceeded");
    }

    @Test // the whole point of C1: no auth header is ever inspected, so different IPs never collide
    void differentIpsGetIndependentBuckets() throws Exception {
        IpBackstopFilter filter = new IpBackstopFilter(new LocalRateLimiterStore(), properties(limit(1)), MAPPER);
        CountingChain chain = new CountingChain();

        filter.doFilter(request("198.51.100.3"), new MockHttpServletResponse(), chain); // spends 198.51.100.3's token
        MockHttpServletResponse fromAnotherIp = new MockHttpServletResponse();
        filter.doFilter(request("198.51.100.4"), fromAnotherIp, chain);

        assertThat(chain.count).isEqualTo(2);
        assertThat(fromAnotherIp.getStatus()).isNotEqualTo(429);
    }
}
