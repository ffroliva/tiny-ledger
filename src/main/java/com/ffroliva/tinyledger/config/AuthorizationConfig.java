package com.ffroliva.tinyledger.config;

/** Spec §6.4: authorisation is a use-case concern — this is the fixed caller in {@code standalone}. */
public final class AuthorizationConfig {
    public static final String STANDALONE_PRINCIPAL = "local";

    private AuthorizationConfig() {}
}
