from decimal import Decimal

import pytest

from ledger_cli.money import from_minor_units, minor_unit_exponent, to_minor_units


def test_gbp_round_trip() -> None:
    assert to_minor_units("100.00", "GBP") == 10000
    assert from_minor_units(10000, "GBP") == "100.00"


def test_gbp_single_decimal_is_padded() -> None:
    assert to_minor_units("1.5", "GBP") == 150


def test_zero_exponent_currency_has_no_decimal_places() -> None:
    assert minor_unit_exponent("JPY") == 0
    assert to_minor_units("100", "JPY") == 100
    assert from_minor_units(100, "JPY") == "100"


def test_three_exponent_currency() -> None:
    assert minor_unit_exponent("BHD") == 3
    assert to_minor_units("1.234", "BHD") == 1234
    assert from_minor_units(1234, "BHD") == "1.234"


def test_excess_precision_is_rejected_not_truncated() -> None:
    # spec §7: 10000.5 minorUnits is a 400, never a silent round.
    with pytest.raises(ValueError, match="precision"):
        to_minor_units("100.005", "GBP")


def test_garbage_amount_is_rejected() -> None:
    with pytest.raises(ValueError, match="not a decimal amount"):
        to_minor_units("not-a-number", "GBP")


def test_negative_amount_converts_but_movement_amount_rejects_it_separately() -> None:
    # to_minor_units is a pure unit conversion; the MovementAmount model (ge=1) is what
    # enforces "strictly positive" — see test_models.py.
    assert to_minor_units("-5.00", "GBP") == -500


def test_running_balance_arithmetic_matches_decimal() -> None:
    running = Decimal("0")
    for amount in ("50.00", "25.00", "-10.00"):
        running += Decimal(amount)
    assert to_minor_units(str(running), "GBP") == 6500
