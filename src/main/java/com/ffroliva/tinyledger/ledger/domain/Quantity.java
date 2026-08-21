package com.ffroliva.tinyledger.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A signed amount of one asset, held as a {@code long} count of <b>micro-units</b> — six decimal places
 * (spec §2.5).
 *
 * <p>The sibling of {@code Money}, and deliberately not the same type. {@code Money} is anchored to a
 * {@code java.util.Currency}, which has no instance for a ticker and carries the wrong scale anyway: a
 * currency has two decimal places, a fractional share is quoted at six. Keeping them apart means the
 * compiler refuses to add 10.5 shares to $10.50, which no runtime check would have to catch.
 *
 * <p>Fixed-point, never {@code double} — the rule §2.1 already states for {@code Money}, and the reason
 * {@code +10.500000} and {@code -10.500000} cancel to exactly zero here.
 *
 * <p>Arithmetic overflow raises {@link ArithmeticException} rather than a §6.5 catalogued error, because
 * no asset symbol reaches this type from the wire yet: getting here is a bug in a caller, and AGENTS.md
 * reserves exceptions for exactly that. The same reasoning covers the asset-mismatch guard.
 *
 * @param symbol the instrument's ticker, e.g. {@code VOO} — or the ISO code when the class is
 *     {@link AssetClass#CURRENCY}
 * @param assetClass part of the asset's identity, not a label beside it
 * @param microUnits the signed amount, scaled by 10^6
 */
public record Quantity(String symbol, AssetClass assetClass, long microUnits) {

    /** Six decimal places: the precision brokers quote fractional shares at. */
    public static final int SCALE = 6;

    public Quantity {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(assetClass, "assetClass");
        if (symbol.isBlank()) throw new IllegalArgumentException("symbol must not be blank");
    }

    /**
     * Parses a decimal string. More than {@link #SCALE} decimal places is <b>refused, never rounded</b>:
     * silently truncating the seventh place is how a position drifts away from the broker's and is only
     * noticed at reconciliation.
     */
    public static Quantity of(String symbol, AssetClass assetClass, String decimalAmount) {
        BigDecimal parsed = new BigDecimal(decimalAmount);
        if (parsed.scale() > SCALE) {
            throw new IllegalArgumentException(
                    "quantity carries more than %d decimal places: %s".formatted(SCALE, decimalAmount));
        }
        return new Quantity(symbol, assetClass, parsed.movePointRight(SCALE).longValueExact());
    }

    public static Quantity zero(String symbol, AssetClass assetClass) {
        return new Quantity(symbol, assetClass, 0);
    }

    public Quantity plus(Quantity other) {
        requireSameAsset(other);
        return new Quantity(symbol, assetClass, Math.addExact(microUnits, other.microUnits));
    }

    public Quantity minus(Quantity other) {
        requireSameAsset(other);
        return new Quantity(symbol, assetClass, Math.subtractExact(microUnits, other.microUnits));
    }

    public Quantity negated() {
        return new Quantity(symbol, assetClass, Math.negateExact(microUnits));
    }

    /** The amount at scale {@link #SCALE}, so a rendered quantity always shows all six places. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(microUnits, SCALE);
    }

    public boolean isPositive() {
        return microUnits > 0;
    }

    public boolean isNegative() {
        return microUnits < 0;
    }

    public boolean isZero() {
        return microUnits == 0;
    }

    /**
     * Package-private and shared by every guard in this package: {@code TaxLotAggregate} calls it rather
     * than repeating the comparison, so the asset-identity rule has one implementation and one test.
     */
    void requireSameAsset(Quantity other) {
        if (!symbol.equals(other.symbol) || assetClass != other.assetClass) {
            throw new IllegalArgumentException(
                    "asset mismatch: %s %s vs %s %s".formatted(symbol, assetClass, other.symbol, other.assetClass));
        }
    }
}
