package com.flaviooliva.ledger.ledger.application.error;

import java.util.UUID;

/** §6.3: the movementUid is already bound to a different payload or a different stream. */
public class IdempotencyConflictException extends RuntimeException {
    private final UUID movementUid;

    public IdempotencyConflictException(UUID movementUid) {
        super("movementUid %s is already bound to a different movement".formatted(movementUid));
        this.movementUid = movementUid;
    }

    public UUID movementUid() {
        return movementUid;
    }
}
