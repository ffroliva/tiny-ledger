package com.ffroliva.tinyledger.notification;

import static org.assertj.core.api.Assertions.*;

import com.ffroliva.tinyledger.ledger.domain.*;
import com.ffroliva.tinyledger.notification.application.Notification;
import com.ffroliva.tinyledger.notification.application.NotificationRules;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec §3/P8: large-movement and rejection notification rules — plain class, no Spring, no mocks. */
class NotificationRulesTest {
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Instant T0 = Instant.parse("2026-08-03T12:00:00Z");
    private static final long THRESHOLD_MINOR_UNITS = 1_000_000; // 10 000.00

    private final NotificationRules rules = new NotificationRules(THRESHOLD_MINOR_UNITS);
    private final AccountId account = AccountId.random();

    @Test
    void depositAboveThresholdProducesLargeMovementNotification() {
        UUID movementUid = UUID.randomUUID();
        MoneyDeposited event = new MoneyDeposited(
                account, 2, T0, movementUid, new Money(GBP, 1_500_000), "ref", new Money(GBP, 1_500_000), null);

        Notification notification = rules.evaluate(event).orElseThrow();

        assertThat(notification.movementUid()).isEqualTo(movementUid);
        assertThat(notification.accountId()).isEqualTo(account);
        assertThat(notification.kind()).isEqualTo("LARGE_MOVEMENT");
        assertThat(notification.amount()).isEqualTo(new Money(GBP, 1_500_000));
        assertThat(notification.at()).isEqualTo(T0);
    }

    @Test
    void depositBelowThresholdProducesNoNotification() {
        MoneyDeposited event = new MoneyDeposited(
                account, 2, T0, UUID.randomUUID(), new Money(GBP, 2_000), "ref", new Money(GBP, 2_000), null);

        assertThat(rules.evaluate(event)).isEmpty();
    }

    @Test // spec §3: "single movement >= a configurable threshold" — boundary is inclusive
    void depositExactlyAtThresholdProducesLargeMovementNotification() {
        MoneyDeposited event = new MoneyDeposited(
                account,
                2,
                T0,
                UUID.randomUUID(),
                new Money(GBP, THRESHOLD_MINOR_UNITS),
                "ref",
                new Money(GBP, THRESHOLD_MINOR_UNITS),
                null);

        assertThat(rules.evaluate(event)).isPresent();
    }

    @Test // spec §3: "single movement" is not deposit-only
    void withdrawalAboveThresholdProducesLargeMovementNotification() {
        MoneyWithdrawn event = new MoneyWithdrawn(
                account, 2, T0, UUID.randomUUID(), new Money(GBP, 1_500_000), "ref", new Money(GBP, 0), null);

        assertThat(rules.evaluate(event)).isPresent();
        assertThat(rules.evaluate(event).orElseThrow().kind()).isEqualTo("LARGE_MOVEMENT");
    }

    @Test
    void everyMovementRejectedProducesRejectionNotificationRegardlessOfAmount() {
        UUID movementUid = UUID.randomUUID();
        MovementRejected event = new MovementRejected(
                account, 2, T0, movementUid, MovementType.WITHDRAWAL, new Money(GBP, 500), "insufficient-funds", null);

        Notification notification = rules.evaluate(event).orElseThrow();

        assertThat(notification.kind()).isEqualTo("MOVEMENT_REJECTED");
        assertThat(notification.movementUid()).isEqualTo(movementUid);
        assertThat(notification.accountId()).isEqualTo(account);
        assertThat(notification.amount()).isEqualTo(new Money(GBP, 500));
    }

    @Test
    void accountOpenedProducesNoNotification() {
        AccountOpened event = new AccountOpened(account, 1, T0, "alice", "ACC-alice", GBP, null);

        assertThat(rules.evaluate(event)).isEmpty();
    }
}
