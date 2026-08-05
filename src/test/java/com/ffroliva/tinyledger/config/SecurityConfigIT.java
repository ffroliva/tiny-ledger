package com.ffroliva.tinyledger.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.ffroliva.tinyledger.testsupport.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
}
