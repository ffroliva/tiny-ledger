package com.flaviooliva.ledger.balance.application.port.in;

import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.time.Instant;

/** {@code asOf}/{@code streamVersion} are the staleness markers of the eventually-consistent read (§4.4). */
public record BalanceView(AccountId accountId, Money amount, Instant asOf, long streamVersion) {}
