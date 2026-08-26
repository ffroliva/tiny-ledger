package com.ffroliva.tinyledger.ledger.application.port.out;

import com.ffroliva.tinyledger.shared.TenantId;

/**
 * The one place a tenant value enters the system.
 *
 * <p>Deliberately a port with a single method, because the tenancy design's safety rests on there
 * being exactly one injection point: configuration in {@code standalone}, a JWT claim in
 * {@code full}. Swapping those is then a change of which bean is composed, not a redesign — and,
 * more importantly, <em>which</em> implementation is composed becomes a fact about the profile's
 * bean graph rather than something an implementation asserts about itself.
 *
 * <p>It resolves from <strong>authenticated context</strong> and never from request data. A tenant
 * that can set its own tenant id can read another's accounts, so this must not become a parameter
 * on a command.
 */
public interface TenantResolverPort {

    /**
     * The tenant of the current authenticated context.
     *
     * @return never {@code null} — a resolver that cannot determine a tenant throws rather than
     *     returning one, because a null here would be indistinguishable from a legacy account's
     *     absent tenant and would silently bind a new account to nothing.
     */
    TenantId currentTenant();
}
