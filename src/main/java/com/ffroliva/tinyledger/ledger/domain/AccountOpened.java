package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;
import java.util.Currency;

public record AccountOpened(
        AccountId accountId, long version, Instant occurredAt, String owner, String name, Currency currency)
        implements LedgerEvent {}
