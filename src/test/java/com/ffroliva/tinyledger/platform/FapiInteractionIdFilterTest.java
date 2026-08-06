package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FapiInteractionIdFilterTest {

    private final FapiInteractionIdFilter filter = new FapiInteractionIdFilter();

    private String echoed(String supplied) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (supplied != null) {
            request.addHeader(FapiInteractionIdFilter.HEADER, supplied);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getHeader(FapiInteractionIdFilter.HEADER);
    }

    @Test
    void aValidUuidIsEchoedUnchanged() throws Exception {
        String supplied = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
        assertThat(echoed(supplied)).isEqualTo(supplied);
    }

    @Test
    void aNewlineBearingValueIsReplacedNotEchoed() throws Exception {
        String forged = "abc\nWARN  forged log line";
        assertThat(echoed(forged)).doesNotContain("\n").isNotEqualTo(forged);
    }

    @Test
    void anOverlongValueIsReplaced() throws Exception {
        assertThat(echoed("x".repeat(4096))).hasSize(36);
    }

    @Test
    void aNonUuidValueIsReplacedWithAMintedUuid() throws Exception {
        assertThat(echoed("not-a-uuid")).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void anAbsentHeaderStillMints() throws Exception {
        assertThat(echoed(null)).hasSize(36);
    }
}
