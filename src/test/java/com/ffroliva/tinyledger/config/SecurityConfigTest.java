package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryHistoryUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code standalone} boots without infrastructure, so this stays a plain {@code @SpringBootTest} under
 * {@code verify}. It needs no {@code JwtDecoder} at all — the standalone chain permits everything and never
 * builds one. The {@code full} cases are {@code SecurityConfigIT}: supplying a decoder bean per test class
 * is the context fork ADR 0003 forbids.
 */
@SpringBootTest(classes = TinyLedgerApplication.class)
@ActiveProfiles("standalone")
class SecurityConfigTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test // the brief: standalone is unauthenticated, and stays so once Security is on the classpath
    void standalonePermitsAnUnauthenticatedRead() throws Exception {
        mvc().perform(get("/api/v1/accounts")).andExpect(status().isOk());
    }

    /**
     * §6.4: the read ports resolve to the authorisation decorators, asserted on the bean graph rather than
     * over HTTP — which is what lets this live under plain {@code verify}.
     *
     * <p>It is here, and not only in {@code SecurityConfigIT}, because that IT runs under {@code -Pit} and
     * ADR 0003's {@code unit} CI job has no Docker: without this, the {@code @Primary} wiring has **zero**
     * coverage in the fast job. Deleting the {@code authorizedHistory} bean was measured to leave the whole
     * unit suite green — one candidate remains, the context starts clean, and every caller can page every
     * other caller's history. This is the assertion that makes that fail fast, in both run modes, since
     * {@code UseCaseConfig} is profile-independent.
     */
    @Test
    void theReadPortsResolveToTheAuthorisationDecorators() {
        assertThat(context.getBean(QueryBalanceUseCase.class)).isInstanceOf(AuthorizedUseCases.Balances.class);
        assertThat(context.getBean(QueryHistoryUseCase.class)).isInstanceOf(AuthorizedUseCases.History.class);
    }
}
