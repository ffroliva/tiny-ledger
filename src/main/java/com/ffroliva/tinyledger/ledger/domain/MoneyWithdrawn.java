package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record MoneyWithdrawn(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        Money amount,
        String reference,
        Money balanceAfter)
        implements LedgerEvent {}
