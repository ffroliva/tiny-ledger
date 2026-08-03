package com.flaviooliva.ledger.ledger.application.error;

import com.flaviooliva.ledger.shared.AccountId;

public class OwnershipException extends RuntimeException {
    private final String caller;
    private final AccountId accountId;

    public OwnershipException(String caller, AccountId accountId) {
        super("caller %s does not own account %s".formatted(caller, accountId.value()));
        this.caller = caller;
        this.accountId = accountId;
    }

    public String caller() {
        return caller;
    }

    public AccountId accountId() {
        return accountId;
    }
}
