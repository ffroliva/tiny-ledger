package com.flaviooliva.ledger.shared;

public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String expected, String actual) {
        super("currency mismatch: expected %s, got %s".formatted(expected, actual));
    }
}
