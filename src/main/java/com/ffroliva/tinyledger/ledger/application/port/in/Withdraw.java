package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.util.UUID;

/** §2.4: caller = JWT subject or the fixed standalone principal. */
public record Withdraw(String caller, AccountId accountId, UUID movementUid, Money amount, String reference) {}
