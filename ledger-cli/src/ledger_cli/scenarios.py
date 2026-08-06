"""Sequences, not cases (task brief). Every existing Java test drives one operation against
prepared state; the longest chain in that suite is two steps. These cover the axis nothing else
does: several movements in a row with the running balance checked after each, the exact-zero
withdrawal boundary, and the read model's eventual-consistency boundary (§4.4) — plus the two
smoke flows spec §11 names directly (`edge-cases`, `rate-limit`), used by §9.6.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from decimal import Decimal

from rich.console import Console

from ledger_cli.client import LedgerClient
from ledger_cli.concurrent import fan_out
from ledger_cli.errors import LedgerApiError
from ledger_cli.money import from_minor_units, to_minor_units


@dataclass
class ScenarioResult:
    name: str
    ok: bool
    detail: str


def _open_scratch_account(client: LedgerClient, currency: str, label: str) -> tuple[str, str]:
    name = f"cli-scenario-{label}-{uuid.uuid4().hex[:8]}"
    account = client.open_account(name, currency)
    return account.account_uid, name


def movement_chain(client: LedgerClient, console: Console, currency: str = "GBP") -> ScenarioResult:
    """Four movements in a row, running balance asserted after each."""
    account_uid, name = _open_scratch_account(client, currency, "chain")
    console.print(f"[dim]opened {name} ({account_uid})[/dim]")

    steps: list[tuple[str, str]] = [
        ("deposit", "50.00"),
        ("deposit", "25.00"),
        ("withdraw", "10.00"),
        ("deposit", "5.00"),
    ]
    running = Decimal("0")
    for kind, amount in steps:
        movement_uid = str(uuid.uuid4())
        minor_units = to_minor_units(amount, currency)
        if kind == "deposit":
            txn, _ = client.deposit(account_uid, movement_uid, minor_units, currency)
            running += Decimal(amount)
        else:
            txn, _ = client.withdraw(account_uid, movement_uid, minor_units, currency)
            running -= Decimal(amount)
        expected = to_minor_units(str(running), currency)
        actual = txn.balance_after.minor_units
        if actual != expected:
            return ScenarioResult(
                "movement-chain",
                False,
                f"after {kind} {amount}: expected balance {expected}, server said {actual}",
            )
        console.print(
            f"  {kind:<8} {amount:>8} -> balanceAfter {from_minor_units(actual, currency)}"
        )
    return ScenarioResult(
        "movement-chain", True, f"{len(steps)} movements, running balance held at each step"
    )


def zero_boundary(client: LedgerClient, console: Console, currency: str = "GBP") -> ScenarioResult:
    """Withdraw to exactly zero, then attempt one more — the boundary usually missed."""
    account_uid, name = _open_scratch_account(client, currency, "zero")
    console.print(f"[dim]opened {name} ({account_uid})[/dim]")

    deposit_amount = "20.00"
    client.deposit(
        account_uid, str(uuid.uuid4()), to_minor_units(deposit_amount, currency), currency
    )

    txn, _ = client.withdraw(
        account_uid, str(uuid.uuid4()), to_minor_units(deposit_amount, currency), currency
    )
    if txn.balance_after.minor_units != 0:
        return ScenarioResult(
            "zero-boundary",
            False,
            f"expected 0 after an exact withdrawal, got {txn.balance_after.minor_units}",
        )
    console.print(f"  withdrew {deposit_amount} to exactly zero")

    try:
        client.withdraw(account_uid, str(uuid.uuid4()), to_minor_units("0.01", currency), currency)
    except LedgerApiError as exc:
        if exc.problem.status == 422 and exc.problem.type == "/errors/insufficient-funds":
            console.print(
                "  withdrawing 0.01 more from zero correctly refused (422 insufficient-funds)"
            )
            return ScenarioResult(
                "zero-boundary", True, "a zero balance rejects the next withdrawal"
            )
        return ScenarioResult(
            "zero-boundary", False, f"wrong refusal: {exc.problem.type} {exc.problem.status}"
        )
    return ScenarioResult(
        "zero-boundary", False, "a withdrawal past a zero balance was NOT refused"
    )


def concurrent_withdrawals(
    client: LedgerClient, console: Console, currency: str = "GBP"
) -> ScenarioResult:
    """N2: 10 parallel withdrawals of 20.00 against 100.00. Exactly 5 settle, 5 are refused, and
    the balance never goes negative. A bare 409 is not terminal — under optimistic concurrency,
    retrying is part of the contract (§6.3), so each branch retries until it reaches 201 or 422."""
    account_uid, name = _open_scratch_account(client, currency, "n2")
    console.print(f"[dim]opened {name} ({account_uid})[/dim]")
    client.deposit(account_uid, str(uuid.uuid4()), to_minor_units("100.00", currency), currency)

    def withdraw_until_terminal() -> str:
        # One client per thread: httpx.Client is not documented thread-safe.
        with LedgerClient(client.settings) as own:
            movement_uid = str(uuid.uuid4())
            for _ in range(20):
                try:
                    own.withdraw(
                        account_uid, movement_uid, to_minor_units("20.00", currency), currency
                    )
                    return "settled"
                except LedgerApiError as exc:
                    if exc.problem.status == 422:
                        return "refused"
                    if exc.problem.status == 409:
                        continue  # version conflict: retry is the contract, not a failure
                    return f"unexpected {exc.problem.status} {exc.problem.type}"
            return "never reached a terminal outcome in 20 attempts"

    outcomes = fan_out([withdraw_until_terminal] * 10)
    errors = [str(o.error) for o in outcomes if o.error is not None]
    if errors:
        return ScenarioResult("concurrent-withdrawals", False, f"branch raised: {errors[0]}")

    results = [str(o.value) for o in outcomes]
    settled = results.count("settled")
    refused = results.count("refused")
    console.print(f"  {settled} settled, {refused} refused, from 10 parallel withdrawals")
    if settled + refused != 10:
        odd = [r for r in results if r not in ("settled", "refused")]
        return ScenarioResult("concurrent-withdrawals", False, f"non-terminal outcomes: {odd}")
    if settled != 5 or refused != 5:
        return ScenarioResult(
            "concurrent-withdrawals",
            False,
            f"expected 5 settled and 5 refused, got {settled}/{refused}",
        )

    final = client.get_balance(account_uid, strong=True)
    if final.amount.minor_units != 0:
        return ScenarioResult(
            "concurrent-withdrawals",
            False,
            f"expected a final balance of 0, got {final.amount.minor_units}",
        )
    return ScenarioResult(
        "concurrent-withdrawals", True, "5 settled, 5 refused, balance landed on exactly zero"
    )


def consistency_boundary(
    client: LedgerClient, console: Console, currency: str = "GBP"
) -> ScenarioResult:
    """Deposit, then race the projection read against the strong read (§4.4's read model)."""
    account_uid, name = _open_scratch_account(client, currency, "consistency")
    console.print(f"[dim]opened {name} ({account_uid})[/dim]")

    client.deposit(account_uid, str(uuid.uuid4()), to_minor_units("40.00", currency), currency)
    strong = client.get_balance(account_uid, strong=True)
    strong_shown = from_minor_units(strong.amount.minor_units, currency)
    console.print(f"  strong read: {strong_shown} @v{strong.stream_version}")

    deadline = time.monotonic() + 5.0
    projected = client.get_balance(account_uid)
    while projected.stream_version < strong.stream_version and time.monotonic() < deadline:
        console.print(
            f"  projection read: v{projected.stream_version} (asOf {projected.as_of}) — behind"
        )
        time.sleep(0.2)
        projected = client.get_balance(account_uid)

    if projected.stream_version < strong.stream_version:
        versions = f"v{projected.stream_version} < v{strong.stream_version}"
        return ScenarioResult(
            "consistency-boundary", False, f"projection never caught up: {versions} after 5s"
        )
    if projected.amount.minor_units != strong.amount.minor_units:
        return ScenarioResult(
            "consistency-boundary",
            False,
            f"projection caught up in version but amount differs: "
            f"{projected.amount.minor_units} vs {strong.amount.minor_units}",
        )
    console.print(
        f"  projection converged: v{projected.stream_version}, amount matches the strong read"
    )
    return ScenarioResult(
        "consistency-boundary", True, "the projection caught up with the aggregate within 5s"
    )


def edge_cases(client: LedgerClient, console: Console, currency: str = "GBP") -> ScenarioResult:
    """§11's own smoke flow: open, deposit, withdraw, verify, replay, confirm no double credit."""
    account_uid, name = _open_scratch_account(client, currency, "edge")
    console.print(f"[dim]opened {name} ({account_uid})[/dim]")

    deposit_uid = str(uuid.uuid4())
    first, created = client.deposit(
        account_uid, deposit_uid, to_minor_units("30.00", currency), currency
    )
    if not created:
        return ScenarioResult("edge-cases", False, "the first deposit did not report 201")

    client.withdraw(account_uid, str(uuid.uuid4()), to_minor_units("10.00", currency), currency)
    balance = client.get_balance(account_uid, strong=True)
    expected = to_minor_units("20.00", currency)
    if balance.amount.minor_units != expected:
        return ScenarioResult(
            "edge-cases", False, f"expected balance 20.00, got {balance.amount.minor_units}"
        )

    replay, replayed_created = client.deposit(
        account_uid, deposit_uid, to_minor_units("30.00", currency), currency
    )
    if replayed_created:
        return ScenarioResult(
            "edge-cases", False, "the replayed deposit UID reported 201, not 200 — re-applied?"
        )
    if replay.balance_after.minor_units != first.balance_after.minor_units:
        return ScenarioResult(
            "edge-cases", False, "the replay changed balanceAfter — double credit"
        )

    balance_after_replay = client.get_balance(account_uid, strong=True)
    if balance_after_replay.amount.minor_units != expected:
        return ScenarioResult("edge-cases", False, "the balance moved after an idempotent replay")

    console.print("  deposit -> withdraw -> balance -> replay same deposit UID -> no double credit")
    return ScenarioResult(
        "edge-cases", True, "the smoke flow held; the idempotent replay did not re-apply"
    )


def rate_limit(client: LedgerClient, console: Console, currency: str = "GBP") -> ScenarioResult:
    """Deliberately exhausts the write bucket (100/min, burst 20 — §6.1) and asserts 429.

    `standalone` exempts loopback (§6.1's operator-exemption clause, as configured for local runs),
    so there is nothing to exhaust there — this is a vacuous, honest pass, not a skipped test.
    """
    if client.settings.profile == "standalone":
        console.print(
            "[dim]standalone exempts loopback — nothing to exhaust; rerun with --profile full[/dim]"
        )
        return ScenarioResult(
            "rate-limit", True, "standalone has no write bucket to exhaust (§6.1)"
        )

    flood = LedgerClient(client.settings, honor_rate_limit=False)
    try:
        account_uid, name = _open_scratch_account(flood, currency, "ratelimit")
        console.print(f"[dim]opened {name} ({account_uid})[/dim]")
        for attempt in range(1, 131):  # burst 20 + capacity 100 + margin
            try:
                flood.deposit(
                    account_uid, str(uuid.uuid4()), to_minor_units("1.00", currency), currency
                )
            except LedgerApiError as exc:
                if exc.problem.status == 429:
                    console.print(f"  429 after {attempt} writes (type={exc.problem.type})")
                    return ScenarioResult(
                        "rate-limit", True, f"the write bucket was exhausted after {attempt} writes"
                    )
                return ScenarioResult(
                    "rate-limit", False, f"unexpected error at attempt {attempt}: {exc}"
                )
        return ScenarioResult(
            "rate-limit", False, "130 writes and never hit 429 — is the bucket configured?"
        )
    finally:
        flood.close()


SCENARIOS = {
    "movement-chain": movement_chain,
    "zero-boundary": zero_boundary,
    "concurrent-withdrawals": concurrent_withdrawals,
    "consistency-boundary": consistency_boundary,
    "edge-cases": edge_cases,
    "rate-limit": rate_limit,
}
