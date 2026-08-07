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

    // ANY_UID is inherited from AbstractIntegrationTest — SecurityConfigIT#theRawEventStreamIsRefusedToAnAdmin
    // needs the same literal, and two copies of one magic UUID is one too many.
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

    // N6. Review #2: the deposit rule had two negative tests through the chain and its withdrawal twin had
    // none — a typo in the withdrawal pattern would fail open into anyRequest().authenticated() and all
    // 43 tests would have stayed green.
    //
    // §9.3 N6 says "403; no event". Only the 403 is asserted, and deliberately: the refusal happens at the
    // filter chain, so no handler — and therefore no event store call — is reached at all. Asserting the
    // absence of an event here would be asserting that a code path that never ran did not run.
    @Test
    void aReaderMayNotWithdraw() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/withdrawals/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer("carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOVEMENT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test // N8: auditors observe, never mutate
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

        // The positive control beside the negative. `$.accounts` is empty here for exactly one interesting
        // reason — trent owns nothing — and this is the suite's only account-list body assertion through the
        // real chain, so a regression returning an empty list to *everyone* would leave the assertion below
        // green while it asserted nothing about admin at all. alice owns the account just opened.
        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isNotEmpty());

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

    /**
     * The positive twin of the test above, and it was missing: that one was the suite's <em>only</em>
     * {@code head(} call site, so a chain rule that denied {@code HEAD} to everyone satisfied it completely
     * — and HEAD would have silently stopped working for legitimate clients while the test that named it
     * stayed green. The fix it guards made HEAD subject to the reader rule; this proves it is subject to it
     * rather than excluded by it.
     *
     * <p>carol is the principal for the same reason she is the GET test's: {@code ledger:reader} and nothing
     * else, so this cannot pass on some other role. {@code ANY_UID} is never opened, so no fixture write is
     * spent and no ownership term is reached — the only thing standing between the request and the handler
     * is the chain rule under test.
     */
    @Test
    void headIsPermittedToAReaderRatherThanDeniedToEveryone() throws Exception {
        int status = mockMvc.perform(head("/api/v1/accounts/" + ANY_UID + "/transactions")
                        .header(HttpHeaders.AUTHORIZATION, bearer("carol")))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("403 would mean the chain denied HEAD to a reader — the regression this pair guards")
                .isNotEqualTo(403);

        // 200 and not 404, measured: /transactions answers an unknown account with an empty page, a
        // divergence from docs/spec.md:720 that AuthorizedUseCases:48-54 records as pre-existing and
        // deliberately out of its scope. Pinned rather than left as "not 403" so that if that divergence is
        // ever closed this line fails loudly and is corrected to 404 — a weaker assertion here would let
        // the pair quietly stop noticing anything. A failure on this line is not a HEAD regression.
        assertThat(status).isEqualTo(200);
    }
}
