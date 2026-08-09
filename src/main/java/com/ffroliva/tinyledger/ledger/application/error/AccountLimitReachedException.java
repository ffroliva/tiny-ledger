package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;

/**
 * §6.5: the caller already holds every account the bank lets them self-open.
 *
 * <p>A {@code 409} rather than a {@code 403}: the caller's roles are correct and nothing about the
 * request is malformed — it conflicts with state they can resolve, by closing an account or asking
 * the bank. It carries the limit and not the owner, because the owner is the caller.
 */
public class AccountLimitReachedException extends TinyLedgerException {

    private final int limit;

    public AccountLimitReachedException(int limit) {
        super(ErrorCode.ACCOUNT_LIMIT_REACHED, "owner already holds the maximum of " + limit + " accounts", limit);
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
