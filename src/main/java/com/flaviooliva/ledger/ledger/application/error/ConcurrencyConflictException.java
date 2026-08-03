package com.flaviooliva.ledger.ledger.application.error;

import com.flaviooliva.ledger.shared.AccountId;

public class ConcurrencyConflictException extends RuntimeException {
    private final AccountId accountId;
    private final long expectedVersion;
    private final long currentVersion;

    public ConcurrencyConflictException(AccountId accountId, long expectedVersion, long currentVersion) {
        super("stream %s at version %d, expected %d".formatted(accountId.value(), currentVersion, expectedVersion));
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
