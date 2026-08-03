package com.flaviooliva.ledger.ledger.application.port.out;

import com.flaviooliva.ledger.ledger.application.error.ConcurrencyConflictException;
import com.flaviooliva.ledger.ledger.application.error.DuplicateMovementException;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.shared.AccountId;
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
