package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * One acquisition of an asset, and what is left of it (spec §2.5).
 *
 * <p>{@code costBasis} is the cost of the quantity <em>still held</em>, not of the original purchase — a
 * lot shrinks as it is disposed of, and both halves shrink together. That is what makes {@link #split}
 * exact: the taken slice is rounded and the remainder is whatever is left over, so the two always sum
 * back to the whole and a disposal can never mint or destroy a minor unit.
 *
 * @param lotId the acquisition's identity, and stable across a split — a split lot is still the same lot
 * @param remaining what is still held; strictly positive, so a spent lot leaves the book rather than
 *     lingering at zero
 * @param costBasis the cost of {@code remaining}
 * @param acquiredAt the acquisition instant; FIFO's ordering key and the holding-period clock
 */
public record TaxLot(String lotId, Quantity remaining, Money costBasis, Instant acquiredAt) {

    public TaxLot {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(costBasis, "costBasis");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        if (!remaining.isPositive()) throw new IllegalArgumentException("a tax lot holds a positive quantity");
        if (costBasis.isNegative()) throw new IllegalArgumentException("cost basis cannot be negative");
    }

    /**
     * Cost per whole unit, in the cost currency's minor units — HIFO's ranking key.
     *
     * <p>Ranked per unit rather than per lot on purpose: a large cheap lot costs more in total than a
     * small expensive one, and a total-cost ranking would hand HIFO the wrong lot every time the sizes
     * differ. Twelve places is comparison precision only; nothing is stored at this scale.
     */
    public BigDecimal unitCost() {
        return BigDecimal.valueOf(costBasis.minorUnits()).divide(remaining.toDecimal(), 12, RoundingMode.HALF_UP);
    }

    /**
     * Splits {@code take} off the front of this lot. The taken slice's basis is prorated and rounded; the
     * remainder is the subtraction, so rounding lands on one side only and the pair conserves exactly.
     * Callers guarantee {@code 0 < take < remaining} — {@link TaxLotSelector} is the only one.
     */
    Split split(Quantity take) {
        long takenMinorUnits = BigDecimal.valueOf(costBasis.minorUnits())
                .multiply(take.toDecimal())
                .divide(remaining.toDecimal(), 0, RoundingMode.HALF_UP)
                .longValueExact();
        Money takenBasis = new Money(costBasis.currency(), takenMinorUnits);
        return new Split(
                new TaxLot(lotId, take, takenBasis, acquiredAt),
                new TaxLot(lotId, remaining.minus(take), costBasis.minus(takenBasis), acquiredAt));
    }

    /** The two halves of a split lot: what leaves the book and what stays in it. */
    record Split(TaxLot taken, TaxLot left) {}
}
