"""Exercises the click commands end-to-end through `CliRunner`, with only the HTTP transport
faked (respx) — argument parsing, name -> uid resolution, amount conversion and exit codes are
all real."""

import json
import uuid

import httpx
import respx
from click.testing import CliRunner

from ledger_cli.cli import main

BASE = "http://testserver"
ACCOUNT_UID = "11111111-1111-4111-8111-111111111111"


def _account_json(
    uid: str = ACCOUNT_UID, name: str = "ACC-001", currency: str = "GBP"
) -> dict[str, object]:
    return {
        "accountUid": uid,
        "name": name,
        "currency": currency,
        "createdAt": "2026-08-06T00:00:00Z",
        "owner": "local",
    }


def _run(*args: str):  # noqa: ANN201 — click's Result type isn't exported for annotation
    runner = CliRunner()
    return runner.invoke(main, ["--base-url", BASE, *args])


@respx.mock
def test_account_open_reports_success() -> None:
    respx.post(f"{BASE}/api/v1/accounts").mock(
        return_value=httpx.Response(201, json=_account_json())
    )
    result = _run("account", "open", "--name", "ACC-001", "--currency", "gbp")
    assert result.exit_code == 0, result.output
    assert ACCOUNT_UID in result.output


@respx.mock
def test_account_open_surfaces_api_error() -> None:
    respx.post(f"{BASE}/api/v1/accounts").mock(
        return_value=httpx.Response(
            400, json={"type": "/errors/invalid-amount", "title": "Invalid amount", "status": 400}
        )
    )
    result = _run("account", "open", "--name", "", "--currency", "GBP")
    assert result.exit_code == 1
    assert "invalid-amount" in result.output


@respx.mock
def test_deposit_sends_minor_units_converted_from_decimal() -> None:
    respx.get(f"{BASE}/api/v1/accounts/{ACCOUNT_UID}").mock(
        return_value=httpx.Response(200, json=_account_json())
    )
    put_route = respx.put(url__regex=rf"{BASE}/api/v1/accounts/{ACCOUNT_UID}/deposits/.+").mock(
        return_value=httpx.Response(
            201,
            json={
                "transactionUid": str(uuid.uuid4()),
                "accountUid": ACCOUNT_UID,
                "type": "DEPOSIT",
                "direction": "IN",
                "amount": {"currency": "GBP", "minorUnits": 10000},
                "balanceAfter": {"currency": "GBP", "minorUnits": 10000},
                "status": "SETTLED",
                "transactionTime": "2026-08-06T00:00:00Z",
                "settlementTime": "2026-08-06T00:00:00Z",
            },
        )
    )
    result = _run("deposit", "--account", ACCOUNT_UID, "--amount", "100.00")
    assert result.exit_code == 0, result.output
    sent = json.loads(put_route.calls.last.request.content)
    assert sent["amount"]["minorUnits"] == 10000
    assert sent["amount"]["currency"] == "GBP"


@respx.mock
def test_deposit_rejects_invalid_amount_before_any_write() -> None:
    respx.get(f"{BASE}/api/v1/accounts/{ACCOUNT_UID}").mock(
        return_value=httpx.Response(200, json=_account_json())
    )
    put_route = respx.put(url__regex=rf"{BASE}/api/v1/accounts/{ACCOUNT_UID}/deposits/.+")
    result = _run("deposit", "--account", ACCOUNT_UID, "--amount", "100.005")
    assert result.exit_code == 1
    assert "invalid amount" in result.output
    assert put_route.call_count == 0


@respx.mock
def test_account_get_reports_ambiguous_name_and_lists_candidates() -> None:
    dup_a = _account_json(uid="22222222-2222-4222-8222-222222222222", name="dup")
    dup_b = _account_json(uid="33333333-3333-4333-8333-333333333333", name="dup")
    respx.get(f"{BASE}/api/v1/accounts").mock(
        return_value=httpx.Response(200, json={"accounts": [dup_a, dup_b]})
    )
    result = _run("account", "get", "--account", "dup")
    assert result.exit_code == 1
    assert "22222222" in result.output
    assert "33333333" in result.output


def test_scenario_run_rejects_unknown_name() -> None:
    result = _run("scenario", "run", "not-a-real-scenario")
    assert result.exit_code != 0


def test_scenario_run_rate_limit_is_a_vacuous_pass_under_standalone() -> None:
    # No respx routes at all — the scenario must short-circuit before any HTTP call.
    result = _run("scenario", "run", "rate-limit")
    assert result.exit_code == 0, result.output
    assert "PASS" in result.output
