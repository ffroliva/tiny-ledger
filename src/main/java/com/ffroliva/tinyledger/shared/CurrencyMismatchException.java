package com.ffroliva.tinyledger.shared;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;

public class CurrencyMismatchException extends TinyLedgerException {
    public CurrencyMismatchException(String expected, String actual) {
        super(
                ErrorCode.CURRENCY_MISMATCH,
                "currency mismatch: expected %s, got %s".formatted(expected, actual),
                expected,
                actual);
    }
}
