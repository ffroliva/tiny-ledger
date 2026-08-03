package com.flaviooliva.ledger.shared;

import java.util.Objects;
import java.util.UUID;

public record AccountId(UUID value) {
    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    public static AccountId random() {
        return new AccountId(UUID.randomUUID());
    }

    public static AccountId of(String uuid) {
        return new AccountId(UUID.fromString(uuid));
    }
}
