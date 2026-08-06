package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRolesConverterTest {

    private final KeycloakRealmRolesConverter converter = new KeycloakRealmRolesConverter();

    private static Jwt jwtWith(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s");
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void readsRolesNestedUnderRealmAccess() {
        Jwt jwt = jwtWith(Map.of("realm_access", Map.of("roles", List.of("ledger:reader", "ledger:writer"))));
        assertThat(converter.convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ledger:reader", "ledger:writer");
    }

    @Test
    void aFlatRolesClaimIsIgnored() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("ledger:reader")));
        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    void aTokenWithNoRealmAccessHasNoAuthorities() {
        Jwt jwt = jwtWith(Map.of("scope", "openid"));
        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }
}
