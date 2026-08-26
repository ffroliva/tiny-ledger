package com.ffroliva.tinyledger.ledger.adapter.out.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.shared.TenantId;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The behavioural half of the provenance invariant.
 *
 * <p>{@code TenantProvenanceGuardTest} proves the wrong resolver cannot be composed. It cannot prove
 * the right one does anything — a resolver that returns a constant would satisfy every startup
 * assertion and every single-tenant test. Only two tokens carrying <em>different</em> claims
 * distinguish "reads the claim" from "returns a tenant".
 */
class JwtClaimTenantResolverTest {

    private static final String CLAIM = "tenant_id";
    private final JwtClaimTenantResolver resolver = new JwtClaimTenantResolver(CLAIM);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(Map<String, Object> claims) {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void twoTokensWithDifferentTenantClaimsResolveToDifferentTenants() {
        authenticateWith(Map.of("sub", "alice", CLAIM, "tenant-a"));
        assertThat(resolver.currentTenant()).isEqualTo(TenantId.of("tenant-a"));

        authenticateWith(Map.of("sub", "bob", CLAIM, "tenant-b"));
        assertThat(resolver.currentTenant()).isEqualTo(TenantId.of("tenant-b"));
    }

    @Test
    void aTokenWithNoTenantClaimFailsClosed() {
        authenticateWith(Map.of("sub", "alice"));

        assertThatThrownBy(resolver::currentTenant)
                .isInstanceOf(TenantUnresolvableException.class)
                .hasMessageContaining(CLAIM);
    }

    @Test
    void aTokenWithABlankTenantClaimFailsClosed() {
        // Distinct from absence: a present-but-empty claim is what a misconfigured identity-provider
        // mapper produces, and treating it as a tenant would isolate every caller into one nameless
        // group rather than refusing.
        authenticateWith(Map.of("sub", "alice", CLAIM, "  "));

        assertThatThrownBy(resolver::currentTenant).isInstanceOf(TenantUnresolvableException.class);
    }

    @Test
    void noAuthenticationAtAllFailsClosed() {
        assertThatThrownBy(resolver::currentTenant)
                .isInstanceOf(TenantUnresolvableException.class)
                .hasMessageContaining("no authenticated JWT");
    }
}
