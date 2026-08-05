package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;

public class AccountNotFoundException extends TinyLedgerException {
    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super(ErrorCode.ACCOUNT_NOT_FOUND, "account not found: " + accountId.value(), accountId.value());
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
