package com.ffroliva.tinyledger.platform;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * §6.4: Keycloak nests realm roles under {@code realm_access.roles}. An earlier attempt pinned a flat
 * {@code roles} claim behind a green test — the shape Keycloak does not use — and was deleted rather
 * than kept, because a passing test asserting the wrong shape is worse than no test at all.
 *
 * <p>Authorities are the bare role names, so authorisation rules use {@code hasAuthority}. Spring's
 * {@code hasRole} prepends {@code ROLE_}, which these names do not carry.
 */
public class KeycloakRealmRolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        Collection<GrantedAuthority> authorities = List.of();
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            authorities = roles.stream()
                    .map(String::valueOf)
                    .map(SimpleGrantedAuthority::new)
                    .map(GrantedAuthority.class::cast)
                    .toList();
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
