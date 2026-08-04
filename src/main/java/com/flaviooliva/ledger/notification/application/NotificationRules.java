package com.flaviooliva.ledger.notification.application;

import com.flaviooliva.ledger.ledger.domain.AccountOpened;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.ledger.domain.MoneyDeposited;
import com.flaviooliva.ledger.ledger.domain.MoneyWithdrawn;
import com.flaviooliva.ledger.ledger.domain.MovementRejected;
import com.flaviooliva.ledger.shared.Money;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec §3/P8: plain class, no Spring — a single movement at or above the threshold is
 * {@code LARGE_MOVEMENT}; every rejection is {@code MOVEMENT_REJECTED} regardless of size;
 * {@code AccountOpened} never notifies.
 */
public class NotificationRules {
    private final long largeMovementThresholdMinorUnits;

    public NotificationRules(long largeMovementThresholdMinorUnits) {
        this.largeMovementThresholdMinorUnits = largeMovementThresholdMinorUnits;
    }

    public Optional<Notification> evaluate(LedgerEvent event) {
        return switch (event) {
            case MovementRejected r -> Optional.of(notify(r.movementUid(), r, "MOVEMENT_REJECTED", r.amount()));
            case MoneyDeposited d
            when isLarge(d.amount().minorUnits()) ->
                Optional.of(notify(d.movementUid(), d, "LARGE_MOVEMENT", d.amount()));
            case MoneyWithdrawn w
            when isLarge(w.amount().minorUnits()) ->
                Optional.of(notify(w.movementUid(), w, "LARGE_MOVEMENT", w.amount()));
            case AccountOpened a -> Optional.empty();
            default -> Optional.empty();
        };
    }

    private boolean isLarge(long minorUnits) {
        return minorUnits >= largeMovementThresholdMinorUnits;
    }

    private static Notification notify(UUID movementUid, LedgerEvent event, String kind, Money amount) {
        return new Notification(movementUid, event.accountId(), kind, amount, event.occurredAt());
    }
}
