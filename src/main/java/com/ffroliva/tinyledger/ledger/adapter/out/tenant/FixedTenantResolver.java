package com.ffroliva.tinyledger.ledger.adapter.out.tenant;

import com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort;
import com.ffroliva.tinyledger.shared.TenantId;

/**
 * Resolves every request to one configured tenant. The {@code standalone}-profile resolver, and the
 * phase-0 mock.
 *
 * <p>It is the mock only in the sense that the <em>value</em> is stubbed. The dimension is real: the
 * account still carries a tenant, the authorisation path still compares one, and swapping this for
 * {@link JwtClaimTenantResolver} changes which bean a profile composes and nothing else.
 *
 * <p><strong>This class must never be composable in {@code full}.</strong> A config-backed resolver
 * reaching production would map every authenticated request to one tenant, and row-level security
 * would then faithfully enforce the wrong label — the failure mode is silent and total. The guard is
 * structural: this bean is declared only in the {@code standalone} configuration, so it is not a
 * property of the resolver that it is a mock, it is a property of the bean graph the profile builds.
 */
public final class FixedTenantResolver implements TenantResolverPort {

    private final TenantId tenant;

    public FixedTenantResolver(String tenant) {
        // TenantId rejects null and blank, so a missing property fails at startup naming the value,
        // rather than at the first request naming nothing.
        this.tenant = TenantId.of(tenant);
    }

    @Override
    public TenantId currentTenant() {
        return tenant;
    }
}
