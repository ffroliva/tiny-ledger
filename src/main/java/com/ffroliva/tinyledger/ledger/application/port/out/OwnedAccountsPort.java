package com.ffroliva.tinyledger.ledger.application.port.out;

/**
 * §6.5: how many accounts a principal already holds. A port because the count lives in
 * {@code balance}'s projection, and {@code config} is the only place allowed to know both (§9.2b).
 */
@FunctionalInterface
public interface OwnedAccountsPort {
    int countOwnedBy(String owner);
}
