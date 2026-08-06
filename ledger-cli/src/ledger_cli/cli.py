"""Click entry point — the nine `openapi.yaml` operations plus the `scenario` sequences (§11)."""

from __future__ import annotations

import sys
import time
import uuid
from typing import Literal, NoReturn
from urllib.parse import parse_qs, urlparse

import click
import structlog
from rich.console import Console
from rich.table import Table

from ledger_cli.client import LedgerClient
from ledger_cli.config import Settings
from ledger_cli.errors import LedgerApiError
from ledger_cli.money import from_minor_units, to_minor_units
from ledger_cli.scenarios import SCENARIOS

structlog.configure(
    processors=[
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.KeyValueRenderer(),
    ],
    logger_factory=structlog.PrintLoggerFactory(sys.stderr),
)

console = Console()
error_console = Console(stderr=True)


def _client(ctx: click.Context) -> LedgerClient:
    return LedgerClient(ctx.obj)


def _fail(exc: LedgerApiError) -> NoReturn:
    detail = exc.problem.detail or exc.problem.title
    error_console.print(f"[red]{exc.problem.status} {exc.problem.type}[/red]: {detail}")
    sys.exit(1)


@click.group()
@click.option("--profile", type=click.Choice(["standalone", "full"]), default=None)
@click.option(
    "--base-url", default=None, help="Where the app is listening (both profiles, one port)."
)
@click.option("--issuer-uri", default=None, help="Keycloak realm issuer (full profile only).")
@click.option("--client-id", default=None, help="Keycloak public client id (full profile only).")
@click.option("--user", "username", default=None, help="Fixture username for a password grant.")
@click.option("--password", default=None)
@click.option("--token", default=None, help="A bearer token, bypassing Keycloak entirely.")
@click.option("--json", "json_output", is_flag=True, default=False, help="Machine-readable output.")
@click.pass_context
def main(
    ctx: click.Context,
    profile: Literal["standalone", "full"] | None,
    base_url: str | None,
    issuer_uri: str | None,
    client_id: str | None,
    username: str | None,
    password: str | None,
    token: str | None,
    json_output: bool,
) -> None:
    """ledger-cli — operator tool and e2e driver for the Tiny Ledger API (spec §11)."""
    settings = Settings()  # env vars / .env for anything the flags below don't override
    if profile is not None:
        settings.profile = profile
    if base_url is not None:
        settings.base_url = base_url
    if issuer_uri is not None:
        settings.issuer_uri = issuer_uri
    if client_id is not None:
        settings.client_id = client_id
    if username is not None:
        settings.username = username
    if password is not None:
        settings.password = password
    if token is not None:
        settings.token = token
    if json_output:
        settings.json_output = True
    ctx.obj = settings


# --- accounts ---------------------------------------------------------------


@main.group()
def account() -> None:
    """Open, list and read accounts."""


@account.command("open")
@click.option(
    "--name", required=True, help="Human-readable name — resolved back to a uid by other commands."
)
@click.option("--currency", required=True, help="ISO-4217 code, fixed for the account's life.")
@click.pass_context
def account_open(ctx: click.Context, name: str, currency: str) -> None:
    with _client(ctx) as client:
        try:
            acc = client.open_account(name, currency.upper())
        except LedgerApiError as exc:
            _fail(exc)
    console.print(f"[green]opened[/green] {acc.name} {acc.account_uid} ({acc.currency})")


@account.command("list")
@click.pass_context
def account_list(ctx: click.Context) -> None:
    with _client(ctx) as client:
        try:
            accounts = client.list_accounts()
        except LedgerApiError as exc:
            _fail(exc)
    table = Table("name", "accountUid", "currency", "createdAt")
    for a in accounts:
        table.add_row(a.name, a.account_uid, a.currency, str(a.created_at))
    console.print(table)


@account.command("get")
@click.option("--account", "account_ref", required=True, help="A name or an accountUid.")
@click.pass_context
def account_get(ctx: click.Context, account_ref: str) -> None:
    with _client(ctx) as client:
        try:
            uid = client.resolve_account_uid(account_ref)
            acc = client.get_account(uid)
        except LedgerApiError as exc:
            _fail(exc)
    console.print(acc.model_dump_json(indent=2, by_alias=True))


# --- movements ----------------------------------------------------------------


def _movement_command(
    ctx: click.Context,
    account_ref: str,
    amount: str,
    reference: str | None,
    movement_uid: str | None,
    kind: str,
) -> None:
    with _client(ctx) as client:
        try:
            uid = client.resolve_account_uid(account_ref)
            acc = client.get_account(uid)
            minor_units = to_minor_units(amount, acc.currency)
        except LedgerApiError as exc:
            _fail(exc)
        except ValueError as exc:
            error_console.print(f"[red]invalid amount[/red]: {exc}")
            sys.exit(1)

        mv_uid = movement_uid or str(uuid.uuid4())
        move = client.deposit if kind == "deposit" else client.withdraw
        try:
            txn, created = move(uid, mv_uid, minor_units, acc.currency, reference)
        except LedgerApiError as exc:
            _fail(exc)

    verb = "recorded" if created else "replayed (idempotent — original result, not re-applied)"
    console.print(
        f"[green]{kind} {verb}[/green] {amount} {acc.currency} -> "
        f"balanceAfter {from_minor_units(txn.balance_after.minor_units, acc.currency)} "
        f"(movementUid {mv_uid})"
    )


@main.command()
@click.option("--account", "account_ref", required=True)
@click.option(
    "--amount", required=True, help="Decimal, e.g. 100.00 — converted to minorUnits (§7)."
)
@click.option("--reference", default=None)
@click.option(
    "--movement-uid", default=None, help="Override the client-generated dedup UID (§6.3)."
)
@click.pass_context
def deposit(
    ctx: click.Context,
    account_ref: str,
    amount: str,
    reference: str | None,
    movement_uid: str | None,
) -> None:
    """Deposit money — PUT to a client-generated movement UID (§6.3)."""
    _movement_command(ctx, account_ref, amount, reference, movement_uid, "deposit")


@main.command()
@click.option("--account", "account_ref", required=True)
@click.option("--amount", required=True)
@click.option("--reference", default=None)
@click.option("--movement-uid", default=None)
@click.pass_context
def withdraw(
    ctx: click.Context,
    account_ref: str,
    amount: str,
    reference: str | None,
    movement_uid: str | None,
) -> None:
    """Withdraw money — 422 insufficient-funds on overdraft (no overdraft, spec §15)."""
    _movement_command(ctx, account_ref, amount, reference, movement_uid, "withdraw")


# --- balance / history ----------------------------------------------------------


@main.command()
@click.option("--account", "account_ref", required=True)
@click.option(
    "--consistency",
    type=click.Choice(["strong"]),
    default=None,
    help="Bypass the projection (§4.4).",
)
@click.option("--watch", is_flag=True, default=False, help="Poll until Ctrl+C.")
@click.option("--interval", type=float, default=2.0, help="Seconds between polls with --watch.")
@click.pass_context
def balance(
    ctx: click.Context, account_ref: str, consistency: str | None, watch: bool, interval: float
) -> None:
    with _client(ctx) as client:
        try:
            uid = client.resolve_account_uid(account_ref)
        except LedgerApiError as exc:
            _fail(exc)
        try:
            while True:
                bal = client.get_balance(uid, strong=(consistency == "strong"))
                shown = from_minor_units(bal.amount.minor_units, bal.amount.currency)
                staleness = f"asOf {bal.as_of}, streamVersion {bal.stream_version}"
                console.print(f"{shown} {bal.amount.currency} ({staleness})")
                if not watch:
                    break
                time.sleep(interval)
        except LedgerApiError as exc:
            _fail(exc)
        except KeyboardInterrupt:
            pass


def _cursor_from_link(link: str) -> str | None:
    values = parse_qs(urlparse(link).query).get("cursor")
    return values[0] if values else None


@main.command()
@click.option("--account", "account_ref", required=True)
@click.option("--limit", type=int, default=None)
@click.option("--cursor", default=None)
@click.option("--min-timestamp", default=None)
@click.option("--max-timestamp", default=None)
@click.option(
    "--all", "follow_all", is_flag=True, default=False, help="Follow links.next until exhausted."
)
@click.pass_context
def history(
    ctx: click.Context,
    account_ref: str,
    limit: int | None,
    cursor: str | None,
    min_timestamp: str | None,
    max_timestamp: str | None,
    follow_all: bool,
) -> None:
    table = Table("type", "direction", "amount", "balanceAfter", "transactionTime", "reference")
    with _client(ctx) as client:
        try:
            uid = client.resolve_account_uid(account_ref)
            next_cursor = cursor
            while True:
                page = client.list_transactions(
                    uid,
                    cursor=next_cursor,
                    limit=limit,
                    min_timestamp=min_timestamp,
                    max_timestamp=max_timestamp,
                )
                for t in page.transactions:
                    table.add_row(
                        t.type,
                        t.direction,
                        from_minor_units(t.amount.minor_units, t.amount.currency),
                        from_minor_units(t.balance_after.minor_units, t.balance_after.currency),
                        str(t.transaction_time),
                        t.reference or "",
                    )
                if not follow_all or page.links is None or not page.links.next:
                    break
                next_cursor = _cursor_from_link(page.links.next)
        except LedgerApiError as exc:
            _fail(exc)
    console.print(table)


# --- audit — full profile only; standalone answers 501 (§7) ----------------------


@main.group()
def audit() -> None:
    """Auditor-only operations — `full` profile only; `standalone` answers 501 (§7)."""


@audit.command("events")
@click.option(
    "--account",
    "account_ref",
    required=True,
    help="An accountUid — auditors own no accounts to resolve names against.",
)
@click.option("--limit", type=int, default=None)
@click.option("--cursor", default=None)
@click.pass_context
def audit_events(
    ctx: click.Context, account_ref: str, limit: int | None, cursor: str | None
) -> None:
    table = Table("version", "type", "occurredAt")
    with _client(ctx) as client:
        try:
            uid = client.resolve_account_uid(account_ref)
            page = client.get_events(uid, cursor=cursor, limit=limit)
        except LedgerApiError as exc:
            _fail(exc)
        for e in page.events:
            table.add_row(str(e.version), e.type, str(e.occurred_at))
    console.print(table)


@audit.command("entries")
@click.option(
    "--account",
    "account_ref",
    default=None,
    help="Restrict to one accountUid; omit for every account.",
)
@click.option("--limit", type=int, default=None)
@click.option("--cursor", default=None)
@click.option("--min-timestamp", default=None)
@click.option("--max-timestamp", default=None)
@click.pass_context
def audit_entries(
    ctx: click.Context,
    account_ref: str | None,
    limit: int | None,
    cursor: str | None,
    min_timestamp: str | None,
    max_timestamp: str | None,
) -> None:
    table = Table("accountUid", "version", "type", "occurredAt", "recordedAt")
    with _client(ctx) as client:
        try:
            uid = client.resolve_account_uid(account_ref) if account_ref else None
            page = client.list_audit_entries(
                account_uid=uid,
                cursor=cursor,
                limit=limit,
                min_timestamp=min_timestamp,
                max_timestamp=max_timestamp,
            )
        except LedgerApiError as exc:
            _fail(exc)
        for e in page.audit_entries:
            table.add_row(
                e.account_uid, str(e.version), e.type, str(e.occurred_at), str(e.recorded_at)
            )
    console.print(table)


# --- scenarios ----------------------------------------------------------------------


@main.group()
def scenario() -> None:
    """Sequences a single operation can't exercise — the CLI's actual job (§11)."""


@scenario.command("run")
@click.argument("name", type=click.Choice(sorted(SCENARIOS)))
@click.option("--currency", default="GBP")
@click.pass_context
def scenario_run(ctx: click.Context, name: str, currency: str) -> None:
    with _client(ctx) as client:
        result = SCENARIOS[name](client, console, currency.upper())
    if result.ok:
        console.print(f"[green]PASS[/green] {result.name}: {result.detail}")
    else:
        error_console.print(f"[red]FAIL[/red] {result.name}: {result.detail}")
        sys.exit(1)


if __name__ == "__main__":
    main()
