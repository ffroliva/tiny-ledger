package com.flaviooliva.ledger.ledger.domain;

import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record MoneyDeposited(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        Money amount,
        String reference,
        Money balanceAfter)
        implements LedgerEvent {}
