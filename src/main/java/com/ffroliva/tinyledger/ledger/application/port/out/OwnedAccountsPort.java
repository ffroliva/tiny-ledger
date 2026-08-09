package com.ffroliva.tinyledger.ledger.application.port.out;

/**
 * §6.5: how many accounts a principal already holds — the one fact {@code OpenAccountService} needs
 * to refuse an over-limit open.
 *
 * <p>A port rather than a direct call into {@code balance}: the count lives in that module's account
 * projection, and the ledger asking it directly would couple two modules that {@code config} exists
 * to compose. The composition root supplies the implementation, so both run modes get the same rule
 * from the same source (§9.2b).
 */
@FunctionalInterface
public interface OwnedAccountsPort {
    int countOwnedBy(String owner);
}
