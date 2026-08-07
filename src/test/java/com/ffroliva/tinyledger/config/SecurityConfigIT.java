package com.ffroliva.tinyledger.config;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.ffroliva.tinyledger.testsupport.KeycloakTokens;
import com.ffroliva.tinyledger.testsupport.TestJwt;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.RequestDispatcher;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The {@code full} profile carries the entire security posture, and {@code application-full.properties}
 * hardcodes localhost Postgres/Redis/Kafka with Liquibase on — so it cannot boot under plain {@code verify}
 * and this has to be an IT. It declares no {@code @SpringBootTest}, {@code @ActiveProfiles},
 * {@code @Import} or {@code @TestPropertySource} of its own: it inherits every one of them, which is what
 * keeps the context count at one (ADR 0003).
 */
class SecurityConfigIT extends AbstractIntegrationTest {

    /**
     * Autowired rather than hand-built. A hand-built {@code webAppContextSetup(context).apply(springSecurity())}
     * registers the security filter and nothing else, so no application {@code Filter} is in the chain and
     * filter ordering cannot be observed — measured: {@code x-fapi-interaction-id} came back null on every
     * response. {@code @AutoConfigureMockMvc} on {@link AbstractIntegrationTest} assembles the chain from the
     * real filter registrations instead, which is what {@link #anUnauthenticatedRefusalStillCarriesTheInteractionId}
     * depends on.
     */
    @Autowired
    private MockMvc mvc;

    // N10: 401 to an unauthenticated request, and no information about whether the account exists — the path
    // here carries no account id at all, which is the strongest form of that: there is nothing to leak.
    @Test // the context starting at all is half the assertion — .jwt(...) needs a decoder to exist
    void anUnauthenticatedRequestIsRefused() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    @Test // §6.5: and the refusal is catalogued, not an empty body
    void theRefusalCarriesTheCataloguedProblem() throws Exception {
        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/unauthenticated"));
    }

    /**
     * Task 7's ordering fix, and the only test in the suite that can see it. A 401 is written by the security
     * chain, which registers at {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER = -100} (Boot 4.1's home
     * for that constant); a plain {@code @Component Filter} registers at
     * {@code Ordered.LOWEST_PRECEDENCE}. Measured: dropping
     * {@code @Order(HIGHEST_PRECEDENCE)} from {@link com.ffroliva.tinyledger.platform.FapiInteractionIdFilter}
     * fails this on the header — the filter never ran, so there was no {@code traceId} in the MDC either.
     * Asserting the header and the body carry the <em>same</em> value is the point: two {@code exists()} checks
     * would be satisfied by two unrelated ids.
     */
    @Test
    void anUnauthenticatedRefusalStillCarriesTheInteractionId() throws Exception {
        String interactionId = "c3f1a9e2-7b6d-4f8a-9c1e-2d3f4a5b6c7d";
        mvc.perform(get("/api/v1/accounts").header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("x-fapi-interaction-id", interactionId))
                .andExpect(jsonPath("$.traceId").value(interactionId));
    }

    // A boot proof, not just a unit-level check: a broken issuer-uri context starts clean and both 401
    // assertions above pass without the decoder ever running, so acceptance has to be asserted positively too.
    @Test // and a valid token gets through, so the refusal above is not just "everything 401s"
    void aTokenFromTheRealIssuerIsAccepted() throws Exception {
        String token = KeycloakTokens.accessToken(issuerUri(), "alice");
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test // the differential half: the old locally-minted token is now refused, proving the decoder moved
    // to the container rather than merely still working
    void aTokenThisIssuerDidNotMintIsRefused() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isUnauthorized());
    }

    @Test // N7: valid token, correct role, wrong owner.
    // §6.4: the decorator is wired, not merely written — mallory holds a valid token and is
    // still refused, which no unit test on AuthorizedUseCases could establish
    void aValidTokenForTheWrongOwnerIsForbidden() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/balance", alicesAccount).header("Authorization", bearer("mallory")))
                .andExpect(status().isForbidden())
                // §6.5: the refusal must be a problem document, the same as the 401 above. These two 403s
                // are the only ones the suite asserts, so nothing else proves the content type.
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // the positive twin: a system that refuses everyone would satisfy the test above
    void theOwnerReadsHerOwnBalance() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/balance", alicesAccount).header("Authorization", bearer("alice")))
                .andExpect(status().isOk());
    }

    @Test // History is a different bean; forgetting it fails SILENTLY, unlike Balances
    void aValidTokenForTheWrongOwnerCannotPageTheHistory() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/transactions", alicesAccount).header("Authorization", bearer("mallory")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    /**
     * §7: both auditor operations are {@code ledger:auditor}-only. {@code accountUid} is optional on the
     * trail, so an ordinary token that omitted it paged every account's id, amount and reference — which
     * also voids §6.5's "account UUIDs are unguessable" justification for wrong-owner 403s, since the
     * trail hands the UUIDs out. alice holds {@code ledger:writer}/{@code ledger:reader}, not
     * {@code ledger:auditor}, so the chain-level {@code hasAuthority("ledger:auditor")} check on
     * {@code /api/v1/audit/**} refuses her (Task 4). This is the only test that reaches
     * {@link com.ffroliva.tinyledger.platform.SecurityProblemHandler#handle}: Task 6's 403 comes from
     * {@code OwnershipException} through {@code ErrorHandlingAdvice}, so a chain-level authorisation
     * refusal is the first thing to run, before {@code DispatcherServlet}. The body assertion is the
     * point — the framework default here is {@code BasicErrorController}'s shape, which echoes the
     * request {@code path}.
     */
    @Test
    void theAuditTrailIsRefusedToAnOrdinaryToken() throws Exception {
        String interactionId = "d4e5f6a7-8b9c-4d1e-af23-456789abcdef";
        mvc.perform(get("/api/v1/audit/entries")
                        .header("Authorization", bearer("alice"))
                        .header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"))
                // Task 7: the chain-level 403 is written by the same handler as the 401, so it is correlatable
                // for free once the filter outranks the chain — asserted here rather than in a new test.
                .andExpect(header().string("x-fapi-interaction-id", interactionId))
                .andExpect(jsonPath("$.traceId").value(interactionId));
    }

    @Test // §7: and the raw stream, which returns verbatim payloads. Alice's OWN account, so this asserts a
    // flat denial rather than ownership — the operation is auditor-only, not owner-only.
    void theRawEventStreamIsRefusedToAnOrdinaryToken() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/events", alicesAccount).header("Authorization", bearer("alice")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    /**
     * P9: trent (admin) deposits into alice's account, addressed by its `accountUid` — not a name.
     * `trent` owns no account (D6), so this never goes near `GET /api/v1/accounts`; the UUID comes
     * straight from `openAnAccountAs`'s own response, the same way every other ownership test in this
     * class already gets it. The movement succeeds and the trail attributes it to him, not to alice —
     * the pair (actor, owner) on one row is the whole record of the delegation.
     *
     * <p>30s, not the 10s first drafted: {@code KafkaAuditModuleIT}'s equivalent non-DLT waits already
     * use 30s for the same real hop (publish -&gt; {@code AuditKafkaListener} -&gt; trail write); matching
     * that established margin here costs nothing and avoids re-tuning a timing constant per call site.
     */
    @Test
    void anAdminRecordsACrossAccountMovementAndTheAuditTrailAttributesItToHim() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", alicesAccount, UUID.randomUUID())
                        .header("Authorization", bearer("trent"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":10000}}"))
                .andExpect(status().isCreated());

        // The withdrawal half of the same clause, and the only test that reaches Withdraw's admin-TRUE
        // branch — LedgerController wires callerPrincipal.isAdmin() into Deposit (:80) and Withdraw (:93)
        // as two independent positional booleans, so the deposit above is no evidence for this line, and
        // every `new Withdraw(...)` in the unit tree passes false. This assertion catches the :93 -> literal
        // `false` slip, which silently removes admin withdrawal. The opposite slip, :93 -> literal `true` —
        // the one that lets every ledger:writer withdraw from any account — is invisible here, because
        // trent is an admin under both values; aWriterWithoutAdminCannotWithdrawFromSomeoneElsesAccount
        // below is the test that goes red on it. Neither direction is covered without both tests.
        mvc.perform(put("/api/v1/accounts/{a}/withdrawals/{w}", alicesAccount, UUID.randomUUID())
                        .header("Authorization", bearer("trent"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":4000}}"))
                .andExpect(status().isCreated());

        // 6000 is a number neither movement produces on its own: a deposit that never landed, or a
        // withdrawal that was refused, each leave a different balance here.
        mvc.perform(get("/api/v1/accounts/{a}/balance?consistency=strong", alicesAccount)
                        .header("Authorization", bearer("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount.minorUnits").value(6000));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mvc.perform(get("/api/v1/audit/entries")
                        .param("accountUid", alicesAccount.toString())
                        .header("Authorization", bearer("dave")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditEntries[?(@.type == 'MoneyDeposited')].actor")
                        .value(hasItem(KeycloakTokens.SUBJECTS.get("trent")))));
    }

    /**
     * P9's other half, split out of it on review. These two 403s are the sole coverage of two independent
     * production comparison points — the projection-backed read, and the strong read decided separately in
     * {@code StrongBalanceService:28} — and inside P9 they only ran if the deposit succeeded, so reverting
     * the write-side clause hid three untested claims behind one failure. Acting for the owner must not
     * silently make the admin an owner on either read path.
     */
    @Test
    void anAdminCannotReadTheBalanceOfAnAccountHeDoesNotOwn() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/balance", alicesAccount).header("Authorization", bearer("trent")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/accounts/{a}/balance?consistency=strong", alicesAccount)
                        .header("Authorization", bearer("trent")))
                .andExpect(status().isForbidden());
    }

    @Test // N13: ledger:admin widens ownership only — the trail stays ledger:auditor-only
    void theAuditTrailIsRefusedToAnAdmin() throws Exception {
        mvc.perform(get("/api/v1/audit/entries").header("Authorization", bearer("trent")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    // N14: same reason as N13, the other auditor route — SecurityConfig denies both with one matcher,
    // so a fix that split the routes and covered only /audit/** would pass N13 while an admin still
    // reads the raw event stream on this one.
    @Test
    void theRawEventStreamIsRefusedToAnAdmin() throws Exception {
        // ANY_UID, not a real account: the chain refuses before anything dereferences the id, so opening
        // one only spent a charged write. Same shape as RoleAuthorizationIT#aReaderIsRefusedTheRawEventStream.
        mvc.perform(get("/api/v1/accounts/{a}/events", ANY_UID).header("Authorization", bearer("trent")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // N17: mallory holds ledger:writer but not ledger:admin — the widening is gated on the role
    void aWriterWithoutAdminCannotDepositIntoSomeoneElsesAccount() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");
        UUID mallorysAccount = openAnAccountAs("mallory");

        // The positive control that pins which refusal the 403 below is. A chain-level rejection and an
        // OwnershipException both render /errors/forbidden, so nothing else here distinguishes them:
        // drop ledger:writer from mallory in docker/keycloak/realm-tiny-ledger.json — a hand-maintained
        // file with no gate tying it to this suite — and the assertion below stays green while the
        // ownership term it exists to test is never reached. This request fails first if that happens.
        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", mallorysAccount, UUID.randomUUID())
                        .header("Authorization", bearer("mallory"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":10000}}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", alicesAccount, UUID.randomUUID())
                        .header("Authorization", bearer("mallory"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":10000}}"))
                .andExpect(status().isForbidden());
    }

    /**
     * N17's withdrawal mirror, and the only test that can catch the slip that loses money. The ownership
     * term is wired into {@code Withdraw} at {@code LedgerController:93} independently of
     * {@code Deposit}'s at {@code :80}: widen {@code :93} unconditionally — a literal {@code true}, the
     * plausible copy-paste on a positional boolean — and every {@code ledger:writer} may withdraw from
     * any account in the system. Nothing else here sees it. P9's withdrawal passes either way, because
     * trent is an admin under both values; {@code RoleAuthorizationIT#aReaderMayNotWithdraw} passes
     * because carol is refused on her <em>role</em> at the filter chain and never reaches
     * {@code RecordMovementService:67}; and N17 above is a deposit, wired at the other line. mallory is
     * the principal for the same reason she is N17's: {@code ledger:writer} without {@code ledger:admin},
     * with her own-account control already established there.
     *
     * <p>alice's account is opened here rather than shared: nothing in this class holds one across
     * methods, and the balance is irrelevant — {@code RecordMovementService} decides ownership at ③
     * before it applies the movement at ⑤, so a zero balance never turns this into a 422.
     */
    @Test
    void aWriterWithoutAdminCannotWithdrawFromSomeoneElsesAccount() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(put("/api/v1/accounts/{a}/withdrawals/{w}", alicesAccount, UUID.randomUUID())
                        .header("Authorization", bearer("mallory"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":1000}}"))
                .andExpect(status().isForbidden());
    }

    @Test // §6.5: and an account nobody owns is still a 404, not a 403
    void anUnknownAccountIsNotFoundRatherThanForbidden() throws Exception {
        mvc.perform(get("/api/v1/accounts/{a}/balance", UUID.randomUUID()).header("Authorization", bearer("alice")))
                .andExpect(status().isNotFound());
    }

    /**
     * §6.5: {@link com.ffroliva.tinyledger.platform.SecurityProblemHandler}'s own javadoc records this —
     * Boot's default {@code /error} is {@code BasicErrorController}'s shape, which echoes the request
     * {@code path} back to the caller, an internal identifier §6.5 forbids crossing the boundary. Neither
     * {@link com.ffroliva.tinyledger.platform.SecurityProblemHandler} nor
     * {@link com.ffroliva.tinyledger.platform.ErrorHandlingAdvice} can close this: both act inside
     * {@code DispatcherServlet}'s own handling of a request, while {@code /error} is reached by a
     * <em>container-level</em> forward — a Filter throwing, or a raw {@code sendError} — that carries this
     * request's failure as two request attributes rather than as a Java exception. {@code requestAttr}
     * reproduces exactly that forward without depending on finding a component that currently triggers one:
     * the point is that {@code BasicErrorController} answers it this way for any future trigger, not just
     * ones that exist today.
     *
     * <p>{@code .with(jwt())} rather than a real {@code bearer(...)} header — measured, not assumed:
     * {@code OncePerRequestFilter.shouldNotFilterErrorDispatch()} defaults to {@code true}, and Boot 4.1's
     * {@code skipDispatch} treats the mere <em>presence</em> of the {@code ERROR_REQUEST_URI} attribute as
     * proof this is an error dispatch — not {@code getDispatcherType()}. That silently skips
     * {@code BearerTokenAuthenticationFilter} itself (also a {@code OncePerRequestFilter}), so a real bearer
     * header is never re-validated on this path. That is correct production behaviour, not a bug: a genuine
     * container forward carries the SAME request object as the original call, so the {@code SecurityContext}
     * the first pass already established survives the forward and there is nothing to re-authenticate.
     * {@code with(jwt())} reproduces exactly that already-authenticated state directly, the same way the
     * survived context would; a real header here would incorrectly imply bearer re-validation happens on
     * this path, which — measured — it does not.
     */
    @Test
    void anErrorDispatchDoesNotEchoTheRequestPath() throws Exception {
        String leakedPath = "/api/v1/accounts/91b1d1c2-aaaa-4f2b-9c3d-abcdefabcdef";
        mvc.perform(get("/error")
                        .with(jwt())
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, leakedPath))
                .andExpect(content().string(not(containsString(leakedPath))));
    }

    /**
     * Opens a real account through the real chain, so the owner recorded on it is the token's subject and
     * the projection row the decorator reads is the one the write path produced. The projection is fed by a
     * synchronous {@code @EventListener} in both run modes, so it is readable as soon as this returns.
     */
    private UUID openAnAccountAs(String owner) throws Exception {
        String body = mvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-%s\",\"currency\":\"GBP\"}".formatted(owner)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.accountUid"));
    }
}
