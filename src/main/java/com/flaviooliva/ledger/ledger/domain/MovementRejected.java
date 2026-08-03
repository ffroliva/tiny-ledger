package com.flaviooliva.ledger.ledger.domain;

import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record MovementRejected(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        MovementType type,
        Money amount,
        String reason)
        implements LedgerEvent {}
