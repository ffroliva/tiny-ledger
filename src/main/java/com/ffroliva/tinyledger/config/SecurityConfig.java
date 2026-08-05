package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.platform.SecurityProblemHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
 */
@Configuration
public class SecurityConfig {

    /** The brief as written: in-memory, unauthenticated, dependency-free. */
    @Bean
    @Profile("standalone")
    SecurityFilterChain standaloneChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
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
     */
    @Bean
    @Profile("full")
    SecurityFilterChain fullChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}).authenticationEntryPoint(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
