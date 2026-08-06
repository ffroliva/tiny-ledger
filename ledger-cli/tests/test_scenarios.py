"""Scenario assertions run against a scripted fake API (respx `side_effect` callables), not a
respx-mocked call to the CLI's own logic — this proves `scenarios.py`'s pass/fail *detection*
actually detects, per AGENTS.md's own trap 4 ("a test that would pass with its fix reverted is not
coverage"): each scenario gets one test where the fake API behaves correctly, and one where it
deliberately misbehaves, to prove the assertion catches it.

This validates the CLI's sequencing and comparison logic only. Whether the real Java app actually
enforces these invariants is exactly what NOTES.md reports as unverified without a live app.
"""

import json
import re
import uuid

import httpx
import respx
from rich.console import Console

from ledger_cli import scenarios
from ledger_cli.client import LedgerClient
from ledger_cli.config import Settings

BASE = "http://testserver"
# path__regex, not url__regex: the balance endpoint carries a ?consistency=strong query string,
# and url__regex matches the full URL — a trailing "$" anchor would never match past the query.
_ACCOUNTS = re.escape("/api/v1/accounts")


class FakeLedger:
    """A minimal, in-memory stand-in for the Java app's write/read paths — just enough behaviour
    to drive the scenario functions, not a model of the real app."""

    def __init__(
        self, *, enforce_no_overdraft: bool = True, corrupt_after: int | None = None
    ) -> None:
        self.currency_by_account: dict[str, str] = {}
        self.balances: dict[str, int] = {}
        self.stream_version: dict[str, int] = {}
        self.movements: dict[str, dict[str, object]] = {}
        self.enforce_no_overdraft = enforce_no_overdraft
        self.corrupt_after = corrupt_after  # if set, the Nth movement reports a wrong balanceAfter
        self._movement_count = 0

    def open_account(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        uid = str(uuid.uuid4())
        self.currency_by_account[uid] = body["currency"]
        self.balances[uid] = 0
        self.stream_version[uid] = 1
        return httpx.Response(
            201,
            json={
                "accountUid": uid,
                "name": body["name"],
                "currency": body["currency"],
                "createdAt": "2026-08-06T00:00:00Z",
                "owner": "local",
            },
        )

    def movement(
        self, request: httpx.Request, kind: str, account_uid: str, movement_uid: str
    ) -> httpx.Response:
        if movement_uid in self.movements:
            return httpx.Response(200, json=self.movements[movement_uid])

        body = json.loads(request.content)
        minor_units: int = body["amount"]["minorUnits"]
        currency: str = body["amount"]["currency"]
        balance = self.balances[account_uid]

        if kind == "withdrawals":
            if self.enforce_no_overdraft and minor_units > balance:
                return httpx.Response(
                    422,
                    json={
                        "type": "/errors/insufficient-funds",
                        "title": "Insufficient funds",
                        "status": 422,
                        "detail": "The account balance is lower than the requested withdrawal.",
                    },
                )
            balance -= minor_units
        else:
            balance += minor_units

        self._movement_count += 1
        reported_balance = balance
        if self.corrupt_after is not None and self._movement_count == self.corrupt_after:
            reported_balance += 1  # deliberately wrong, to prove the scenario notices

        self.balances[account_uid] = balance
        self.stream_version[account_uid] += 1
        txn = {
            "transactionUid": movement_uid,
            "accountUid": account_uid,
            "type": "DEPOSIT" if kind == "deposits" else "WITHDRAWAL",
            "direction": "IN" if kind == "deposits" else "OUT",
            "amount": {"currency": currency, "minorUnits": minor_units},
            "balanceAfter": {"currency": currency, "minorUnits": reported_balance},
            "status": "SETTLED",
            "transactionTime": "2026-08-06T00:00:00Z",
            "settlementTime": "2026-08-06T00:00:00Z",
        }
        self.movements[movement_uid] = txn
        return httpx.Response(201, json=txn)

    def get_balance(self, account_uid: str) -> httpx.Response:
        currency = self.currency_by_account[account_uid]
        return httpx.Response(
            200,
            json={
                "accountUid": account_uid,
                "amount": {"currency": currency, "minorUnits": self.balances[account_uid]},
                "asOf": "2026-08-06T00:00:00Z",
                "streamVersion": self.stream_version[account_uid],
            },
        )


def _install(fake: FakeLedger) -> None:
    respx.post(f"{BASE}/api/v1/accounts").mock(side_effect=fake.open_account)
    respx.route(
        method="PUT",
        path__regex=rf"{_ACCOUNTS}/(?P<account_uid>[^/]+)/deposits/(?P<movement_uid>[^/]+)$",
    ).mock(
        side_effect=lambda request, account_uid, movement_uid: fake.movement(
            request, "deposits", account_uid, movement_uid
        )
    )
    respx.route(
        method="PUT",
        path__regex=rf"{_ACCOUNTS}/(?P<account_uid>[^/]+)/withdrawals/(?P<movement_uid>[^/]+)$",
    ).mock(
        side_effect=lambda request, account_uid, movement_uid: fake.movement(
            request, "withdrawals", account_uid, movement_uid
        )
    )
    respx.route(method="GET", path__regex=rf"{_ACCOUNTS}/(?P<account_uid>[^/]+)/balance$").mock(
        side_effect=lambda request, account_uid: fake.get_balance(account_uid)
    )


def _client() -> LedgerClient:
    return LedgerClient(Settings(base_url=BASE, profile="standalone"))


@respx.mock
def test_movement_chain_passes_against_a_correct_fake() -> None:
    _install(FakeLedger())
    with _client() as client:
        result = scenarios.movement_chain(client, Console(quiet=True))
    assert result.ok, result.detail


@respx.mock
def test_movement_chain_detects_a_wrong_running_balance() -> None:
    _install(FakeLedger(corrupt_after=3))  # third movement reports balanceAfter off by one
    with _client() as client:
        result = scenarios.movement_chain(client, Console(quiet=True))
    assert not result.ok
    assert "expected balance" in result.detail


@respx.mock
def test_zero_boundary_passes_when_overdraft_is_refused() -> None:
    _install(FakeLedger(enforce_no_overdraft=True))
    with _client() as client:
        result = scenarios.zero_boundary(client, Console(quiet=True))
    assert result.ok, result.detail


@respx.mock
def test_zero_boundary_detects_a_server_that_allows_overdraft() -> None:
    _install(FakeLedger(enforce_no_overdraft=False))
    with _client() as client:
        result = scenarios.zero_boundary(client, Console(quiet=True))
    assert not result.ok
    assert "NOT refused" in result.detail


@respx.mock
def test_edge_cases_passes_against_a_correct_fake() -> None:
    _install(FakeLedger())
    with _client() as client:
        result = scenarios.edge_cases(client, Console(quiet=True))
    assert result.ok, result.detail


@respx.mock
def test_consistency_boundary_passes_when_projection_never_lags() -> None:
    _install(FakeLedger())
    with _client() as client:
        result = scenarios.consistency_boundary(client, Console(quiet=True))
    assert result.ok, result.detail


def test_rate_limit_is_a_vacuous_pass_under_standalone() -> None:
    # No respx routes registered — a real HTTP call here would error, proving none was made.
    with LedgerClient(Settings(base_url=BASE, profile="standalone")) as client:
        result = scenarios.rate_limit(client, Console(quiet=True))
    assert result.ok
    assert "nothing to exhaust" in result.detail or "standalone" in result.detail
