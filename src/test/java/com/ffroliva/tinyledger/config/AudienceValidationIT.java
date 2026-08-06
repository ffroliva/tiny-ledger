package com.ffroliva.tinyledger.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.ffroliva.tinyledger.testsupport.KeycloakTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 3: with only {@code issuer-uri} configured, any token the realm issues is accepted — including one
 * minted for a client this service was never meant to serve. {@code spring.security.oauth2.resourceserver
 * .jwt.audiences} (application-full.properties) closes that: Boot's own auto-configured decoder adds an
 * {@code aud} validator when it is set, so no {@code JwtDecoder} bean is needed and this class declares no
 * {@code @SpringBootTest}, {@code @ActiveProfiles} or {@code @Import} of its own — it inherits everything
 * from {@link AbstractIntegrationTest}, which is what keeps the context count at one (ADR 0003).
 *
 * <p>Both tests are required. The positive case alone would pass with no validator at all — a token from
 * the realm's issuer always gets through if nothing checks who it was minted for. The negative case, a
 * token minted for {@code ledger-other} (a second fixture client with no audience mapper of its own, so its
 * tokens never carry {@code tiny-ledger-api}), is the one that actually proves the validator runs.
 */
class AudienceValidationIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test // the positive half: a token minted for THIS service's audience is accepted
    void aTokenForTheExpectedAudienceIsAccepted() throws Exception {
        String token = KeycloakTokens.accessToken(issuerUri(), "alice", "ledger-test");
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test // the differential half: same issuer, same signature, wrong audience — refused
    void aTokenForADifferentAudienceIsRefused() throws Exception {
        String token = KeycloakTokens.accessToken(issuerUri(), "alice", "ledger-other");
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
