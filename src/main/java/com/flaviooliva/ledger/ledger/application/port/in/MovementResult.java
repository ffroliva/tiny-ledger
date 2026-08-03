package com.flaviooliva.ledger.ledger.application.port.in;

import com.flaviooliva.ledger.ledger.domain.MovementType;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.time.Instant;
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
        String rejectionReason) {}
