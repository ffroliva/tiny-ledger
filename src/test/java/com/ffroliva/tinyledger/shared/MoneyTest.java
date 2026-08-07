package com.ffroliva.tinyledger.shared;

import static org.assertj.core.api.Assertions.*;

import com.ffroliva.tinyledger.shared.error.InvalidAmountException;
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

    /**
     * Kills the mutant recorded as `performance-findings` §6.4 row 1: deleting {@code requireSameCurrency}
     * from {@code Money.minus} passed the whole suite. Nothing in this codebase currently calls
     * {@code minus} with mismatched currencies — {@code Account.withdraw} guards first, and *that* guard's
     * mutant is killed — so the survivor was real but latent. {@code Money} is a public value type and owes
     * its own invariant a test that does not depend on who happens to call it today.
     */
    @Test
    void refusesCrossCurrencySubtractionToo() {
        assertThatThrownBy(() -> new Money(GBP, 1).minus(Money.of("EUR", 1)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void signHelpers() {
        assertThat(new Money(GBP, 1).isPositive()).isTrue();
        assertThat(new Money(GBP, 0).isPositive()).isFalse();
        assertThat(new Money(GBP, -1).isNegative()).isTrue();
    }

    /**
     * V3. This asserted {@code ArithmeticException} until 2026-08-07, which was "loudly" in the sense that
     * it did not wrap around — but at the API boundary that exception is uncatalogued, so it reached
     * {@code ErrorHandlingAdvice}'s catch-all and the caller got an opaque <b>500</b> for input the
     * contract permits. Loud to the domain is not the same as loud to the client.
     */
    @Test
    void overflowFailsAsACataloguedInvalidAmountRatherThanAnUncaughtArithmeticException() {
        assertThatThrownBy(() -> new Money(GBP, Long.MAX_VALUE).plus(new Money(GBP, 1)))
                .isInstanceOf(InvalidAmountException.class)
                .isNotInstanceOf(ArithmeticException.class);
    }

    /** The other operator, which had no overflow test at all — see {@code Money#exact}'s javadoc. */
    @Test
    void underflowOnSubtractionIsCataloguedTheSameWay() {
        assertThatThrownBy(() -> new Money(GBP, Long.MIN_VALUE).minus(new Money(GBP, 1)))
                .isInstanceOf(InvalidAmountException.class);
    }
}
