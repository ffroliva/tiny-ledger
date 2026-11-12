package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssetTransferred(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        Quantity quantity,
        Money costBasis,
        List<TaxLot> taxLots,
        TaxLotSelector selector,
        String reference,
        Money balanceAfter,
        String actor)
        implements MovementEvent {

    public AssetTransferred {
        taxLots = taxLots == null ? null : List.copyOf(taxLots); // own copy; legacy payloads carry no taxLots
    }

    /** Stored in {@code events.event_type}. Data, not a name — see {@link LedgerEvent#eventType()}. */
    public static final String TYPE = "AssetTransferred";

    @Override
    public String eventType() {
        return TYPE;
    }
}
