package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.ffroliva.tinyledger.testsupport.KeycloakTokens;
import com.nimbusds.jwt.SignedJWT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 4: {@code ledger:reader} / {@code ledger:writer} / {@code ledger:auditor} enforced at the
 * {@code fullChain} filter, replacing Task 6b's {@code denyAll()}. Inherits every fixture from
 * {@link AbstractIntegrationTest} — the Keycloak container, {@code bearer(String)}, {@code issuerUri()}
 * — so the context cache key does not fork (ADR 0003).
 */
class RoleAuthorizationIT extends AbstractIntegrationTest {

    private static final String ANY_UID = "11111111-1111-4111-8111-111111111111";
    private static final String DEPOSIT_BODY = """
            {"amount":{"currency":"GBP","minorUnits":1000}}""";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anAuditorReadsTheTrail() throws Exception {
        String token = KeycloakTokens.accessToken(issuerUri(), "dave");
        // Task 2 review: SUBJECTS is a hand-maintained duplicate of the realm file with nothing tying
        // the two together — this converts it from an unverified constant into a checked one, so a
        // drifted pinned id fails loudly here instead of silently everywhere else.
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getSubject())
                .isEqualTo(KeycloakTokens.SUBJECTS.get("dave"));

        mockMvc.perform(get("/api/v1/audit/entries").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aReaderIsRefusedTheTrail() throws Exception {
        mockMvc.perform(get("/api/v1/audit/entries").header(HttpHeaders.AUTHORIZATION, bearer("carol")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aReaderIsRefusedTheRawEventStream() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + ANY_UID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer("carol")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aReaderMayNotMoveMoney() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/deposits/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer("carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEPOSIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAuditorMayNotMoveMoney() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/deposits/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer("dave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEPOSIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRolelessButAuthenticatedTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer("nobody")))
                .andExpect(status().isForbidden());
    }
}
