package com.ffroliva.tinyledger.notification.application;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** Spec §3: a produced notification record — {@code kind} is {@code LARGE_MOVEMENT} or {@code MOVEMENT_REJECTED}. */
public record Notification(UUID movementUid, AccountId accountId, String kind, Money amount, Instant at) {}
