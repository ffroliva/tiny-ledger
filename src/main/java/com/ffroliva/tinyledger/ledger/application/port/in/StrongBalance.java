package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;

public record StrongBalance(AccountId accountId, Money amount, Instant asOf, long streamVersion) {}
