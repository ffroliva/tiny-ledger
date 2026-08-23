package com.ffroliva.tinyledger.shared;

import java.util.Objects;

/**
 * The isolation unit. Distinct from {@code owner}, and deliberately not a {@link String}.
 *
 * <p>The seed contract keeps tenant and owner as <strong>independent</strong> groupings: one owner
 * string may exist in two tenants, and one tenant holds many owners. Both are identifiers of a
 * party-ish thing, both would otherwise be {@code String}, and they sit next to each other in
 * {@code AccountOpened} — so a wrapper here is not ceremony, it is the only thing that makes
 * swapping them a compile error rather than a cross-tenant read.
 *
 * <p>It lives in {@code shared} for the same reason {@link StandalonePrincipal} does: {@code config}
 * imports the business modules, so a {@code ledger} type reading it from {@code config} would close
 * a package cycle that {@code HexagonalRulesTest.noCyclicPackages} fails on.
 */
public record TenantId(String value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            // A blank tenant is the shape a misconfigured resolver produces — an empty property, a
            // claim that was present but unset. Refusing it here means such a resolver fails at the
            // point of construction rather than isolating every account into one nameless tenant.
            throw new IllegalArgumentException("tenant id must not be blank");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }
}
