package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.util.UUID;

/** §2.4/§6.4: see {@link Deposit} — same shape, same reason for `callerIsAdmin`. */
public record Withdraw(
        String caller, boolean callerIsAdmin, AccountId accountId, UUID movementUid, Money amount, String reference) {}
