package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;

public sealed interface LedgerEvent permits AccountOpened, MovementEvent {
    AccountId accountId();

    long version();

    Instant occurredAt();

    /**
     * §2.3/§2.4: the principal that issued the command. On the three movement events it is a record
     * component, stamped by the use case (Account.deposit/withdraw) from the command's caller — never
     * from the account's owner. On {@link AccountOpened} it is derived from {@code owner()}: an account
     * has no owner to act on behalf of until it exists (§15.8). A legacy payload written before this
     * field existed deserialises with it absent (null) rather than failing to read at all — §15.9
     * records how that absence is interpreted.
     */
    String actor();
}
