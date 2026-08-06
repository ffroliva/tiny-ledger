package com.ffroliva.tinyledger.platform;

import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Spec §1: losing a profile flag degrades to a refusal, never to an unauthenticated ledger. */
@Configuration
public class FailClosedGuard implements EnvironmentAware {
    @Override
    public void setEnvironment(Environment env) {
        boolean standalone = env.matchesProfiles("standalone") || env.getActiveProfiles().length == 0;
        if (standalone) {
            String[] fullShaped = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "spring.datasource.url",
                "spring.kafka.bootstrap-servers",
            };
            for (String key : fullShaped) {
                if (env.containsProperty(key)) {
                    throw new IllegalStateException(
                            "standalone profile is active but full-mode config '%s' is present — refusing to start unauthenticated (spec §1)"
                                    .formatted(key));
                }
            }
        } else if (!env.matchesProfiles("full")) {
            throw new IllegalStateException(
                    "active profile(s) '%s' are neither 'standalone' nor 'full' — refusing to start without a known security posture (spec §1)"
                            .formatted(String.join(",", env.getActiveProfiles())));
        }
    }
}
