package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record MovementRejected(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        MovementType type,
        Money amount,
        String reason,
        String actor)
        implements LedgerEvent {}
