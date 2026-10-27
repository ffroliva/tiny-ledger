package com.ffroliva.tinyledger.ledger.domain;

import static com.ffroliva.tinyledger.ledger.domain.AssetClass.EQUITY_ETF;
import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LIFO: newest lot first by acquiredAt, ties broken on lotId so a replay is reproducible. */
class TaxLotSelectorTest {
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant JAN = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-15T10:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-15T10:00:00Z");

    private static Quantity voo(String amount) {
        return Quantity.of("VOO", EQUITY_ETF, amount);
    }

    private static TaxLot lot(String lotId, String amount, long cost, Instant acquiredAt) {
        return new TaxLot(lotId, voo(amount), new Money(USD, cost), acquiredAt);
    }

    private static List<String> ids(List<TaxLot> lots) {
        return lots.stream().map(TaxLot::lotId).toList();
    }

    @Test
    void lifoConsumesTheNewestLotFirst() {
        List<TaxLot> lots = List.of(
                lot("L1", "1.000000", 40_000, JAN),
                lot("L3", "1.000000", 50_000, MAR),
                lot("L2", "1.000000", 60_000, FEB));

        TaxLotSelector.Selection selection = TaxLotSelector.LIFO.select(lots, voo("2.000000"));

        assertThat(ids(selection.consumed())).containsExactly("L3", "L2");
        assertThat(ids(selection.remaining())).containsExactly("L1");
    }

    @Test
    void lifoBreaksAnAcquiredAtTieOnLotIdRegardlessOfInputOrder() {
        TaxLot a = lot("A", "1.000000", 10_000, MAR);
        TaxLot b = lot("B", "1.000000", 20_000, MAR);

        var forward = TaxLotSelector.LIFO.select(List.of(a, b), voo("1.000000"));
        var reversed = TaxLotSelector.LIFO.select(List.of(b, a), voo("1.000000"));

        assertThat(forward).isEqualTo(reversed);
        assertThat(ids(forward.consumed())).containsExactly("A");
        assertThat(ids(forward.remaining())).containsExactly("B");
    }

    @Test
    void lifoSplitsTheLastConsumedLotAndConservesQuantityAndBasis() {
        List<TaxLot> lots = List.of(
                lot("L1", "1.000000", 40_000, JAN),
                lot("L2", "2.000000", 60_000, FEB),
                lot("L3", "1.000000", 50_000, MAR));

        TaxLotSelector.Selection selection = TaxLotSelector.LIFO.select(lots, voo("1.500000"));

        assertThat(ids(selection.consumed())).containsExactly("L3", "L2");
        TaxLot taken = selection.consumed().get(1);
        assertThat(taken.remaining()).isEqualTo(voo("0.500000"));
        assertThat(taken.costBasis()).isEqualTo(new Money(USD, 15_000));
        assertThat(ids(selection.remaining())).containsExactly("L2", "L1");
        TaxLot left = selection.remaining().get(0);
        assertThat(left.remaining()).isEqualTo(voo("1.500000"));
        assertThat(left.costBasis()).isEqualTo(new Money(USD, 45_000));
    }
}
