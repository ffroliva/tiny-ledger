package com.ffroliva.tinyledger.shared.error;

/** §6.5: the 400 — a movement amount that is zero, negative, or otherwise not a usable amount. */
public class InvalidAmountException extends TinyLedgerException {
    public InvalidAmountException(String message) {
        super(ErrorCode.INVALID_AMOUNT, message);
    }
}
