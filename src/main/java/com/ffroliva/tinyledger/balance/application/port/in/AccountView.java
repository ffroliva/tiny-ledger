package com.ffroliva.tinyledger.balance.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;
import java.util.Currency;

public record AccountView(AccountId accountId, String name, String owner, Currency currency, Instant createdAt) {}
