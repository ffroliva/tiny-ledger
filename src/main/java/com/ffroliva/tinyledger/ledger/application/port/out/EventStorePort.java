package com.ffroliva.tinyledger.ledger.application.port.out;

import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.error.DuplicateMovementException;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.ledger.domain.MovementEvent;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventStorePort {
    void append(AccountId streamId, long expectedVersion, List<? extends LedgerEvent> events)
            throws ConcurrencyConflictException, DuplicateMovementException;

    /** Empty list = unknown account. */
    List<LedgerEvent> read(AccountId streamId);

    /**
     * One page of the whole log, across every stream, in append order — the read a projection
     * rebuild needs and {@link #read(AccountId)} cannot express.
     *
     * <p>Paged rather than returning everything: the log only grows, and the operation that exists
     * to rebuild a projection must not need the entire history resident to do it.
     *
     * <p><strong>Bounded to offline use, and this is not a detail.</strong> The Postgres cursor is
     * {@code global_index}, a {@code BIGSERIAL} allocated at insert and made visible at commit — so
     * a transaction holding a lower index can commit after one holding a higher index. A reader
     * that has already advanced past the higher index will never see the lower one. That is
     * harmless for a rebuild over a quiesced or accepted-boundary log, and it is silent data loss
     * if this is used to drive a live subscription. Use it for rebuild and backfill; do not build a
     * change feed on it without first solving commit-order visibility.
     *
     * @param fromGlobalIndex exclusive lower bound; pass {@code 0} to start at the beginning, then
     *     pass back {@link EventPage#nextCursor()} verbatim
     * @param limit maximum events in the page
     */
    EventPage readAll(long fromGlobalIndex, int limit);

    /** Global lookup across all streams (§6.3). */
    Optional<MovementEvent> findByMovementUid(UUID movementUid);
}
