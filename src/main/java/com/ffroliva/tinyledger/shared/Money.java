package com.ffroliva.tinyledger.shared;

import java.util.Currency;
import java.util.Objects;

public record Money(Currency currency, long minorUnits) {
    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(String currencyCode, long minorUnits) {
        return new Money(Currency.getInstance(currencyCode), minorUnits);
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
