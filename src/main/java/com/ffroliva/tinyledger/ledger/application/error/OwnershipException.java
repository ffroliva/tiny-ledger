package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;

/** §6.4: the caller is not the owner of the stream it addressed. */
public class OwnershipException extends TinyLedgerException {
    private final String caller;
    private final AccountId accountId;

    public OwnershipException(String caller, AccountId accountId) {
        super(
                ErrorCode.FORBIDDEN,
                "caller %s does not own account %s".formatted(caller, accountId.value()),
                caller,
                accountId.value());
        this.caller = caller;
        this.accountId = accountId;
    }

    public String caller() {
        return caller;
    }

    public AccountId accountId() {
        return accountId;
    }
}
