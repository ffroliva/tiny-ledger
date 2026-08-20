package com.ffroliva.tinyledger.ledger.domain;

/**
 * What kind of thing a {@link Quantity} counts (spec §2.5).
 *
 * <p>{@code CURRENCY} is a member on purpose: cash is a position like any other, so a multi-asset book
 * needs no second container for it and a portfolio total is one fold rather than two.
 *
 * <p>The class is part of an asset's identity rather than a label beside it — see
 * {@code Quantity#requireSameAsset}. Tickers are reused across instrument types, and netting an equity
 * position against a bond position because both are called "AGG" is the kind of error that shows up as a
 * reconciliation break weeks later.
 */
public enum AssetClass {
    CURRENCY,
    EQUITY_ETF,
    BOND_ETF
}
