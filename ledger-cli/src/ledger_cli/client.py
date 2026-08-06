"""Thin wrapper over `docs/api/openapi.yaml`'s nine operations (spec §11)."""

from __future__ import annotations

import time
import uuid
from typing import Any, Literal, Self

import httpx
import structlog
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

from ledger_cli.auth import get_access_token
from ledger_cli.config import Settings
from ledger_cli.errors import LedgerApiError, RateLimitExceededError
from ledger_cli.models import (
    Account,
    AccountList,
    AuditEntryList,
    Balance,
    EventList,
    MovementAmount,
    MovementRequest,
    OpenAccountRequest,
    ProblemDetail,
    Transaction,
    TransactionList,
)

log = structlog.get_logger(__name__)

_MAX_RATE_LIMIT_RETRIES = 3
_DEFAULT_RETRY_AFTER = 1.0


class LedgerClient:
    """One instance per run. `honor_rate_limit=False` is for the `rate-limit` scenario, which
    needs to *observe* a 429 rather than have it silently absorbed (§6.1, NOTES.md)."""

    def __init__(self, settings: Settings, *, honor_rate_limit: bool = True) -> None:
        self.settings = settings
        self._honor_rate_limit = honor_rate_limit
        self._http = httpx.Client(base_url=settings.base_url, timeout=settings.timeout)

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    def _headers(self) -> dict[str, str]:
        token = get_access_token(self.settings)
        return {"Authorization": f"Bearer {token}"} if token else {}

    @retry(
        retry=retry_if_exception_type(httpx.TransportError),
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=0.5, max=4),
        reraise=True,
    )
    def _send(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        return self._http.request(method, path, headers=self._headers(), **kwargs)

    def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        attempts = 0
        while True:
            resp = self._send(method, path, **kwargs)
            if (
                resp.status_code == 429
                and self._honor_rate_limit
                and attempts < _MAX_RATE_LIMIT_RETRIES
            ):
                retry_after = _parse_retry_after(resp.headers.get("Retry-After"))
                log.warning(
                    "rate_limited.retry", path=path, retry_after=retry_after, attempt=attempts
                )
                time.sleep(retry_after)
                attempts += 1
                continue
            return resp

    def _raise_for_problem(self, resp: httpx.Response) -> None:
        problem = ProblemDetail.model_validate(resp.json())
        if problem.status == 429:
            retry_after = resp.headers.get("Retry-After")
            raise RateLimitExceededError(problem, float(retry_after) if retry_after else None)
        raise LedgerApiError(problem)

    # --- accounts ---------------------------------------------------------

    def open_account(self, name: str, currency: str) -> Account:
        body = OpenAccountRequest(name=name, currency=currency)
        resp = self._request("POST", "/api/v1/accounts", json=body.model_dump())
        if resp.status_code != 201:
            self._raise_for_problem(resp)
        return Account.model_validate(resp.json())

    def list_accounts(self) -> list[Account]:
        resp = self._request("GET", "/api/v1/accounts")
        if resp.status_code != 200:
            self._raise_for_problem(resp)
        return AccountList.model_validate(resp.json()).accounts

    def get_account(self, account_uid: str) -> Account:
        resp = self._request("GET", f"/api/v1/accounts/{account_uid}")
        if resp.status_code != 200:
            self._raise_for_problem(resp)
        return Account.model_validate(resp.json())

    # --- movements ----------------------------------------------------------

    def deposit(
        self,
        account_uid: str,
        movement_uid: str,
        minor_units: int,
        currency: str,
        reference: str | None = None,
    ) -> tuple[Transaction, bool]:
        return self._movement(
            "deposits", account_uid, movement_uid, minor_units, currency, reference
        )

    def withdraw(
        self,
        account_uid: str,
        movement_uid: str,
        minor_units: int,
        currency: str,
        reference: str | None = None,
    ) -> tuple[Transaction, bool]:
        return self._movement(
            "withdrawals", account_uid, movement_uid, minor_units, currency, reference
        )

    def _movement(
        self,
        kind: Literal["deposits", "withdrawals"],
        account_uid: str,
        movement_uid: str,
        minor_units: int,
        currency: str,
        reference: str | None,
    ) -> tuple[Transaction, bool]:
        """Returns `(transaction, created)` — `created` is `True` on the first write (`201`),
        `False` on an idempotent replay (`200`, §6.3). tenacity retries above reuse this exact
        `movement_uid`, which is what makes the retry safe rather than a second movement."""
        body = MovementRequest(
            amount=MovementAmount(currency=currency, minorUnits=minor_units), reference=reference
        )
        path = f"/api/v1/accounts/{account_uid}/{kind}/{movement_uid}"
        resp = self._request("PUT", path, json=body.model_dump(by_alias=True, exclude_none=True))
        if resp.status_code not in (200, 201):
            self._raise_for_problem(resp)
        return Transaction.model_validate(resp.json()), resp.status_code == 201

    # --- balance / history ----------------------------------------------------

    def get_balance(self, account_uid: str, *, strong: bool = False) -> Balance:
        params = {"consistency": "strong"} if strong else None
        resp = self._request("GET", f"/api/v1/accounts/{account_uid}/balance", params=params)
        if resp.status_code != 200:
            self._raise_for_problem(resp)
        return Balance.model_validate(resp.json())

    def list_transactions(
        self,
        account_uid: str,
        *,
        cursor: str | None = None,
        limit: int | None = None,
        min_timestamp: str | None = None,
        max_timestamp: str | None = None,
    ) -> TransactionList:
        params = _page_params(cursor, limit, min_timestamp, max_timestamp)
        resp = self._request("GET", f"/api/v1/accounts/{account_uid}/transactions", params=params)
        if resp.status_code != 200:
            self._raise_for_problem(resp)
        return TransactionList.model_validate(resp.json())

    # --- audit — `full` profile only; `standalone` answers 501 (§7) -----------

    def get_events(
        self, account_uid: str, *, cursor: str | None = None, limit: int | None = None
    ) -> EventList:
        params = _page_params(cursor, limit, None, None)
        resp = self._request("GET", f"/api/v1/accounts/{account_uid}/events", params=params)
        if resp.status_code != 200:
            self._raise_for_problem(resp)
        return EventList.model_validate(resp.json())

    def list_audit_entries(
        self,
        *,
        account_uid: str | None = None,
        cursor: str | None = None,
        limit: int | None = None,
        min_timestamp: str | None = None,
        max_timestamp: str | None = None,
    ) -> AuditEntryList:
        params = _page_params(cursor, limit, min_timestamp, max_timestamp)
        if account_uid:
            params["accountUid"] = account_uid
        resp = self._request("GET", "/api/v1/audit/entries", params=params)
        if resp.status_code != 200:
            self._raise_for_problem(resp)
        return AuditEntryList.model_validate(resp.json())

    # --- name -> uid resolution (§11) -------------------------------------------

    def resolve_account_uid(self, name_or_uid: str) -> str:
        """`--account` takes either a name (resolved against the caller's own accounts, §11) or
        an `accountUid` verbatim — useful for `ledger:auditor` callers, who own no accounts to
        resolve names against. Ambiguous names error and list every candidate `accountUid` rather
        than guessing (§11's own requirement)."""
        try:
            uuid.UUID(name_or_uid)
            return name_or_uid
        except ValueError:
            pass
        matches = [a for a in self.list_accounts() if a.name == name_or_uid]
        if not matches:
            raise LedgerApiError(
                ProblemDetail(
                    type="/errors/account-not-found",
                    title="Account not found",
                    status=404,
                    detail=f"No account named {name_or_uid!r} among the caller's own accounts.",
                )
            )
        if len(matches) > 1:
            # CLI-local: not in the §6.5 catalogue — the server has no concept of name ambiguity,
            # only the CLI's name -> uid convenience does.
            candidates = ", ".join(a.account_uid for a in matches)
            raise LedgerApiError(
                ProblemDetail(
                    type="/errors/ambiguous-account-name",
                    title="Ambiguous account name",
                    status=409,
                    detail=f"{len(matches)} accounts are named {name_or_uid!r}: {candidates}",
                )
            )
        return matches[0].account_uid


def _parse_retry_after(value: str | None) -> float:
    if not value:
        return _DEFAULT_RETRY_AFTER
    try:
        return float(value)
    except ValueError:
        return _DEFAULT_RETRY_AFTER


def _page_params(
    cursor: str | None, limit: int | None, min_timestamp: str | None, max_timestamp: str | None
) -> dict[str, str | int]:
    params: dict[str, str | int] = {}
    if cursor:
        params["cursor"] = cursor
    if limit is not None:
        params["limit"] = limit
    if min_timestamp:
        params["minTransactionTimestamp"] = min_timestamp
    if max_timestamp:
        params["maxTransactionTimestamp"] = max_timestamp
    return params
