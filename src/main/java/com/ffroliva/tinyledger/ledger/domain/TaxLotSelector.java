package com.ffroliva.tinyledger.ledger.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which lots a disposal consumes, and in what order (spec §2.5). A pure function of the lots it is
 * handed: it mutates nothing, and {@link TaxLotAggregate} is what applies the answer.
 *
 * <p>An enum rather than an interface with two implementations: the method <em>is</em> the strategy, the
 * set is closed, and it has to survive a round-trip through configuration or an API field as a name.
 *
 * <p>Both orderings break ties on {@code lotId}. Without that, two lots bought in the same instant — or
 * at the same price — would consume in whatever order the list happened to hold them, and the same
 * disposal would produce a different cost basis on a replay.
 */
public enum TaxLotSelector {
    /** Oldest first. The default method for most tax regimes, and the one that ages a holding fastest. */
    FIFO(Comparator.comparing(TaxLot::acquiredAt).thenComparing(TaxLot::lotId)),

    /** Highest cost per unit first, which realises the smallest gain — hence the smallest tax — on a sale. */
    HIFO(Comparator.comparing(TaxLot::unitCost, Comparator.reverseOrder()).thenComparing(TaxLot::lotId));

    private final Comparator<TaxLot> ordering;

    TaxLotSelector(Comparator<TaxLot> ordering) {
        this.ordering = ordering;
    }

    /**
     * @param lots the holding, in any order; left untouched
     * @param quantity how much to dispose of; strictly positive and no more than the holding
     * @return what leaves the book and what stays in it — together, exactly {@code lots}
     */
    public Selection select(List<TaxLot> lots, Quantity quantity) {
        if (!quantity.isPositive()) throw new IllegalArgumentException("a disposal must be positive");
        Quantity zero = Quantity.zero(quantity.symbol(), quantity.assetClass());
        // Folding through Quantity::plus is also the asset check: a lot of another asset fails here.
        Quantity held = lots.stream().map(TaxLot::remaining).reduce(zero, Quantity::plus);
        if (held.microUnits() < quantity.microUnits()) {
            throw new IllegalArgumentException("insufficient holding: %s available, %s requested"
                    .formatted(held.toDecimal(), quantity.toDecimal()));
        }

        List<TaxLot> consumed = new ArrayList<>();
        List<TaxLot> remaining = new ArrayList<>();
        Quantity outstanding = quantity;
        for (TaxLot lot : lots.stream().sorted(ordering).toList()) {
            if (!outstanding.isPositive()) {
                remaining.add(lot);
            } else if (lot.remaining().microUnits() <= outstanding.microUnits()) {
                consumed.add(lot);
                outstanding = outstanding.minus(lot.remaining());
            } else {
                TaxLot.Split split = lot.split(outstanding);
                consumed.add(split.taken());
                remaining.add(split.left());
                outstanding = zero;
            }
        }
        return new Selection(List.copyOf(consumed), List.copyOf(remaining));
    }

    /**
     * The two sides of a disposal. {@code consumed} is in consumption order — the first element is the lot
     * the method reached for first — and carries the cost basis of what was sold.
     */
    public record Selection(List<TaxLot> consumed, List<TaxLot> remaining) {}
}
