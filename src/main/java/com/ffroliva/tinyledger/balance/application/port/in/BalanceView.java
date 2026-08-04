package com.ffroliva.tinyledger.balance.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;

/** {@code asOf}/{@code streamVersion} are the staleness markers of the eventually-consistent read (§4.4). */
public record BalanceView(AccountId accountId, Money amount, Instant asOf, long streamVersion) {}
