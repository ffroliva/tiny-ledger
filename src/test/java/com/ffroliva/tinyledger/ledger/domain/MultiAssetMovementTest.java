package com.ffroliva.tinyledger.ledger.domain;

import static com.ffroliva.tinyledger.ledger.domain.AssetClass.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Multi-asset movement arithmetic (spec §2.5). {@code Money} answers "how much cash", at the currency's own
 * minor-unit scale; it cannot answer "how many shares of VOO" — {@code java.util.Currency} has no instance
 * for a ticker, and a fractional share needs six decimal places where a currency needs two. {@link Quantity}
 * is that second value type, and it is a separate type on purpose: the two must never be added.
 *
 * <p>Fixed-point, never {@code double} — the same rule §2.1 already states for {@code Money}. Six decimal
 * places is the scale brokers quote fractional shares at, so a quantity is a {@code long} count of
 * micro-units exactly as {@code Money} is a {@code long} count of minor units.
 */
class MultiAssetMovementTest {

    @Test
    void parsesSixDecimalPlacesWithoutLosingThem() {
        Quantity q = Quantity.of("VOO", EQUITY_ETF, "10.500000");

        assertThat(q.microUnits()).isEqualTo(10_500_000L);
        assertThat(q.toDecimal()).isEqualTo(new BigDecimal("10.500000"));
        assertThat(q.toDecimal().scale()).isEqualTo(6);
    }

    /** The smallest representable slice: one micro-unit is not rounded away. */
    @Test
    void carriesTheSixthDecimalPlace() {
        assertThat(Quantity.of("VOO", EQUITY_ETF, "0.000001").microUnits()).isEqualTo(1L);
    }

    /**
     * The headline invariant, and the reason a movement pair is written as one credit and one debit of the
     * same {@code Quantity}: <b>+10.500000 VOO and −10.500000 VOO cancel exactly</b>. Fixed-point is what
     * makes "exactly" true — the same pair in {@code double} leaves a residue.
     */
    @Test
    void fractionalBalanceIsConservedAcrossAMatchedPair() {
        Quantity credit = Quantity.of("VOO", EQUITY_ETF, "10.500000");
        Quantity debit = credit.negated();

        assertThat(debit.microUnits()).isEqualTo(-credit.microUnits());
        assertThat(debit.toDecimal()).isEqualTo(new BigDecimal("-10.500000"));
        assertThat(credit.plus(debit)).isEqualTo(Quantity.zero("VOO", EQUITY_ETF));
        assertThat(credit.plus(debit).isZero()).isTrue();
    }

    /** Double entry over a whole set of movements, not just a pair: the legs of a balanced book sum to zero. */
    @Test
    void aBalancedSetOfMovementsSumsToZero() {
        List<Quantity> legs = List.of(
                Quantity.of("VOO", EQUITY_ETF, "10.500000"),
                Quantity.of("VOO", EQUITY_ETF, "0.250000"),
                Quantity.of("VOO", EQUITY_ETF, "-4.125000"),
                Quantity.of("VOO", EQUITY_ETF, "-6.625000"));

        Quantity sum = legs.stream().reduce(Quantity.zero("VOO", EQUITY_ETF), Quantity::plus);

        assertThat(sum.isZero()).isTrue();
    }

    @Test
    void subtractsWithinOneAsset() {
        Quantity held = Quantity.of("VOO", EQUITY_ETF, "10.500000");

        assertThat(held.minus(Quantity.of("VOO", EQUITY_ETF, "0.500000")))
                .isEqualTo(Quantity.of("VOO", EQUITY_ETF, "10.000000"));
    }

    /**
     * A quantity is meaningless without the asset it counts, so the asset travels inside the value and
     * arithmetic across two assets is refused. An {@code IllegalArgumentException} rather than a §6.5
     * catalogued error on purpose: nothing routes an asset symbol in from the wire yet, so reaching this
     * is a bug in a caller, and AGENTS.md reserves exceptions for exactly that.
     */
    @Test
    void refusesArithmeticAcrossAssets() {
        Quantity voo = Quantity.of("VOO", EQUITY_ETF, "1.000000");
        Quantity bnd = Quantity.of("BND", BOND_ETF, "1.000000");

        assertThatThrownBy(() -> voo.plus(bnd)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> voo.minus(bnd)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The asset class is part of the asset's identity, not a label beside it. Two instruments can share a
     * ticker across classes; treating them as one holding would silently net an equity position against a
     * bond position.
     */
    @Test
    void assetClassIsPartOfAssetIdentity() {
        Quantity equity = Quantity.of("AGG", EQUITY_ETF, "1.000000");
        Quantity bond = Quantity.of("AGG", BOND_ETF, "1.000000");

        assertThat(equity).isNotEqualTo(bond);
        assertThatThrownBy(() -> equity.plus(bond)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Cash is an asset class here too, so a multi-asset portfolio needs no second container for it. */
    @Test
    void currencyIsAnAssetClass() {
        assertThat(AssetClass.values()).containsExactly(CURRENCY, EQUITY_ETF, BOND_ETF);
        assertThat(Quantity.of("EUR", CURRENCY, "1250.000000").microUnits()).isEqualTo(1_250_000_000L);
    }

    /**
     * Seven decimal places is precision this ledger cannot hold, and truncating it silently is how a
     * position drifts from the broker's. Refused at the boundary rather than rounded.
     */
    @Test
    void refusesMorePrecisionThanSixDecimalPlaces() {
        assertThatThrownBy(() -> Quantity.of("VOO", EQUITY_ETF, "10.5000005"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6");
    }

    @Test
    void signHelpers() {
        assertThat(Quantity.of("VOO", EQUITY_ETF, "0.000001").isPositive()).isTrue();
        assertThat(Quantity.zero("VOO", EQUITY_ETF).isPositive()).isFalse();
        assertThat(Quantity.of("VOO", EQUITY_ETF, "-0.000001").isNegative()).isTrue();
        assertThat(Quantity.zero("VOO", EQUITY_ETF).isNegative()).isFalse();
        assertThat(Quantity.zero("VOO", EQUITY_ETF).isZero()).isTrue();
    }

    @Test
    void rejectsABlankSymbol() {
        assertThatThrownBy(() -> Quantity.of(" ", EQUITY_ETF, "1.000000")).isInstanceOf(IllegalArgumentException.class);
    }
}
