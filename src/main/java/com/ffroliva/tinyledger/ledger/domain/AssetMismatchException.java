package com.ffroliva.tinyledger.ledger.domain;

/**
 * Two quantities of different assets were combined — a different symbol or a different {@link AssetClass}
 * (spec §6.5). An {@link IllegalArgumentException} so callers that already catch that keep working.
 */
public class AssetMismatchException extends IllegalArgumentException {

    public AssetMismatchException(String message) {
        super(message);
    }
}
