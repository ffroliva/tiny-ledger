"""Wire models, hand-mirrored from `docs/api/openapi.yaml`'s `components.schemas` (§5).

Not codegen. `docs/spec.md` §11 says "generated from `openapi.yaml`" — see NOTES.md for why this
build hand-writes them once instead, and what that costs.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

_ALIASED = ConfigDict(populate_by_name=True)

TransactionType = Literal["DEPOSIT", "WITHDRAWAL"]
Direction = Literal["IN", "OUT"]
TransactionStatus = Literal["SETTLED", "PENDING", "REVERSED"]
EventType = Literal["AccountOpened", "MoneyDeposited", "MoneyWithdrawn", "MovementRejected"]


class Money(BaseModel):
    model_config = _ALIASED
    currency: str
    minor_units: int = Field(alias="minorUnits")


class MovementAmount(BaseModel):
    """`Money` as a movement may carry it: strictly positive (§7)."""

    model_config = _ALIASED
    currency: str
    minor_units: int = Field(alias="minorUnits", ge=1)


class OpenAccountRequest(BaseModel):
    name: str
    currency: str


class MovementRequest(BaseModel):
    amount: MovementAmount
    reference: str | None = None


class Account(BaseModel):
    model_config = _ALIASED
    account_uid: str = Field(alias="accountUid")
    name: str
    currency: str
    created_at: datetime = Field(alias="createdAt")
    owner: str


class AccountList(BaseModel):
    accounts: list[Account]


class PageLinks(BaseModel):
    next: str | None = None


class Balance(BaseModel):
    model_config = _ALIASED
    account_uid: str = Field(alias="accountUid")
    amount: Money
    as_of: datetime = Field(alias="asOf")
    stream_version: int = Field(alias="streamVersion")


class Transaction(BaseModel):
    model_config = _ALIASED
    transaction_uid: str = Field(alias="transactionUid")
    account_uid: str = Field(alias="accountUid")
    type: TransactionType
    direction: Direction
    amount: Money
    balance_after: Money = Field(alias="balanceAfter")
    status: TransactionStatus
    transaction_time: datetime = Field(alias="transactionTime")
    settlement_time: datetime = Field(alias="settlementTime")
    reference: str | None = None


class TransactionList(BaseModel):
    transactions: list[Transaction]
    links: PageLinks | None = None


class Event(BaseModel):
    model_config = _ALIASED
    account_uid: str = Field(alias="accountUid")
    version: int
    type: EventType
    occurred_at: datetime = Field(alias="occurredAt")
    payload: dict[str, object] | None = None


class EventList(BaseModel):
    events: list[Event]
    links: PageLinks | None = None


class AuditEntry(BaseModel):
    model_config = _ALIASED
    account_uid: str = Field(alias="accountUid")
    version: int
    type: EventType
    occurred_at: datetime = Field(alias="occurredAt")
    recorded_at: datetime = Field(alias="recordedAt")
    trace_id: str | None = Field(default=None, alias="traceId")


class AuditEntryList(BaseModel):
    model_config = _ALIASED
    audit_entries: list[AuditEntry] = Field(alias="auditEntries")
    links: PageLinks | None = None


class ProblemDetail(BaseModel):
    """RFC 7807, spec §6.5. `type` is the catalogue key CLI callers branch on."""

    model_config = _ALIASED
    type: str
    title: str
    status: int
    detail: str | None = None
    instance: str | None = None
    trace_id: str | None = Field(default=None, alias="traceId")
