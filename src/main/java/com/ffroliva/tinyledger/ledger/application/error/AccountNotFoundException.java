package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.AccountId;

public class AccountNotFoundException extends RuntimeException {
    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super("account not found: " + accountId.value());
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
