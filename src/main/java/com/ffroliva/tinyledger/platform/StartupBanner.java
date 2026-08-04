package com.ffroliva.tinyledger.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Spec §1: the standalone banner is the visible half of the fail-closed guard's promise. */
@Component
public class StartupBanner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);

    private final Environment env;

    public StartupBanner(Environment env) {
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        String address = env.getProperty("server.address", "0.0.0.0");
        String port = env.getProperty("server.port", "8080");
        log.info("Tiny Ledger listening on http://{}:{}", address, port);
        boolean standalone = env.matchesProfiles("standalone") || env.getActiveProfiles().length == 0;
        if (standalone) {
            log.info("AUTH DISABLED (standalone)");
        }
    }
}
