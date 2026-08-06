package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerPrincipalTest {

    private static CallerPrincipal under(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new CallerPrincipal(environment);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test // §6.4: the caller is the JWT subject when there is one
    void theSubjectOfTheAuthenticatedJwtIsTheCaller() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThat(under("full").current()).isEqualTo("alice");
    }

    @Test // standalone has no authentication at all, and the fixed principal is the contract
    void withNoAuthenticationTheCallerIsTheStandalonePrincipal() {
        assertThat(under("standalone").current()).isEqualTo("local");
    }

    @Test // the fail-closed half: outside standalone, a missing principal is a refusal, not a default
    void withNoAuthenticationOutsideStandaloneItRefuses() {
        assertThatThrownBy(() -> under("full").current()).isInstanceOf(IllegalStateException.class);
    }

    @Test // JwtValidators validates iss and the timestamps, never sub — a subject-less token is authenticated
    void anAuthenticatedTokenWithNoSubjectIsNotAPrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t")
                        .header("alg", "none")
                        .claim("scope", "read")
                        .build()));

        // null must not become the owner stamped on an account: every subject-less token would then
        // share one principal. It takes the same path as no authentication at all.
        assertThatThrownBy(() -> under("full").current()).isInstanceOf(IllegalStateException.class);
        assertThat(under("standalone").current()).isEqualTo("local");
    }

    @Test // §6.4: the one fact RecordMovementService widens on — detected from the JWT's authorities
    void isAdminIsTrueWhenTheJwtCarriesLedgerAdmin() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("trent").build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ledger:admin"))));

        assertThat(under("full").isAdmin()).isTrue();
    }

    @Test // ledger:writer alone — the ordinary caller — must not be mistaken for the widening authority
    void isAdminIsFalseWithoutTheLedgerAdminAuthority() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("alice").build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ledger:writer"))));

        assertThat(under("full").isAdmin()).isFalse();
    }

    @Test // standalone has no Authentication at all — isAdmin() must fail closed to false, not throw
    void isAdminIsFalseWithNoAuthentication() {
        assertThat(under("standalone").isAdmin()).isFalse();
    }
}
