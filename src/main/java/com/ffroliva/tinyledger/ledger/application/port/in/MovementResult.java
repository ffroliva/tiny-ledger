package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.ledger.domain.Quantity;
import com.ffroliva.tinyledger.ledger.domain.TaxLot;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MovementResult(
        AccountId accountId,
        UUID movementUid,
        MovementType type,
        long version,
        Money amount,
        Money balanceAfter,
        Instant occurredAt,
        Outcome outcome,
        String rejectionReason,
        Quantity quantity,
        List<TaxLot> taxLots) {

    public MovementResult(
            AccountId accountId,
            UUID movementUid,
            MovementType type,
            long version,
            Money amount,
            Money balanceAfter,
            Instant occurredAt,
            Outcome outcome,
            String rejectionReason) {
        this(
                accountId,
                movementUid,
                type,
                version,
                amount,
                balanceAfter,
                occurredAt,
                outcome,
                rejectionReason,
                null,
                List.of());
    }
}
