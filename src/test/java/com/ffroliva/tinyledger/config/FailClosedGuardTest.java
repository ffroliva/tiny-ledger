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
}
