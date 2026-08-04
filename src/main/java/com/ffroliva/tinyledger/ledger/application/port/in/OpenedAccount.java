package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;

/** {@code createdAt} is the recorded {@code AccountOpened} time — §7's {@code Account} response needs it. */
public record OpenedAccount(AccountId accountId, long version, Instant createdAt) {}
