package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.platform.KeycloakRealmRolesConverter;
import com.ffroliva.tinyledger.platform.SecurityProblemHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spec §1: one codebase, two run modes — so security is one mechanism with two configurations rather
 * than a dependency that is excluded in one of them. Excluding the autoconfiguration would also work
 * and is two properties, but it leaves no {@code HttpSecurity} at all, so {@code standalone} could
 * never declare a chain — and the {@code x-fapi-interaction-id} filter and every future chain-level
 * concern would have nothing to attach to.
 *
 * <p>CSRF is off in both profiles and sessions are stateless. That is not a shortcut: CSRF defends
 * against the browser attaching <em>ambient</em> credentials (a cookie, Basic auth) to a cross-site
 * request. A token in an {@code Authorization} header is not ambient — script must attach it
 * deliberately — so there is nothing for the token to protect. This system has no cookie, no session,
 * and no browser surface at all (no springdoc, no swagger-ui, no static resources). The stateless
 * declaration is the guard: if anyone later introduces cookie authentication or a UI, it is this line
 * that has to change, which forces the CSRF question back into the open instead of leaving a hole.
 *
 * <p>Both chains disable logout, and that is a fix rather than tidying. {@code HttpSecurityConfiguration}
 * applies {@code logout(withDefaults())} to every {@code HttpSecurity} unconditionally, and because CSRF is
 * disabled above, {@code LogoutConfigurer#createLogoutRequestMatcher} sees a null {@code CsrfConfigurer} and
 * ORs {@code GET}/{@code PUT}/{@code DELETE} in beside {@code POST}. {@code LogoutFilter} runs ahead of
 * {@code AuthorizationFilter}, so {@code full} answered an <em>unauthenticated</em> {@code GET /logout} with
 * {@code 302 → /login?logout} — a page this API does not serve — instead of the catalogued 401. The route is
 * in neither {@code openapi.yaml} nor {@code ErrorCode}: a session-based route on a stateless bearer API,
 * contributed by the framework and authorised by nothing. Measured over HTTP, not inferred:
 * {@code SecurityConfigTest#logoutIsNotARouteThisApiServes} failed {@code expected:<404> but was:<302>}
 * before these two lines.
 */
@Configuration
public class SecurityConfig {

    /** The brief as written: in-memory, unauthenticated, dependency-free. */
    @Bean
    @Profile("standalone")
    SecurityFilterChain standaloneChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * The production stack: every API call carries a JWT; ownership is checked at the port boundary (§6.4).
     *
     * <p>The decoder is built from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, which
     * {@link com.ffroliva.tinyledger.platform.FailClosedGuard} already treats as a full-mode marker — if
     * that property is ever present while {@code standalone} is active, the guard refuses to start rather
     * than run an unauthenticated ledger (spec §1). Setting it here is what makes that guard meaningful.
     *
     * <p>{@code authenticationEntryPoint} is set <em>both</em> inside {@code oauth2ResourceServer} and on
     * {@code exceptionHandling}. On Spring Security 7.0 / Boot 4.1 the inner one is redundant — measured:
     * dropping it leaves all three {@code SecurityConfigIT} cases green, so the outer setting already wins
     * for bearer-token failures. It is kept as the framework-documented placement in case a future version
     * reinstates {@code BearerTokenAuthenticationEntryPoint}, and it costs nothing. What actually enforces
     * the outcome is {@code SecurityConfigIT#theRefusalCarriesTheCataloguedProblem}: with neither entry
     * point wired, that test fails with "Content type not set" while the status-only assertion still passes.
     *
     * <p>Task 3: the audience check is deliberately absent from this method. It lives entirely in
     * {@code spring.security.oauth2.resourceserver.jwt.audiences} in {@code application-full.properties} —
     * Boot's own auto-configured decoder adds the {@code aud} validator to the same decoder when that
     * property is set (measured against the shipped 4.1.0 jar), so a {@code JwtDecoder} bean here would
     * only duplicate what the framework already builds, and would fork the single Spring context
     * {@code AbstractIntegrationTest} relies on (ADR 0003) for no gain.
     */
    @Bean
    @Profile("full")
    SecurityFilterChain fullChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // §6.4 row 4 / §7: role alone, no account subject. This REPLACES Task 6b's
                        // denyAll() — the routes stay closed to everyone without ledger:auditor.
                        // standalone answers these 501 from its own chain — SecurityConfigTest goes red
                        // if these two matchers are moved there (measured: 403 instead of 501).
                        .requestMatchers("/api/v1/audit/**", "/api/v1/accounts/*/events")
                        .hasAuthority("ledger:auditor")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts")
                        .hasAuthority("ledger:writer")
                        // Method-less, not PUT-scoped: a deposit/withdrawal path is a write path regardless
                        // of verb. Scoping it to PUT left every other verb on these paths (no handler today,
                        // so 405 — but the framework, not this rule, would decide that) falling through to
                        // the method-less reader matcher below, making ledger:reader — the weakest role —
                        // the default authority on a money path. POST /api/v1/accounts stays method-scoped
                        // deliberately: broadening it the same way would also catch GET /api/v1/accounts and
                        // require ledger:writer there, blocking readers from listing their own accounts.
                        .requestMatchers("/api/v1/accounts/*/deposits/*", "/api/v1/accounts/*/withdrawals/*")
                        .hasAuthority("ledger:writer")
                        // Method-less, not GET-scoped: hasAuthority matches on request.getMethod(), and
                        // Spring MVC serves HEAD from the same @GetMapping handler by default — a
                        // GET-only matcher here left HEAD falling through to anyRequest().authenticated(),
                        // skipping the role check while still returning real status/Content-Length.
                        // Measured: RoleAuthorizationIT#headIsSubjectToTheSameRoleRuleAsGet failed
                        // 200 instead of 403 before this line.
                        .requestMatchers("/api/v1/accounts/**")
                        .hasAuthority("ledger:reader")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRealmRolesConverter()))
                                .authenticationEntryPoint(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
