package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.ledger.adapter.out.tenant.FixedTenantResolver;
import com.ffroliva.tinyledger.ledger.adapter.out.tenant.JwtClaimTenantResolver;
import com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The safety control for phase 0's mocked tenant.
 *
 * <p>The failure this exists to prevent is specific and silent: a configuration-backed resolver
 * added for local {@code full}-mode testing reaches production and maps <em>every</em> authenticated
 * request to one tenant, after which row-level security faithfully enforces the wrong label. The
 * two-tenant test does not necessarily catch it, because a resolver that ignores the JWT entirely
 * still returns <em>a</em> tenant, consistently.
 *
 * <p>So the invariant is about which resolver was <strong>composed</strong>, asserted at startup —
 * never about a provenance value the resolver reports, which the mock would report as cheerfully as
 * the real one.
 */
class TenantProvenanceGuardTest {

    @Configuration
    static class WithFixedResolver {
        @Bean
        TenantResolverPort tenantResolver() {
            return new FixedTenantResolver("local");
        }
    }

    @Configuration
    static class WithJwtResolver {
        @Bean
        TenantResolverPort tenantResolver() {
            return new JwtClaimTenantResolver("tenant_id");
        }
    }

    private static ApplicationContextRunner runner(Class<?> resolverConfig) {
        return new ApplicationContextRunner().withUserConfiguration(resolverConfig, TenantProvenanceGuard.class);
    }

    @Test
    void fullRefusesToStartWithTheFixedResolver() {
        // The named failure mode. Nothing else in the system would notice.
        runner(WithFixedResolver.class)
                .withPropertyValues("spring.profiles.active=full", "ledger.tenant.claim=tenant_id")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("fixed configuration-backed tenant resolver"));
    }

    @Test
    void fullRefusesToStartWithNoTenantClaimMapping() {
        // Absence of a claim mapping is a boot failure, never a default. A resolver with nothing to
        // read would fail every request closed, which reads as an outage rather than a misconfiguration.
        // Honesty note: ApplicationContextRunner never loads application*.properties, so this test
        // exercises the guard, not the packaging — the property itself is declared only in
        // application-full.properties, and a base-file default would defeat the guard invisibly here.
        runner(WithJwtResolver.class)
                .withPropertyValues("spring.profiles.active=full")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining(TenantProvenanceGuard.CLAIM_PROPERTY));
    }

    @Test
    void standaloneRefusesToStartUnderAProductionDeploymentClass() {
        // standalone has no authentication at all, so the mocked tenant is only safe where the
        // deployment says it is not production.
        runner(WithFixedResolver.class)
                .withPropertyValues("spring.profiles.active=standalone", "ledger.deployment.class=production")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("deployment class"));
    }

    @Test
    void fullStartsWithTheJwtResolverAndAClaimMapping() {
        // The positive control: without it, a guard that refuses everything would pass all three
        // negative tests above and prove nothing.
        runner(WithJwtResolver.class)
                .withPropertyValues("spring.profiles.active=full", "ledger.tenant.claim=tenant_id")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void standaloneStartsWithTheFixedResolverOutsideProduction() {
        runner(WithFixedResolver.class)
                .withPropertyValues("spring.profiles.active=standalone")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
