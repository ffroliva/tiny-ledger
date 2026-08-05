package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;
import java.util.UUID;

/** §6.3: the movementUid is already bound to a different payload or a different stream. */
public class IdempotencyConflictException extends TinyLedgerException {
    private final UUID movementUid;

    public IdempotencyConflictException(UUID movementUid) {
        super(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                "movementUid %s is already bound to a different movement".formatted(movementUid),
                movementUid);
        this.movementUid = movementUid;
    }

    public UUID movementUid() {
        return movementUid;
    }
}
