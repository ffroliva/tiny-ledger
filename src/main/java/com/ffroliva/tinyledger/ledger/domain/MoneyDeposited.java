package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record MoneyDeposited(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        Money amount,
        String reference,
        Money balanceAfter,
        String actor)
        implements MovementEvent {}
