package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;

public class ConcurrencyConflictException extends TinyLedgerException {
    private final AccountId accountId;
    private final long expectedVersion;
    private final long currentVersion;

    public ConcurrencyConflictException(AccountId accountId, long expectedVersion, long currentVersion) {
        super(
                ErrorCode.VERSION_CONFLICT,
                "stream %s at version %d, expected %d".formatted(accountId.value(), currentVersion, expectedVersion),
                accountId.value(),
                currentVersion,
                expectedVersion);
        this.accountId = accountId;
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
    }

    public AccountId accountId() {
        return accountId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
