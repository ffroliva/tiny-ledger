package com.flaviooliva.ledger.ledger.application.port.in;

import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.time.Instant;

public record StrongBalance(AccountId accountId, Money amount, Instant asOf, long streamVersion) {}
