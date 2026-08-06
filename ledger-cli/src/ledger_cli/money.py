"""Decimal amounts <-> `minorUnits` (spec §7's `Money` shape). The CLI is the human boundary: a
human types `100.00`, the wire only ever sees the integer `10000` (§11).
"""

from __future__ import annotations

from decimal import Decimal, InvalidOperation

# ponytail: covers the common zero- and three-decimal exceptions to ISO 4217's default of two;
# it is not the full standard table. Upgrade path: source exponents from a currency-data package
# (e.g. `iso4217` or `babel.numbers.get_currency_precision`) if the CLI needs every currency —
# not needed today since every fixture account is GBP (docker/keycloak/realm-tiny-ledger.json).
_ZERO_EXPONENT_CURRENCIES = frozenset(
    {
        "BIF",
        "CLP",
        "DJF",
        "GNF",
        "ISK",
        "JPY",
        "KMF",
        "KRW",
        "PYG",
        "RWF",
        "UGX",
        "UYI",
        "VND",
        "VUV",
        "XAF",
        "XOF",
        "XPF",
    }
)
_THREE_EXPONENT_CURRENCIES = frozenset({"BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND"})


def minor_unit_exponent(currency: str) -> int:
    """Decimal places `currency` uses for its minor unit. Defaults to 2 (GBP, USD, EUR, ...)."""
    if currency in _ZERO_EXPONENT_CURRENCIES:
        return 0
    if currency in _THREE_EXPONENT_CURRENCIES:
        return 3
    return 2


def to_minor_units(amount: str, currency: str) -> int:
    """`"100.00"` -> `10000`. Rejects extra precision rather than silently truncating it (§7's
    note that `10000.5` minorUnits is a `400`, never a silent round)."""
    try:
        value = Decimal(amount)
    except InvalidOperation as exc:
        raise ValueError(f"not a decimal amount: {amount!r}") from exc
    exponent = minor_unit_exponent(currency)
    scaled = value.scaleb(exponent)
    whole = scaled.to_integral_value()
    if whole != scaled:
        places = f"{exponent} decimal place{'s' if exponent != 1 else ''}"
        raise ValueError(f"{amount!r} has more precision than {currency} supports ({places})")
    return int(whole)


def from_minor_units(minor_units: int, currency: str) -> str:
    """`10000` -> `"100.00"` for display."""
    exponent = minor_unit_exponent(currency)
    value = Decimal(minor_units).scaleb(-exponent)
    return f"{value:.{exponent}f}"
