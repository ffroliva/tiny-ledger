package com.ffroliva.tinyledger.ledger.domain;

import static com.ffroliva.tinyledger.ledger.domain.AssetClass.*;
import static org.assertj.core.api.Assertions.*;

import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tax-lot aggregate (spec §2.5). A position is not one number: it is the lots it was built from, each
 * with its own acquisition date and its own cost basis, because <em>which</em> lot a disposal consumes
 * decides the tax. FIFO takes the oldest, HIFO the most expensive; the same sale of the same asset from the
 * same holding produces a different cost basis under each.
 *
 * <p>Double entry is the invariant these tests exist to pin: a disposal never creates or destroys quantity
 * or cost basis, it only moves them out of the book. Every test here that disposes asserts both halves of
 * that ledger.
 */
class TaxLotAggregateTest {
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant JAN = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-15T10:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-15T10:00:00Z");

    private static Quantity voo(String amount) {
        return Quantity.of("VOO", EQUITY_ETF, amount);
    }

    private static Money usd(long minorUnits) {
        return new Money(USD, minorUnits);
    }

    /** A lot of {@code amount} VOO whose whole cost basis is {@code costMinorUnits}. */
    private static TaxLot lot(String lotId, String amount, long costMinorUnits, Instant acquiredAt) {
        return new TaxLot(lotId, voo(amount), usd(costMinorUnits), acquiredAt);
    }

    private static TaxLotAggregate emptyBook() {
        return TaxLotAggregate.of("VOO", EQUITY_ETF, USD);
    }

    /**
     * Three lots at $400, $600 and $500 a share. Every selection test below uses this book, so the two
     * methods are compared on identical holdings; the only variable is which lot each one reaches for.
     */
    private static TaxLotAggregate bookOfThree() {
        TaxLotAggregate book = emptyBook();
        book.acquire(lot("L1", "1.000000", 40_000, JAN));
        book.acquire(lot("L2", "1.000000", 60_000, FEB));
        book.acquire(lot("L3", "1.000000", 50_000, MAR));
        return book;
    }

    @Test
    void anEmptyBookHoldsNothingAndHasNoCostBasis() {
        TaxLotAggregate book = emptyBook();

        assertThat(book.quantity()).isEqualTo(Quantity.zero("VOO", EQUITY_ETF));
        assertThat(book.costBasis()).isEqualTo(usd(0));
        assertThat(book.lots()).isEmpty();
    }

    @Test
    void acquiringAccumulatesQuantityAndCostBasis() {
        TaxLotAggregate book = bookOfThree();

        assertThat(book.quantity()).isEqualTo(voo("3.000000"));
        assertThat(book.costBasis()).isEqualTo(usd(150_000));
    }

    /** HIFO reaches for the most expensive lot first: L2 at $600, neither the oldest nor the newest. */
    @Test
    void hifoConsumesTheHighestCostLotFirst() {
        TaxLotAggregate book = bookOfThree();

        List<TaxLot> consumed = book.dispose(voo("1.000000"), TaxLotSelector.HIFO);

        assertThat(consumed).singleElement().satisfies(slice -> {
            assertThat(slice.lotId()).isEqualTo("L2");
            assertThat(slice.costBasis()).isEqualTo(usd(60_000));
        });
        assertThat(book.lots()).extracting(TaxLot::lotId).containsExactlyInAnyOrder("L1", "L3");
    }

    /** FIFO reaches for the oldest lot, L1 acquired in January, on the very same book. */
    @Test
    void fifoConsumesTheOldestLotFirst() {
        TaxLotAggregate book = bookOfThree();

        List<TaxLot> consumed = book.dispose(voo("1.000000"), TaxLotSelector.FIFO);

        assertThat(consumed).singleElement().satisfies(slice -> {
            assertThat(slice.lotId()).isEqualTo("L1");
            assertThat(slice.costBasis()).isEqualTo(usd(40_000));
        });
        assertThat(book.lots()).extracting(TaxLot::lotId).containsExactlyInAnyOrder("L2", "L3");
    }

    /**
     * The whole point of choosing a method: the same sale off the same holding yields a different cost
     * basis, and therefore a different taxable gain. A test that only checked lot ids would still pass if
     * the basis were mis-attributed.
     */
    @Test
    void theTwoMethodsYieldDifferentCostBasisForTheSameSale() {
        Money hifoBasis = bookOfThree()
                .dispose(voo("1.000000"), TaxLotSelector.HIFO)
                .getFirst()
                .costBasis();
        Money fifoBasis = bookOfThree()
                .dispose(voo("1.000000"), TaxLotSelector.FIFO)
                .getFirst()
                .costBasis();

        assertThat(hifoBasis).isEqualTo(usd(60_000));
        assertThat(fifoBasis).isEqualTo(usd(40_000));
        assertThat(hifoBasis).isNotEqualTo(fifoBasis);
    }

    /** HIFO ranks by cost per share, not by what a lot cost in total; a big cheap lot must not outrank it. */
    @Test
    void hifoRanksByUnitCostNotByTotalLotCost() {
        TaxLotAggregate book = emptyBook();
        book.acquire(lot("BULK", "10.000000", 400_000, JAN)); // $400/share, $4000 total
        book.acquire(lot("PRICEY", "1.000000", 60_000, FEB)); // $600/share, $600 total

        List<TaxLot> consumed = book.dispose(voo("1.000000"), TaxLotSelector.HIFO);

        assertThat(consumed).singleElement().extracting(TaxLot::lotId).isEqualTo("PRICEY");
    }

    /**
     * A fractional disposal that outruns one lot and lands mid-way through the next. The last lot touched
     * is <em>split</em>: part leaves the book, the rest stays with a prorated basis and the same lot id,
     * because a split lot is still the same lot.
     */
    @Test
    void aDisposalSpansLotsAndSplitsTheLastOneItTouches() {
        TaxLotAggregate book = emptyBook();
        book.acquire(lot("L1", "10.500000", 420_000, JAN)); // $400/share
        book.acquire(lot("L2", "4.000000", 240_000, FEB)); // $600/share

        List<TaxLot> consumed = book.dispose(voo("6.500000"), TaxLotSelector.HIFO);

        assertThat(consumed).extracting(TaxLot::lotId).containsExactly("L2", "L1");
        assertThat(consumed.get(0).remaining()).isEqualTo(voo("4.000000"));
        assertThat(consumed.get(0).costBasis()).isEqualTo(usd(240_000));
        assertThat(consumed.get(1).remaining()).isEqualTo(voo("2.500000"));
        assertThat(consumed.get(1).costBasis()).isEqualTo(usd(100_000)); // 2.5 x $400
        assertThat(book.lots()).singleElement().satisfies(left -> {
            assertThat(left.lotId()).isEqualTo("L1");
            assertThat(left.remaining()).isEqualTo(voo("8.000000"));
            assertThat(left.costBasis()).isEqualTo(usd(320_000));
        });
    }

    /**
     * Double entry, stated as an equation over a deliberately indivisible basis: what left the book plus
     * what stayed equals what was there before, in both quantity and cost basis. This is the assertion
     * that catches a proration rounding each side independently and losing a minor unit in the middle.
     */
    @Test
    void aDisposalConservesBothQuantityAndCostBasis() {
        TaxLotAggregate book = emptyBook();
        book.acquire(lot("L1", "3.000000", 100_000, JAN)); // $333.33/share, not divisible
        book.acquire(lot("L2", "7.000000", 250_000, FEB));
        Quantity before = book.quantity();
        Money basisBefore = book.costBasis();

        List<TaxLot> consumed = book.dispose(voo("4.333333"), TaxLotSelector.HIFO);

        Quantity consumedQuantity =
                consumed.stream().map(TaxLot::remaining).reduce(Quantity.zero("VOO", EQUITY_ETF), Quantity::plus);
        Money consumedBasis = consumed.stream().map(TaxLot::costBasis).reduce(usd(0), Money::plus);
        assertThat(consumedQuantity).isEqualTo(voo("4.333333"));
        assertThat(consumedQuantity.plus(book.quantity())).isEqualTo(before);
        assertThat(consumedBasis.plus(book.costBasis())).isEqualTo(basisBefore);
    }

    /** Disposing the entire holding empties the book rather than leaving a zero-quantity lot behind. */
    @Test
    void disposingEverythingEmptiesTheBook() {
        TaxLotAggregate book = bookOfThree();

        List<TaxLot> consumed = book.dispose(voo("3.000000"), TaxLotSelector.FIFO);

        assertThat(consumed).extracting(TaxLot::lotId).containsExactly("L1", "L2", "L3");
        assertThat(book.lots()).isEmpty();
        assertThat(book.quantity()).isEqualTo(Quantity.zero("VOO", EQUITY_ETF));
        assertThat(book.costBasis()).isEqualTo(usd(0));
    }

    /** A book cannot go short: a disposal larger than the holding is refused and the book is left untouched. */
    @Test
    void refusesToDisposeMoreThanIsHeld() {
        TaxLotAggregate book = bookOfThree();

        assertThatThrownBy(() -> book.dispose(voo("3.000001"), TaxLotSelector.HIFO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient");
        assertThat(book.quantity()).isEqualTo(voo("3.000000"));
        assertThat(book.lots()).hasSize(3);
    }

    @Test
    void refusesANonPositiveDisposal() {
        TaxLotAggregate book = bookOfThree();

        assertThatThrownBy(() -> book.dispose(Quantity.zero("VOO", EQUITY_ETF), TaxLotSelector.HIFO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> book.dispose(voo("-1.000000"), TaxLotSelector.HIFO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** One book, one asset. A bond lot in an equity book would net two positions into one number. */
    @Test
    void refusesALotOfADifferentAsset() {
        TaxLotAggregate book = emptyBook();
        TaxLot bond = new TaxLot("B1", Quantity.of("BND", BOND_ETF, "1.000000"), usd(7_000), JAN);

        assertThatThrownBy(() -> book.acquire(bond)).isInstanceOf(IllegalArgumentException.class);
    }

    /** A second currency is not addable, so it is refused where it enters rather than where it is summed. */
    @Test
    void refusesALotWhoseCostBasisIsInAnotherCurrency() {
        TaxLotAggregate book = emptyBook();
        TaxLot inEuros = new TaxLot("E1", voo("1.000000"), Money.of("EUR", 40_000), JAN);

        assertThatThrownBy(() -> book.acquire(inEuros)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesADisposalOfADifferentAsset() {
        TaxLotAggregate book = bookOfThree();

        assertThatThrownBy(() -> book.dispose(Quantity.of("BND", BOND_ETF, "1.000000"), TaxLotSelector.HIFO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesALotWithoutAPositiveQuantity() {
        assertThatThrownBy(() -> lot("L0", "0.000000", 0, JAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lot("L0", "-1.000000", 40_000, JAN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesALotWithANegativeCostBasis() {
        assertThatThrownBy(() -> lot("L0", "1.000000", -1, JAN)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Two lots acquired in the same instant still consume in a fixed order, so a disposal is reproducible. */
    @Test
    void breaksTiesDeterministicallyByLotId() {
        TaxLotAggregate sameInstant = emptyBook();
        sameInstant.acquire(lot("B", "1.000000", 40_000, JAN));
        sameInstant.acquire(lot("A", "1.000000", 40_000, JAN));

        TaxLotAggregate samePrice = emptyBook();
        samePrice.acquire(lot("B", "1.000000", 40_000, MAR));
        samePrice.acquire(lot("A", "1.000000", 40_000, JAN));

        assertThat(sameInstant
                        .dispose(voo("1.000000"), TaxLotSelector.FIFO)
                        .getFirst()
                        .lotId())
                .isEqualTo("A");
        assertThat(samePrice
                        .dispose(voo("1.000000"), TaxLotSelector.HIFO)
                        .getFirst()
                        .lotId())
                .isEqualTo("A");
    }

    /** The selector is a pure function of the lots it is handed; it does not touch the list it is given. */
    @Test
    void theSelectorLeavesItsInputUntouched() {
        List<TaxLot> lots = List.of(lot("L1", "1.000000", 40_000, JAN), lot("L2", "1.000000", 60_000, FEB));

        TaxLotSelector.Selection selection = TaxLotSelector.HIFO.select(lots, voo("0.500000"));

        assertThat(lots).extracting(TaxLot::remaining).containsExactly(voo("1.000000"), voo("1.000000"));
        assertThat(selection.consumed())
                .singleElement()
                .extracting(TaxLot::lotId)
                .isEqualTo("L2");
        assertThat(selection.remaining()).hasSize(2);
    }
}
