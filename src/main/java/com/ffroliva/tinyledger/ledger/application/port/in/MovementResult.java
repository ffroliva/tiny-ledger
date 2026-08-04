package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
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
