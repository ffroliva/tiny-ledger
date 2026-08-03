package com.flaviooliva.ledger.shared;

import static org.assertj.core.api.Assertions.*;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {
    private static final Currency GBP = Currency.getInstance("GBP");

    @Test
    void addsAndSubtractsInMinorUnits() {
        Money a = new Money(GBP, 10_000);
        assertThat(a.plus(new Money(GBP, 2_500))).isEqualTo(new Money(GBP, 12_500));
        assertThat(a.minus(new Money(GBP, 2_500))).isEqualTo(new Money(GBP, 7_500));
    }

    @Test
    void refusesCrossCurrencyArithmetic() {
        assertThatThrownBy(() -> new Money(GBP, 1).plus(Money.of("EUR", 1)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void signHelpers() {
        assertThat(new Money(GBP, 1).isPositive()).isTrue();
        assertThat(new Money(GBP, 0).isPositive()).isFalse();
        assertThat(new Money(GBP, -1).isNegative()).isTrue();
    }

    @Test
    void overflowFailsLoudly() {
        assertThatThrownBy(() -> new Money(GBP, Long.MAX_VALUE).plus(new Money(GBP, 1)))
                .isInstanceOf(ArithmeticException.class);
    }
}
