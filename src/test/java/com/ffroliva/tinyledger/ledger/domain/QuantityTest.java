package com.ffroliva.tinyledger.ledger.domain;

import static com.ffroliva.tinyledger.ledger.domain.AssetClass.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The two §6.5 domain errors {@link Quantity} raises, as types rather than a message to string-match. */
class QuantityTest {

    @Test
    void differentSymbolRaisesAssetMismatch() {
        Quantity voo = Quantity.of("VOO", EQUITY_ETF, "1");
        Quantity vti = Quantity.of("VTI", EQUITY_ETF, "1");

        assertThatThrownBy(() -> voo.plus(vti))
                .isInstanceOf(AssetMismatchException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asset mismatch");
        assertThatThrownBy(() -> voo.minus(vti)).isInstanceOf(AssetMismatchException.class);
    }

    @Test
    void differentAssetClassRaisesAssetMismatch() {
        Quantity equity = Quantity.of("AGG", EQUITY_ETF, "1");
        Quantity bond = Quantity.of("AGG", BOND_ETF, "1");

        assertThatThrownBy(() -> equity.plus(bond)).isInstanceOf(AssetMismatchException.class);
    }

    @Test
    void morePrecisionThanScaleRaisesExcessPrecision() {
        assertThatThrownBy(() -> Quantity.of("VOO", EQUITY_ETF, "10.5000005"))
                .isInstanceOf(ExcessPrecisionException.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6");
    }
}
