package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.ffroliva.tinyledger.testsupport.KeycloakTokens;
import com.nimbusds.jwt.SignedJWT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 4: {@code ledger:reader} / {@code ledger:writer} / {@code ledger:auditor} enforced at the
 * {@code fullChain} filter, replacing Task 6b's {@code denyAll()}. Inherits every fixture from
 * {@link AbstractIntegrationTest} — the Keycloak container, {@code bearer(String)}, {@code issuerUri()}
 * — so the context cache key does not fork (ADR 0003).
 */
class RoleAuthorizationIT extends AbstractIntegrationTest {

    private static final String ANY_UID = "11111111-1111-4111-8111-111111111111";
    private static final String MOVEMENT_BODY = """
            {"amount":{"currency":"GBP","minorUnits":1000}}""";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anAuditorReadsTheTrail() throws Exception {
        mockMvc.perform(get("/api/v1/audit/entries").header(HttpHeaders.AUTHORIZATION, bearer("dave")))
                .andExpect(status().isOk());
    }

    /**
     * Task 2 review: {@code SUBJECTS} is a hand-maintained duplicate of the realm file with nothing
     * tying the two together — this converts it from an unverified constant into a checked one, so a
     * drifted pinned id fails loudly here instead of silently everywhere else. Kept separate from
     * {@link #anAuditorReadsTheTrail}: folded into that test, a drift throws before the {@code mockMvc}
     * call ever runs, so "an auditor can read the trail" is never actually exercised — a test named for
     * authorisation failing for a reason that has nothing to do with authorisation.
     */
    @Test
    void theMintedTokenSubjectMatchesThePinnedFixture() throws Exception {
        String token = KeycloakTokens.accessToken(issuerUri(), "dave");
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getSubject())
                .isEqualTo(KeycloakTokens.SUBJECTS.get("dave"));
    }

    @Test // same reason as theMintedTokenSubjectMatchesThePinnedFixture, for the new fixture user
    void theMintedTokenSubjectMatchesThePinnedFixtureForTrent() throws Exception {
        String token = KeycloakTokens.accessToken(issuerUri(), "trent");
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getSubject())
                .isEqualTo(KeycloakTokens.SUBJECTS.get("trent"));
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
                        .content(MOVEMENT_BODY))
                .andExpect(status().isForbidden());
    }

    // Review #2: the deposit rule had two negative tests through the chain and its withdrawal twin had
    // none — a typo in the withdrawal pattern would fail open into anyRequest().authenticated() and all
    // 43 tests would have stayed green.
    @Test
    void aReaderMayNotWithdraw() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/withdrawals/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer("carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOVEMENT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAuditorMayNotMoveMoney() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/deposits/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer("dave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOVEMENT_BODY))
                .andExpect(status().isForbidden());
    }

    // Review #2: POST /api/v1/accounts had only positive coverage through the chain.
    @Test
    void aReaderMayNotOpenAnAccount() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer("carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-carol\",\"currency\":\"GBP\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * The positive half of the reader rule, and the detector for a regression the comment at
     * SecurityConfig's POST matcher only warns about: if that matcher were ever made method-less,
     * GET /api/v1/accounts would silently require ledger:writer and readers would lose their own
     * account list. alice cannot catch it — she holds both roles. carol holds ledger:reader only.
     */
    @Test
    void aReaderOnlyTokenCanListHerOwnAccounts() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer("carol")))
                .andExpect(status().isOk());
    }

    // N16/D8: ledger:admin never widens GET /api/v1/accounts — trent owns nothing, so the list is empty
    @Test
    void anAdminListsOnlyTheAccountsHeOwnsWhichIsNone() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-N16\",\"currency\":\"GBP\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer("trent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isEmpty());
    }

    /**
     * N15: the actual conjunction test. `ledger:admin` alone does not satisfy the chain's
     * `ledger:writer` matcher on this path — P9 alone cannot fail against a blanket
     * `if (admin) return true` bypass that also happened to grant roles; this can, because it holds
     * `ledger:admin` and nothing else. `.with(jwt().authorities(...))` bypasses the real decoder and
     * injects the authorities directly — the same technique `SecurityConfigIT#anErrorDispatchDoesNotEchoTheRequestPath`
     * already uses — so this needs no realm change: the chain-level rule is what is under test, not
     * the token issuer.
     */
    @Test
    void anAdminWithoutWriterCannotDeposit() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/deposits/" + UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ledger:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOVEMENT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRolelessButAuthenticatedTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer("nobody")))
                .andExpect(status().isForbidden());
    }

    /**
     * Review #1: rules 2-4 are method-scoped ({@code POST}/{@code PUT}); the auditor rule and the
     * reader rule are the only two that could plausibly be reached by a method neither names. The
     * auditor rule is already method-less (it has no {@code HttpMethod} argument at all), so it is not
     * the risk here. The reader rule names {@code GET} explicitly: {@code hasAuthority} matches on
     * {@code request.getMethod()}, so a {@code HEAD} request matches none of the four role rules and
     * falls through to {@code anyRequest().authenticated()} — and Spring MVC serves {@code HEAD} from
     * the {@code @GetMapping} handler by default, discarding the body but keeping status and headers
     * (including {@code Content-Length}, a side channel on a spec that leans on unguessable UUIDs).
     * Measured, not reasoned about: see the fix report for the result.
     */
    @Test
    void headIsSubjectToTheSameRoleRuleAsGet() throws Exception {
        mockMvc.perform(head("/api/v1/accounts/" + ANY_UID + "/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer("nobody")))
                .andExpect(status().isForbidden());
    }
}
