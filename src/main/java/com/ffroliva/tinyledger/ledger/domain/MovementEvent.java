package com.ffroliva.tinyledger.ledger.domain;

import java.util.UUID;

/**
 * The three events that carry a client-supplied {@code movementUid} (§6.3). {@code AccountOpened} does
 * not — account opening is server-uid'd and deliberately not client-idempotent (N22) — and that
 * asymmetry is the whole reason this type exists.
 *
 * <p><strong>It replaces an assertion with a proof.</strong> {@code EventStorePort.findByMovementUid}
 * can only ever return one of these: the stores key their UID index off exactly these three
 * ({@code InMemoryEventStore.uidOf}, and Postgres's {@code client_movement_uid} column, which is NULL
 * for an opening). That was true before, but it was true *by construction of two adapters* rather than
 * by the type — so {@code RecordMovementService.movementUidOf} needed a fourth switch arm that threw
 * {@code IllegalStateException} for a case it could not receive, which Sonar reported as bug S6416 and
 * which was, fairly, unprovable from the code alone.
 *
 * <p>Narrowing the port's return type moves that from a comment to the compiler. The switch is now
 * exhaustive over three arms, the impossible branch is gone rather than documented, and a future event
 * type carrying a movement UID has to say so by joining this interface.
 */
public sealed interface MovementEvent extends LedgerEvent
        permits MoneyDeposited, MoneyWithdrawn, AssetTransferred, MovementRejected {

    /** The client-supplied identity of the movement — its dedup key and its permanent name (§6.3). */
    UUID movementUid();
}
