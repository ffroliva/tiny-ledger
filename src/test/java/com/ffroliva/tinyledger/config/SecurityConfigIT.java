package com.ffroliva.tinyledger.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.ffroliva.tinyledger.testsupport.TestJwt;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The {@code full} profile carries the entire security posture, and {@code application-full.properties}
 * hardcodes localhost Postgres/Redis/Kafka with Liquibase on — so it cannot boot under plain {@code verify}
 * and this has to be an IT. It declares no {@code @SpringBootTest}, {@code @ActiveProfiles},
 * {@code @Import} or {@code @TestPropertySource} of its own: it inherits every one of them, which is what
 * keeps the context count at one (ADR 0003).
 */
class SecurityConfigIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test // the context starting at all is half the assertion — .jwt(...) needs a decoder to exist
    void anUnauthenticatedRequestIsRefused() throws Exception {
        mvc().perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    @Test // §6.5: and the refusal is catalogued, not an empty body
    void theRefusalCarriesTheCataloguedProblem() throws Exception {
        mvc().perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/unauthenticated"));
    }

    @Test // and a valid token gets through, so the refusal above is not just "everything 401s"
    void aValidTokenIsAccepted() throws Exception {
        mvc().perform(get("/api/v1/accounts").header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isOk());
    }

    @Test // §6.4: the decorator is wired, not merely written — mallory holds a valid token and is
    // still refused, which no unit test on AuthorizedUseCases could establish
    void aValidTokenForTheWrongOwnerIsForbidden() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc().perform(get("/api/v1/accounts/{a}/balance", alicesAccount)
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

        mvc().perform(get("/api/v1/accounts/{a}/balance", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isOk());
    }

    @Test // History is a different bean; forgetting it fails SILENTLY, unlike Balances
    void aValidTokenForTheWrongOwnerCannotPageTheHistory() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc().perform(get("/api/v1/accounts/{a}/transactions", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("mallory")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // §6.5: and an account nobody owns is still a 404, not a 403
    void anUnknownAccountIsNotFoundRatherThanForbidden() throws Exception {
        mvc().perform(get("/api/v1/accounts/{a}/balance", UUID.randomUUID())
                        .header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isNotFound());
    }

    /**
     * Opens a real account through the real chain, so the owner recorded on it is the token's subject and
     * the projection row the decorator reads is the one the write path produced. The projection is fed by a
     * synchronous {@code @EventListener} in both run modes, so it is readable as soon as this returns.
     */
    private UUID openAnAccountAs(String owner) throws Exception {
        String body = mvc().perform(post("/api/v1/accounts")
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
