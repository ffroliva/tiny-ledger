package com.flaviooliva.ledger.ledger.application.port.in;

import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.util.UUID;

/** §2.4: caller = JWT subject or the fixed standalone principal. */
public record Withdraw(String caller, AccountId accountId, UUID movementUid, Money amount, String reference) {}
