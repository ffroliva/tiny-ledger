package com.flaviooliva.ledger.notification.application;

import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** Spec §3: a produced notification record — {@code kind} is {@code LARGE_MOVEMENT} or {@code MOVEMENT_REJECTED}. */
public record Notification(UUID movementUid, AccountId accountId, String kind, Money amount, Instant at) {}
