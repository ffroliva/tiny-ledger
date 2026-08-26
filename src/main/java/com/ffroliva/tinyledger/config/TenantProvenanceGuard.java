package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.ledger.adapter.out.tenant.FixedTenantResolver;
import com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Asserts, at startup, that the composed tenant resolver matches the deployment's security posture.
 *
 * <p><strong>Why this is a startup assertion and not a code review.</strong> A configuration-backed
 * resolver added for local {@code full}-mode testing that reaches production maps every
 * authenticated request to a single tenant, and row-level security then enforces that wrong label
 * perfectly. Nothing downstream notices: a two-tenant isolation test still passes, because a
 * resolver that ignores the JWT entirely still returns <em>a</em> tenant consistently. The defect is
 * only visible at the moment of composition, so that is where it is caught.
 *
 * <p><strong>Why it inspects the bean rather than asking it.</strong> The obvious alternative — a
 * {@code provenance()} method on the port — asks the resolver to vouch for itself, which the mock
 * does as readily as the real one. Here provenance is a fact about the type the context actually
 * built.
 *
 * <p>It sits in {@code config} because it must name a concrete outbound adapter to recognise it, and
 * the composition root is the only place allowed to depend on {@code adapter.out}. Placing it in
 * {@code platform} failed two architecture rules at once — {@code onlyConfigInstantiatesOutboundAdapters}
 * and {@code noCyclicPackages}, since {@code ledger} already depends on {@code platform} through
 * {@code CallerPrincipal}. Both are the right rules; the guard was in the wrong package.
 */
@Configuration
public class TenantProvenanceGuard {

    /** The property naming which JWT claim carries the tenant. Its absence is a boot failure. */
    public static final String CLAIM_PROPERTY = "ledger.tenant.claim";

    /** Deployment-class signal. {@code production} forbids the unauthenticated run mode. */
    public static final String DEPLOYMENT_CLASS_PROPERTY = "ledger.deployment.class";

    public TenantProvenanceGuard(Environment env, TenantResolverPort resolver) {
        boolean standalone = env.matchesProfiles("standalone") || env.getActiveProfiles().length == 0;

        if (standalone) {
            if ("production".equalsIgnoreCase(env.getProperty(DEPLOYMENT_CLASS_PROPERTY, ""))) {
                throw new IllegalStateException(
                        "standalone profile is active under deployment class 'production' — refusing to start: "
                                + "standalone has no authentication and resolves every request to a fixed tenant");
            }
            return;
        }

        // full, or anything else FailClosedGuard has already allowed through.
        if (resolver instanceof FixedTenantResolver) {
            throw new IllegalStateException(
                    "full profile composed a fixed configuration-backed tenant resolver — refusing to start: "
                            + "every authenticated request would resolve to the same tenant and RLS would enforce it faithfully");
        }
        String claim = env.getProperty(CLAIM_PROPERTY);
        if (claim == null || claim.isBlank()) {
            throw new IllegalStateException(
                    "full profile has no '%s' — refusing to start: a missing tenant claim ".formatted(CLAIM_PROPERTY)
                            + "mapping is a misconfiguration, never a default of 'no tenancy'");
        }
    }
}
