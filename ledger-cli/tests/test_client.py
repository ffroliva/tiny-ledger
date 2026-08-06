"""Exercises `LedgerClient` against a mocked HTTP transport (respx) — the network boundary is
faked, nothing about the client's own retry/parsing/resolution logic is."""

import time
import uuid
from typing import Literal

import httpx
import pytest
import respx

from ledger_cli.client import LedgerClient
from ledger_cli.config import Settings
from ledger_cli.errors import LedgerApiError, RateLimitExceededError

BASE = "http://testserver"
ACCOUNT_UID = "11111111-1111-4111-8111-111111111111"


def _settings(profile: Literal["standalone", "full"] = "standalone") -> Settings:
    return Settings(base_url=BASE, profile=profile)


def _account_json(uid: str = ACCOUNT_UID, name: str = "ACC-001") -> dict[str, object]:
    return {
        "accountUid": uid,
        "name": name,
        "currency": "GBP",
        "createdAt": "2026-08-03T17:12:09Z",
        "owner": "local",
    }


def _txn_json(account_uid: str, movement_uid: str, minor_units: int = 10000) -> dict[str, object]:
    return {
        "transactionUid": movement_uid,
        "accountUid": account_uid,
        "type": "DEPOSIT",
        "direction": "IN",
        "amount": {"currency": "GBP", "minorUnits": minor_units},
        "balanceAfter": {"currency": "GBP", "minorUnits": minor_units},
        "status": "SETTLED",
        "transactionTime": "2026-08-03T17:12:09Z",
        "settlementTime": "2026-08-03T17:12:09Z",
    }


@respx.mock
def test_open_account_parses_201() -> None:
    respx.post(f"{BASE}/api/v1/accounts").mock(
        return_value=httpx.Response(201, json=_account_json())
    )
    with LedgerClient(_settings()) as client:
        account = client.open_account("ACC-001", "GBP")
    assert account.account_uid == ACCOUNT_UID


@respx.mock
def test_deposit_distinguishes_created_from_replay() -> None:
    movement_uid = str(uuid.uuid4())
    txn_body = _txn_json(ACCOUNT_UID, movement_uid)
    respx.put(f"{BASE}/api/v1/accounts/{ACCOUNT_UID}/deposits/{movement_uid}").mock(
        side_effect=[httpx.Response(201, json=txn_body), httpx.Response(200, json=txn_body)]
    )
    with LedgerClient(_settings()) as client:
        _, created_first = client.deposit(ACCOUNT_UID, movement_uid, 10000, "GBP")
        _, created_second = client.deposit(ACCOUNT_UID, movement_uid, 10000, "GBP")
    assert created_first is True
    assert created_second is False


@respx.mock
def test_insufficient_funds_raises_typed_error() -> None:
    movement_uid = str(uuid.uuid4())
    respx.put(f"{BASE}/api/v1/accounts/{ACCOUNT_UID}/withdrawals/{movement_uid}").mock(
        return_value=httpx.Response(
            422,
            json={
                "type": "/errors/insufficient-funds",
                "title": "Insufficient funds",
                "status": 422,
                "detail": "The account balance is lower than the requested withdrawal.",
                "traceId": "abc123",
            },
        )
    )
    with LedgerClient(_settings()) as client, pytest.raises(LedgerApiError) as exc_info:
        client.withdraw(ACCOUNT_UID, movement_uid, 100000, "GBP")
    assert exc_info.value.problem.type == "/errors/insufficient-funds"


@respx.mock
def test_429_is_retried_and_honours_retry_after(monkeypatch: pytest.MonkeyPatch) -> None:
    slept: list[float] = []
    monkeypatch.setattr(time, "sleep", lambda s: slept.append(s))

    respx.get(f"{BASE}/api/v1/accounts").mock(
        side_effect=[
            httpx.Response(
                429,
                headers={"Retry-After": "3"},
                json={
                    "type": "/errors/rate-limit-exceeded",
                    "title": "Rate limit exceeded",
                    "status": 429,
                },
            ),
            httpx.Response(200, json={"accounts": []}),
        ]
    )
    with LedgerClient(_settings()) as client:
        accounts = client.list_accounts()
    assert accounts == []
    assert slept == [3.0]


@respx.mock
def test_429_without_honor_rate_limit_raises_immediately() -> None:
    route = respx.get(f"{BASE}/api/v1/accounts").mock(
        return_value=httpx.Response(
            429,
            headers={"Retry-After": "3"},
            json={
                "type": "/errors/rate-limit-exceeded",
                "title": "Rate limit exceeded",
                "status": 429,
            },
        )
    )
    with LedgerClient(_settings(), honor_rate_limit=False) as client:
        with pytest.raises(RateLimitExceededError) as exc_info:
            client.list_accounts()
    assert exc_info.value.retry_after == 3.0
    assert route.call_count == 1


@respx.mock
def test_transport_error_is_retried_by_tenacity(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        time, "sleep", lambda s: None
    )  # tenacity's backoff nap, not under test here
    route = respx.get(f"{BASE}/api/v1/accounts").mock(
        side_effect=[httpx.ConnectError("boom"), httpx.Response(200, json={"accounts": []})]
    )
    with LedgerClient(_settings()) as client:
        accounts = client.list_accounts()
    assert accounts == []
    assert route.call_count == 2


@respx.mock
def test_resolve_account_uid_skips_lookup_for_a_real_uuid() -> None:
    route = respx.get(f"{BASE}/api/v1/accounts")
    with LedgerClient(_settings()) as client:
        resolved = client.resolve_account_uid(ACCOUNT_UID)
    assert resolved == ACCOUNT_UID
    assert route.call_count == 0


@respx.mock
def test_resolve_account_uid_by_name() -> None:
    respx.get(f"{BASE}/api/v1/accounts").mock(
        return_value=httpx.Response(200, json={"accounts": [_account_json(name="ACC-001")]})
    )
    with LedgerClient(_settings()) as client:
        resolved = client.resolve_account_uid("ACC-001")
    assert resolved == ACCOUNT_UID


@respx.mock
def test_resolve_account_uid_errors_on_ambiguous_name() -> None:
    dup_a = _account_json(uid="22222222-2222-4222-8222-222222222222", name="dup")
    dup_b = _account_json(uid="33333333-3333-4333-8333-333333333333", name="dup")
    respx.get(f"{BASE}/api/v1/accounts").mock(
        return_value=httpx.Response(200, json={"accounts": [dup_a, dup_b]})
    )
    with LedgerClient(_settings()) as client, pytest.raises(LedgerApiError) as exc_info:
        client.resolve_account_uid("dup")
    assert exc_info.value.problem.status == 409
    detail = exc_info.value.problem.detail or ""
    assert "22222222" in detail
    assert "33333333" in detail


@respx.mock
def test_resolve_account_uid_errors_on_no_match() -> None:
    respx.get(f"{BASE}/api/v1/accounts").mock(
        return_value=httpx.Response(200, json={"accounts": []})
    )
    with LedgerClient(_settings()) as client, pytest.raises(LedgerApiError) as exc_info:
        client.resolve_account_uid("does-not-exist")
    assert exc_info.value.problem.status == 404
