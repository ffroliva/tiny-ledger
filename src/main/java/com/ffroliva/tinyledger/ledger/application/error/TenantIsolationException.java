package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.AccountId;

/**
 * The caller's tenant is not the account's tenant, or the account has no tenant at all.
 *
 * <p>Separate from {@link OwnershipException} on purpose. Tenant is the isolation unit and ownership
 * is a grouping inside it, so collapsing the two would make a cross-tenant refusal indistinguishable
 * from an ordinary permission error in logs, metrics and incident review — and a cross-tenant attempt
 * is the one worth paging someone about.
 *
 * <p>It carries no tenant identifiers in its message: telling a caller which tenant owns an account
 * they cannot see is the existence oracle the isolation exists to close.
 */
public class TenantIsolationException extends RuntimeException {

    public TenantIsolationException(AccountId accountId) {
        super("account %s is not accessible in the current tenant".formatted(accountId.value()));
    }
}
