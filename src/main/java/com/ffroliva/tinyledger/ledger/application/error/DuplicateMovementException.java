package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;
import java.util.UUID;

/** §6.3: the store lost a race on the global movementUid uniqueness constraint. */
public class DuplicateMovementException extends TinyLedgerException {
    private final UUID movementUid;

    public DuplicateMovementException(UUID movementUid) {
        super(ErrorCode.IDEMPOTENCY_CONFLICT, "duplicate movementUid: " + movementUid, movementUid);
        this.movementUid = movementUid;
    }

    public UUID movementUid() {
        return movementUid;
    }
}
