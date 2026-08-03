package com.flaviooliva.ledger.ledger.application.error;

import java.util.UUID;

/** §6.3: the store lost a race on the global movementUid uniqueness constraint. */
public class DuplicateMovementException extends RuntimeException {
    private final UUID movementUid;

    public DuplicateMovementException(UUID movementUid) {
        super("duplicate movementUid: " + movementUid);
        this.movementUid = movementUid;
    }

    public UUID movementUid() {
        return movementUid;
    }
}
