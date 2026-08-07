package com.ffroliva.tinyledger.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The {@code standalone} twin of {@code SecurityConfigIT#anErrorDispatchDoesNotEchoTheRequestPath}, and
 * the missing enforcement for {@code AGENTS.md} trap 6.
 *
 * <p><strong>What was unguarded.</strong> {@code spring.autoconfigure.exclude} <em>replaces</em> rather
 * than appends, so {@code application-standalone.properties} must restate every entry the base file
 * contributes or lose them silently. It does — and that line's own comment records the measured symptom
 * of removing it: "{@code full}'s exclusion of {@code ErrorMvcAutoConfiguration} is shadowed under
 * {@code standalone} and {@code GET /error} keeps answering with {@code BasicErrorController}'s
 * {@code {"status":999,"error":"None"}} shape", which echoes the request path §6.5 forbids crossing the
 * boundary.
 *
 * <p>That guard was a properties line with nothing testing it. The one assertion on {@code /error} in
 * the whole suite lives in {@code SecurityConfigIT}, which runs under {@code @ActiveProfiles("full")} —
 * so deleting the standalone restatement left the entire suite green while the mode it broke is the one
 * the trap names. Verified before writing this: a search for the {@code /error} endpoint across
 * {@code src/test} returns exactly one hit, against a control of 53 for {@code /errors/} problem types.
 *
 * <p>Boots {@code standalone}, so it starts no containers and belongs in the unit suite — the split
 * {@code AGENTS.md} calls load-bearing is preserved.
 */
@SpringBootTest(classes = TinyLedgerApplication.class)
@ActiveProfiles("standalone")
@AutoConfigureMockMvc
class StandaloneErrorDispatchTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anErrorDispatchDoesNotEchoTheRequestPathInStandaloneEither() throws Exception {
        String leakedPath = "/api/v1/accounts/91b1d1c2-aaaa-4f2b-9c3d-abcdefabcdef";

        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, leakedPath))
                .andExpect(content().string(not(containsString(leakedPath))));
    }

    /**
     * The positive twin, and it is what makes the test above non-vacuous: an empty body would satisfy
     * "does not contain the path" perfectly. {@code BasicErrorController}'s shape is identifiable by its
     * own keys rather than by the path it leaks, so asserting their absence catches the regression even
     * if a future leak used a different attribute.
     */
    @Test
    void anErrorDispatchIsNotServedByBasicErrorController() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/accounts"))
                .andExpect(content().string(not(containsString("\"error\":\"None\""))))
                .andExpect(content().string(not(containsString("\"path\":"))));
    }
}
