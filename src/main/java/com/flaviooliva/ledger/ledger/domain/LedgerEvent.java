package com.flaviooliva.ledger.ledger.domain;

import com.flaviooliva.ledger.shared.AccountId;
import java.time.Instant;

public sealed interface LedgerEvent permits AccountOpened, MoneyDeposited, MoneyWithdrawn, MovementRejected {
    AccountId accountId();

    long version();

    Instant occurredAt();
}
