package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
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
 * internals (that is {@link com.ffroliva.tinyledger.platform.RateLimitFilterTest}'s job).
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
        for (int i = 0; i < AbstractIntegrationTest.LOWERED_WRITE_LIMIT; i++) {
            mockMvc.perform(post("/api/v1/accounts")
                            .header(HttpHeaders.AUTHORIZATION, bearer("bob"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"ACC-bob-" + i + "\",\"currency\":\"GBP\"}"))
                    .andExpect(status().isCreated());
        }

        MvcResult overTheLimit = mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer("bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-bob-over\",\"currency\":\"GBP\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(overTheLimit.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(overTheLimit.getResponse().getContentAsString()).contains("/errors/rate-limit-exceeded");
    }
}
