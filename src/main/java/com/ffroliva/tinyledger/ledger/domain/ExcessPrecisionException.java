package com.ffroliva.tinyledger.ledger.domain;

/**
 * A quantity carried more than {@link Quantity#SCALE} decimal places (spec §6.5). Refused, never rounded.
 * An {@link IllegalArgumentException} so callers that already catch that keep working.
 */
public class ExcessPrecisionException extends IllegalArgumentException {

    public ExcessPrecisionException(String message) {
        super(message);
    }
}
