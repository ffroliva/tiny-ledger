package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Spec §6.1/§9.6: proves the 429 by exhausting a real bucket rather than asserting the filter's
 * internals (that is {@link com.ffroliva.tinyledger.platform.RateLimitFilterTest}'s and
 * {@link com.ffroliva.tinyledger.platform.IpBackstopFilterTest}'s job).
 *
 * <p>{@code bob} is the write-per-principal identity: {@link AbstractIntegrationTest} lowers that
 * limit for the whole shared context (a per-class {@code @TestPropertySource} here would fork it,
 * ADR 0003), which is only safe because {@code bob} is the one Keycloak fixture user no other
 * {@code *IT} test authenticates as for a write call — grep-verified against every {@code post(}/
 * {@code put(} call site in {@code src/test} before picking the number. See the comment on that
 * override for the margin kept above what {@code alice}/{@code carol} legitimately use.
 */
class RateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exceedingTheWritePerPrincipalLimitAnswers429WithRetryAfterAndTheCataloguedType() throws Exception {
        // Review finding I4: minted once. bearer("bob") is a fresh Keycloak password-grant round
        // trip per call — inside the loop, eleven of those plus eleven Postgres/Kafka writes can
        // outlast the lowered bucket's 6-second-per-token refill on a loaded runner, silently
        // refilling a token and turning the 11th request green instead of 429.
        String token = bearer("bob");
        for (int i = 0; i < AbstractIntegrationTest.LOWERED_WRITE_LIMIT; i++) {
            mockMvc.perform(post("/api/v1/accounts")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"ACC-bob-" + i + "\",\"currency\":\"GBP\"}"))
                    .andExpect(status().isCreated());
        }

        MvcResult overTheLimit = mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-bob-over\",\"currency\":\"GBP\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(overTheLimit.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(overTheLimit.getResponse().getContentAsString()).contains("/errors/rate-limit-exceeded");
    }

    /**
     * Review finding C1. {@code BearerTokenAuthenticationFilter} answers 401 for a malformed token
     * itself, inline, without calling the rest of the chain — so before the fix, none of these
     * requests ever reached a rate limiter at all, and this loop would run to
     * {@code RAISED_IP_BACKSTOP_LIMIT + 1} without ever seeing a 429. {@code IpBackstopFilter} sits
     * ahead of that filter specifically to close this gap.
     *
     * <p>A unique IP (never used by any other {@code *IT} test's default-{@code 127.0.0.1} traffic)
     * isolates this flood from every other test sharing the same context, so it can drive its own
     * bucket to exhaustion regardless of what {@link AbstractIntegrationTest#RAISED_IP_BACKSTOP_LIMIT}
     * is raised to for everyone else. The token itself is deliberately not a well-formed JWT with an
     * unrecognised {@code kid} — that shape can drive a real JWKS refetch per attempt (the review's
     * own warning about the cost of leaving this gap open); a string that fails to parse as a JWT at
     * all is rejected locally, so a few hundred iterations stay cheap.
     */
    @Test
    void aFloodOfGarbageBearerTokensEventuallyTripsTheIpBackstop() throws Exception {
        MvcResult result = null;
        for (int i = 0; i <= AbstractIntegrationTest.RAISED_IP_BACKSTOP_LIMIT; i++) {
            result = mockMvc.perform(get("/api/v1/accounts")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                            .with(request -> {
                                request.setRemoteAddr("203.0.113.222");
                                return request;
                            }))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) break;
        }

        assertThat(result).isNotNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(result.getResponse().getContentAsString()).contains("/errors/rate-limit-exceeded");
    }
}
