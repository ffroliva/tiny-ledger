package com.ffroliva.tinyledger.ledger.adapter.out.tenant;

import com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort;
import com.ffroliva.tinyledger.shared.TenantId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Resolves the tenant from a named JWT claim. The {@code full}-profile resolver.
 *
 * <p>Fails closed, loudly, in both directions a token can be wrong: no authenticated JWT at all, and
 * a JWT whose tenant claim is absent or blank. Neither returns a default, because the failure mode
 * this whole design exists to prevent is one request quietly acquiring another tenant's scope — and
 * a resolver that substitutes a fallback is exactly how that happens.
 *
 * <p>The claim <em>name</em> is configuration; the decision to read a claim at all is not. That
 * split is what lets {@code standalone} and {@code full} differ by which bean is composed rather
 * than by a flag inside one bean.
 */
public final class JwtClaimTenantResolver implements TenantResolverPort {

    private final String claimName;

    public JwtClaimTenantResolver(String claimName) {
        if (claimName == null || claimName.isBlank()) {
            // A blank claim name would make every lookup miss and every request fail closed, which
            // looks like an outage rather than a misconfiguration. Refuse at construction, so it
            // surfaces as a startup failure naming the property.
            throw new IllegalArgumentException("tenant claim name must be configured");
        }
        this.claimName = claimName;
    }

    @Override
    public TenantId currentTenant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            throw new TenantUnresolvableException("no authenticated JWT to read tenant '%s' from".formatted(claimName));
        }
        Object claim = jwt.getToken().getClaims().get(claimName);
        if (claim == null || claim.toString().isBlank()) {
            throw new TenantUnresolvableException("token carries no '%s' claim".formatted(claimName));
        }
        return TenantId.of(claim.toString());
    }
}
