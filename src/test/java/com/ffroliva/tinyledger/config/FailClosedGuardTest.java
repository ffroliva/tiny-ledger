package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.*;

import com.ffroliva.tinyledger.platform.FailClosedGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FailClosedGuardTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FailClosedGuard.class)
            .withPropertyValues("spring.profiles.active=standalone");

    @Test
    void refusesFullShapedConfigUnderStandalone() {
        runner.withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=http://keycloak/realm")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bootsCleanStandalone() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesAThirdProfileThatIsNeitherStandaloneNorFull() {
        new ApplicationContextRunner()
                .withUserConfiguration(FailClosedGuard.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * §9.7's load runs activate {@code full,load} — the {@code load} profile is an overlay that raises
     * rate limits (application-load.properties), never a run mode of its own.
     *
     * <p>This passes because the guard tests profile <em>membership</em>: {@code matchesProfiles("full")}
     * is true when {@code full} is among the active set, and a second active profile does not falsify
     * it. That is a property of the guard's condition, not of anything this test does, and nothing else
     * in the suite pinned it — {@code grep -rn 'spring.profiles.active' src/test/java} reached only this
     * file. So a future edit narrowing the guard to an exclusive match would make every load run refuse
     * to start, and would do it silently.
     */
    @Test
    void bootsUnderFullPlusTheLoadOverlay() {
        new ApplicationContextRunner()
                .withUserConfiguration(FailClosedGuard.class)
                .withPropertyValues("spring.profiles.active=full,load")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The other half, and the one that matters: {@code load} raises rate limits, so it must never be
     * capable of running without the security posture it is raising them inside. Alone it is neither
     * {@code standalone} nor {@code full}, so the guard refuses (spec §1).
     */
    @Test
    void refusesTheLoadOverlayOnItsOwn() {
        new ApplicationContextRunner()
                .withUserConfiguration(FailClosedGuard.class)
                .withPropertyValues("spring.profiles.active=load")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("neither 'standalone' nor 'full'"));
    }
}
