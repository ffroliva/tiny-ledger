package com.flaviooliva.ledger.balance.application.port.in;

import com.flaviooliva.ledger.ledger.domain.MovementType;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** Plan 1 settles synchronously, so {@code settlementTime} equals {@code transactionTime}. */
public record TransactionView(
        UUID transactionUid,
        AccountId accountId,
        MovementType type,
        String direction,
        Money amount,
        Money balanceAfter,
        String status,
        Instant transactionTime,
        Instant settlementTime,
        String reference) {
    public static final String IN = "IN";
    public static final String OUT = "OUT";
    public static final String SETTLED = "SETTLED";
}
