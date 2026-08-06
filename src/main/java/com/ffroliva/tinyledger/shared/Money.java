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
        return exact(Math::addExact, other);
    }

    public Money minus(Money other) {
        return exact(Math::subtractExact, other);
    }

    /**
     * §6.5, V3: {@code Math.addExact} throws {@link ArithmeticException} on overflow, which is neither a
     * {@code TinyLedgerException} nor an {@code ErrorResponse} — so it fell through to
     * {@code ErrorHandlingAdvice}'s catch-all and the caller got an opaque <b>500</b>. Measured, not
     * assumed: a deposit of {@code 9223372036854775807} into an account holding anything at all did
     * exactly that.
     *
     * <p>That input is <em>well-formed</em>. {@code minorUnits} is an {@code int64} with {@code minimum: 1}
     * in the contract, so bean validation admits it and the value is only unrepresentable once added to a
     * balance. §6.5 reserves 500 for "a genuine surprise"; an amount this ledger cannot represent is a
     * refusal it can foresee, and any authenticated writer could otherwise mint ERROR-level stack traces
     * at will.
     *
     * <p>Answered as {@code /errors/invalid-amount} (400) rather than a 422 {@code MovementRejected}: a
     * 4xx says "send a different amount", which is the truth here, and retrying is pointless — the mark of
     * a 4xx rather than a 5xx. The alternative, making overflow a durable rejection event beside
     * insufficient-funds and currency-mismatch, is a catalogue change and is deliberately not taken here.
     *
     * <p>Guarded on both operators, not just {@code plus}: {@code subtractExact} overflows too, at
     * {@code Long.MIN_VALUE}, and a guard on one of two call sites is how the 500 survived in the first
     * place.
     */
    private Money exact(java.util.function.LongBinaryOperator arithmetic, Money other) {
        requireSameCurrency(other);
        try {
            return new Money(currency, arithmetic.applyAsLong(minorUnits, other.minorUnits));
        } catch (ArithmeticException overflow) {
            throw new InvalidAmountException("amount is outside the range this ledger can represent in minor units");
        }
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
