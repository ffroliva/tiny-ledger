"""Parses the exact example payloads from `docs/api/openapi.yaml` / `docs/spec.md` §7 — proves
the hand-written models actually match the contract, not just that they parse *something*."""

import pytest
from pydantic import ValidationError

from ledger_cli.models import (
    Account,
    Balance,
    MovementAmount,
    ProblemDetail,
    Transaction,
)


def test_transaction_matches_spec_7_example() -> None:
    payload = {
        "transactionUid": "8b0c1234-1234-4123-8123-123412341234",
        "accountUid": "f91e6c0e-1f3d-4b2a-9c77-0b1c2d3e4f50",
        "type": "WITHDRAWAL",
        "direction": "OUT",
        "amount": {"currency": "GBP", "minorUnits": 2000},
        "balanceAfter": {"currency": "GBP", "minorUnits": 8000},
        "status": "SETTLED",
        "transactionTime": "2026-08-03T17:12:09Z",
        "settlementTime": "2026-08-03T17:12:09Z",
        "reference": "rent",
    }
    txn = Transaction.model_validate(payload)
    assert txn.direction == "OUT"
    assert txn.amount.minor_units == 2000
    assert txn.balance_after.minor_units == 8000
    assert txn.reference == "rent"
    # round-trips back to the wire shape
    assert txn.model_dump(by_alias=True)["transactionUid"] == payload["transactionUid"]


def test_balance_matches_spec_7_example() -> None:
    payload = {
        "accountUid": "f91e6c0e-1f3d-4b2a-9c77-0b1c2d3e4f50",
        "amount": {"currency": "GBP", "minorUnits": 8000},
        "asOf": "2026-08-03T17:12:10Z",
        "streamVersion": 3,
    }
    balance = Balance.model_validate(payload)
    assert balance.stream_version == 3
    assert balance.amount.currency == "GBP"


def test_account_requires_owner_per_openapi_schema() -> None:
    payload = {
        "accountUid": "f91e6c0e-1f3d-4b2a-9c77-0b1c2d3e4f50",
        "name": "ACC-001",
        "currency": "GBP",
        "createdAt": "2026-08-03T17:12:09Z",
        # "owner" deliberately omitted — required by openapi.yaml's Account schema
    }
    with pytest.raises(ValidationError):
        Account.model_validate(payload)


@pytest.mark.parametrize(
    ("payload", "expected_type"),
    [
        (
            {
                "type": "/errors/insufficient-funds",
                "title": "Insufficient funds",
                "status": 422,
                "detail": "The account balance is lower than the requested withdrawal.",
                "traceId": "7c6f2e1a9b4d5c30",
            },
            "/errors/insufficient-funds",
        ),
        (
            {
                "type": "/errors/idempotency-conflict",
                "title": "Idempotency conflict",
                "status": 409,
                "detail": "This movement UID already identifies a different movement.",
                "traceId": "7c6f2e1a9b4d5c30",
            },
            "/errors/idempotency-conflict",
        ),
        (
            {
                "type": "/errors/version-conflict",
                "title": "Version conflict",
                "status": 409,
                "detail": "The account stream moved on; retry the command.",
                "traceId": "7c6f2e1a9b4d5c30",
            },
            "/errors/version-conflict",
        ),
        (
            {
                "type": "/errors/rate-limit-exceeded",
                "title": "Rate limit exceeded",
                "status": 429,
                "traceId": "7c6f2e1a9b4d5c30",
            },
            "/errors/rate-limit-exceeded",
        ),
        (
            {
                "type": "/errors/not-available-in-standalone",
                "title": "Not available in standalone",
                "status": 501,
                "detail": "The auditor operations require the full profile.",
                "traceId": "7c6f2e1a9b4d5c30",
            },
            "/errors/not-available-in-standalone",
        ),
    ],
)
def test_problem_detail_catalogue_examples(payload: dict[str, object], expected_type: str) -> None:
    problem = ProblemDetail.model_validate(payload)
    assert problem.type == expected_type
    assert problem.trace_id == "7c6f2e1a9b4d5c30"


def test_movement_amount_rejects_zero_minor_units() -> None:
    # openapi.yaml: MovementAmount.minorUnits has minimum: 1 — zero is a 400 server-side;
    # the client fails the same way before the wire.
    with pytest.raises(ValidationError):
        MovementAmount(currency="GBP", minorUnits=0)


def test_movement_amount_rejects_negative_minor_units() -> None:
    with pytest.raises(ValidationError):
        MovementAmount(currency="GBP", minorUnits=-100)
