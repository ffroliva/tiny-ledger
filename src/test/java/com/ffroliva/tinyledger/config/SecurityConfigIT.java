package com.ffroliva.tinyledger.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.ffroliva.tinyledger.testsupport.TestJwt;
import com.jayway.jsonpath.JsonPath;
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
     * chain, which registers at {@code SecurityProperties.DEFAULT_FILTER_ORDER = -100}; a plain
     * {@code @Component Filter} registers at {@code Ordered.LOWEST_PRECEDENCE}. Measured: dropping
     * {@code @Order(HIGHEST_PRECEDENCE)} from {@link com.ffroliva.tinyledger.platform.FapiInteractionIdFilter}
     * fails this on the header — the filter never ran, so there was no {@code traceId} in the MDC either.
     * Asserting the header and the body carry the <em>same</em> value is the point: two {@code exists()} checks
     * would be satisfied by two unrelated ids.
     */
    @Test
    void anUnauthenticatedRefusalStillCarriesTheInteractionId() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("x-fapi-interaction-id", "abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("x-fapi-interaction-id", "abc-123"))
                .andExpect(jsonPath("$.traceId").value("abc-123"));
    }

    @Test // and a valid token gets through, so the refusal above is not just "everything 401s"
    void aValidTokenIsAccepted() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isOk());
    }

    @Test // §6.4: the decorator is wired, not merely written — mallory holds a valid token and is
    // still refused, which no unit test on AuthorizedUseCases could establish
    void aValidTokenForTheWrongOwnerIsForbidden() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/balance", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("mallory")))
                .andExpect(status().isForbidden())
                // §6.5: the refusal must be a problem document, the same as the 401 above. These two 403s
                // are the only ones the suite asserts, so nothing else proves the content type.
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // the positive twin: a system that refuses everyone would satisfy the test above
    void theOwnerReadsHerOwnBalance() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/balance", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isOk());
    }

    @Test // History is a different bean; forgetting it fails SILENTLY, unlike Balances
    void aValidTokenForTheWrongOwnerCannotPageTheHistory() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/transactions", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("mallory")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    /**
     * §7: both auditor operations are {@code ledger:auditor}-only, and roles arrive in the follow-up plan.
     * {@code accountUid} is optional on the trail, so an ordinary token that omitted it paged every
     * account's id, amount and reference — which also voids §6.5's "account UUIDs are unguessable"
     * justification for wrong-owner 403s, since the trail hands the UUIDs out. Until the role exists
     * {@code full} refuses. This is the only test that reaches
     * {@link com.ffroliva.tinyledger.platform.SecurityProblemHandler#handle}: Task 6's 403 comes from
     * {@code OwnershipException} through {@code ErrorHandlingAdvice}, so a chain-level {@code denyAll()} is
     * the first thing to be refused before {@code DispatcherServlet}. The body assertion is the point — the
     * framework default here is {@code BasicErrorController}'s shape, which echoes the request {@code path}.
     */
    @Test
    void theAuditTrailIsRefusedToAnOrdinaryToken() throws Exception {
        mvc.perform(get("/api/v1/audit/entries")
                        .header("Authorization", "Bearer " + TestJwt.token("alice"))
                        .header("x-fapi-interaction-id", "abc-123"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"))
                // Task 7: the chain-level 403 is written by the same handler as the 401, so it is correlatable
                // for free once the filter outranks the chain — asserted here rather than in a new test.
                .andExpect(header().string("x-fapi-interaction-id", "abc-123"))
                .andExpect(jsonPath("$.traceId").value("abc-123"));
    }

    @Test // §7: and the raw stream, which returns verbatim payloads. Alice's OWN account, so this asserts a
    // flat denial rather than ownership — the operation is auditor-only, not owner-only.
    void theRawEventStreamIsRefusedToAnOrdinaryToken() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/events", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // §6.5: and an account nobody owns is still a 404, not a 403
    void anUnknownAccountIsNotFoundRatherThanForbidden() throws Exception {
        mvc.perform(get("/api/v1/accounts/{a}/balance", UUID.randomUUID())
                        .header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isNotFound());
    }

    /**
     * Opens a real account through the real chain, so the owner recorded on it is the token's subject and
     * the projection row the decorator reads is the one the write path produced. The projection is fed by a
     * synchronous {@code @EventListener} in both run modes, so it is readable as soon as this returns.
     */
    private UUID openAnAccountAs(String owner) throws Exception {
        String body = mvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + TestJwt.token(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACC-%s\",\"currency\":\"GBP\"}".formatted(owner)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.accountUid"));
    }
}
