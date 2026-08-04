package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;

public sealed interface LedgerEvent permits AccountOpened, MoneyDeposited, MoneyWithdrawn, MovementRejected {
    AccountId accountId();

    long version();

    Instant occurredAt();
}
