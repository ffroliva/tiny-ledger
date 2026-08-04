package com.ffroliva.tinyledger.ledger.application.port.out;

import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.error.DuplicateMovementException;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventStorePort {
    void append(AccountId streamId, long expectedVersion, List<LedgerEvent> events)
            throws ConcurrencyConflictException, DuplicateMovementException;

    /** Empty list = unknown account. */
    List<LedgerEvent> read(AccountId streamId);

    /** Global lookup across all streams (§6.3). */
    Optional<LedgerEvent> findByMovementUid(UUID movementUid);
}
