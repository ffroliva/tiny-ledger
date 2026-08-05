package com.ffroliva.tinyledger.shared;

import com.ffroliva.tinyledger.shared.error.InvalidAmountException;
import java.util.Currency;
import java.util.Objects;

public record Money(Currency currency, long minorUnits) {
    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    /**
     * §6.5: a well-formed but unknown ISO code. The OpenAPI pattern {@code ^[A-Z]{3}$} admits "ZZZ", so bean
     * validation passes it through and the JDK is what refuses it. One guard, because there are two call
     * sites — this one and {@code LedgerApiMapper.toCommand} — and a guard on only one of them leaves the
     * other answering an opaque 500.
     */
    public static Currency currencyOf(String currencyCode) {
        try {
            return Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new InvalidAmountException("unknown currency code: " + currencyCode);
        }
    }

    public static Money of(String currencyCode, long minorUnits) {
        return new Money(currencyOf(currencyCode), minorUnits);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits));
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency.getCurrencyCode(), other.currency.getCurrencyCode());
        }
    }
}
