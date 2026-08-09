package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;

/** §6.5: the caller already holds every account the bank lets them self-open. */
public class AccountLimitReachedException extends TinyLedgerException {

    public AccountLimitReachedException(int limit) {
        super(ErrorCode.ACCOUNT_LIMIT_REACHED, "owner already holds the maximum of " + limit + " accounts", limit);
    }
}
