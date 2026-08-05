package com.ffroliva.tinyledger.shared;

/**
 * Spec §6.4: the fixed caller in {@code standalone}, where there is no authentication at all.
 *
 * <p>It lives in {@code shared} — the open kernel every slice may depend on — rather than in
 * {@code config}, because {@code config} imports the business modules: a {@code platform} or
 * {@code ledger} class reading it from {@code config} would close a cycle that
 * {@code HexagonalRulesTest.noCyclicPackages} fails on.
 */
public final class StandalonePrincipal {

    public static final String NAME = "local";

    private StandalonePrincipal() {}
}
