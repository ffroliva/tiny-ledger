package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;
import java.util.Currency;

public record AccountOpened(
        AccountId accountId, long version, Instant occurredAt, String owner, String name, Currency currency)
        implements LedgerEvent {

    // §15.8: an account has no owner to act on behalf of until it exists — actor is always the owner,
    // never a separate fact, so this is a derivation, not a fourth stamped field.
    @Override
    public String actor() {
        return owner;
    }
}
