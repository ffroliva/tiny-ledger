package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Logic only: these call {@code doFilter} directly, so they cannot see whether the filter is registered or
 * ordered. {@code BalanceControllerTest} proves registration and the header/{@code traceId} coupling;
 * {@code SecurityConfigIT} proves the ordering against a real security chain.
 */
class FapiInteractionIdFilterTest {

    private final FapiInteractionIdFilter filter = new FapiInteractionIdFilter();

    @Test // OB: a caller-supplied correlation id is echoed unchanged
    void aSuppliedInteractionIdIsEchoed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-fapi-interaction-id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("x-fapi-interaction-id")).isEqualTo("abc-123");
    }

    @Test // and one is minted when the caller supplies none, so every response is correlatable
    void anAbsentInteractionIdIsMinted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});

        String minted = response.getHeader("x-fapi-interaction-id");
        assertThat(minted).isNotBlank();
        assertThat(UUID.fromString(minted)).isNotNull();
    }
}
