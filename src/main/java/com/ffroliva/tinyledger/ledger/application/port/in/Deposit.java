package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.util.UUID;

/**
 * §2.4: caller = JWT subject or the fixed standalone principal. §6.4: {@code callerIsAdmin} is
 * whether that principal holds {@code ledger:admin} — the one fact
 * {@link com.ffroliva.tinyledger.ledger.application.usecase.RecordMovementService} needs to widen its
 * ownership check that it cannot otherwise see, since {@code application} carries no Spring Security
 * type (ArchUnit, §4.5).
 */
public record Deposit(
        String caller, boolean callerIsAdmin, AccountId accountId, UUID movementUid, Money amount, String reference) {}
