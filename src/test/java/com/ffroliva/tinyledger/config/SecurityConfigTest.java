package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryHistoryUseCase;
import java.util.UUID;
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
     * Task 3: {@code spring.security.oauth2.resourceserver.jwt.audiences} lives only in
     * {@code application-full.properties}, which {@code standalone} never loads — so there is no
     * {@code JwtDecoder} to run the check against in the first place. Asserted rather than assumed: a
     * header carrying a value that is not even a well-formed JWT still gets a 200, proving the standalone
     * chain never looks at {@code Authorization} at all, audience or otherwise.
     */
    @Test
    void standaloneIgnoresBearerTokensEntirely() throws Exception {
        mvc().perform(get("/api/v1/accounts").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isOk());
    }

    /**
     * The framework contributes an inbound route this API never declared.
     * {@code HttpSecurityConfiguration#httpSecurity()} applies {@code logout(withDefaults())} to every
     * {@code HttpSecurity}, and because both chains call {@code csrf(csrf -> csrf.disable())},
     * {@code LogoutConfigurer#createLogoutRequestMatcher} finds a null {@code CsrfConfigurer} and ORs
     * {@code GET}/{@code PUT}/{@code DELETE} in beside {@code POST}. {@code LogoutFilter} sits ahead of
     * {@code AuthorizationFilter}, so in {@code full} an <em>unauthenticated</em> caller was answered
     * {@code 302 → /login?logout} — a page that does not exist — instead of the catalogued 401, and
     * {@code /logout} appears in neither {@code openapi.yaml} nor {@code ErrorCode}.
     *
     * <p>Measured, not inferred: before {@code .logout(AbstractHttpConfigurer::disable)} this assertion
     * failed with {@code Status expected:<404> but was:<302>}. It runs here rather than in
     * {@code SecurityConfigIT} because the route is contributed to both chains identically and this class
     * needs no Docker — but the disable is applied to both, since it is {@code full} where the 302 mattered.
     */
    @Test
    void logoutIsNotARouteThisApiServes() throws Exception {
        mvc().perform(get("/logout")).andExpect(status().isNotFound());
    }

    /**
     * §7: {@code full} enforces {@code ledger:auditor} on the two auditor operations via a
     * {@code hasAuthority} role matcher in {@code fullChain} — not, as this comment once said, a
     * chain-level {@code denyAll()}; that stopgap is gone. {@code standalone} has its own chain
     * and must keep answering the contractual 501 (`openapi.yaml:296-355`). Nothing else in the repository
     * holds that line over a filter chain: no feature file mentions {@code audit}, {@code /events}, 501 or
     * {@code not-available}, and {@code AuditControllerTest} is a {@code @WebMvcTest} slice with no chain at
     * all — so the same matchers applied to {@code standaloneChain}, or to a shared builder, would leave
     * every other test green while {@code standalone} answered 403. Measured: moving them there turns this
     * test red on both routes.
     */
    @Test
    void standaloneStillAnswersTheAuditorRoutesWithTheContractual501() throws Exception {
        mvc().perform(get("/api/v1/audit/entries"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("/errors/not-available-in-standalone"));

        mvc().perform(get("/api/v1/accounts/{a}/events", UUID.randomUUID()))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("/errors/not-available-in-standalone"));
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
