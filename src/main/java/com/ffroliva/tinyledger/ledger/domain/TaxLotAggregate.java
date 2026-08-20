package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The lots held in one asset, and the double-entry rule over them (spec §2.5).
 *
 * <p>A position is not one number. It is the acquisitions it was built from, because <em>which</em> lot a
 * disposal consumes decides the cost basis and therefore the tax — {@link TaxLotSelector} is where that
 * choice lives, and this aggregate is what applies it.
 *
 * <p><b>Double entry:</b> a disposal moves quantity and cost basis out of the book, it never creates or
 * destroys either. That holds by construction rather than by assertion: {@code select} partitions the
 * lots into two lists that together are exactly the lots it was given, and {@link TaxLot#split} rounds
 * only the taken slice so the remainder absorbs the rounding.
 *
 * <p>In-memory and single-threaded, like {@code Account}: this is a write-model aggregate, and the
 * concurrency story is the stream version above it, not a lock in here.
 */
public final class TaxLotAggregate {
    private final Quantity zero;
    private final Currency currency;
    private final List<TaxLot> lots = new ArrayList<>();

    private TaxLotAggregate(Quantity zero, Currency currency) {
        this.zero = zero;
        this.currency = currency;
    }

    /**
     * @param currency the cost-basis currency, fixed at construction so an empty book can still answer
     *     {@link #costBasis()} — and so a lot priced in another currency is refused where it enters
     *     rather than where it is summed
     */
    public static TaxLotAggregate of(String symbol, AssetClass assetClass, Currency currency) {
        return new TaxLotAggregate(Quantity.zero(symbol, assetClass), Objects.requireNonNull(currency, "currency"));
    }

    public void acquire(TaxLot lot) {
        zero.requireSameAsset(lot.remaining());
        if (!currency.equals(lot.costBasis().currency())) {
            throw new IllegalArgumentException("cost basis must be in %s, got %s"
                    .formatted(
                            currency.getCurrencyCode(),
                            lot.costBasis().currency().getCurrencyCode()));
        }
        lots.add(lot);
    }

    /**
     * Removes {@code quantity} from the book under {@code selector}.
     *
     * <p>The book is replaced only after the selection succeeds, so a refused disposal — one larger than
     * the holding, or of the wrong asset — leaves it exactly as it was.
     *
     * @return the consumed slices in consumption order, each carrying the cost basis of what was sold
     */
    public List<TaxLot> dispose(Quantity quantity, TaxLotSelector selector) {
        TaxLotSelector.Selection selection = selector.select(List.copyOf(lots), quantity);
        lots.clear();
        lots.addAll(selection.remaining());
        return selection.consumed();
    }

    /** The whole position: the lots folded into one number, which is the only place that number exists. */
    public Quantity quantity() {
        return lots.stream().map(TaxLot::remaining).reduce(zero, Quantity::plus);
    }

    public Money costBasis() {
        return lots.stream().map(TaxLot::costBasis).reduce(new Money(currency, 0), Money::plus);
    }

    public List<TaxLot> lots() {
        return List.copyOf(lots);
    }
}
