"""Real sequence tests against a running app — no mock, no fake, the actual `LedgerClient` over
real HTTP. Excluded from the default run (`pyproject.toml`'s `addopts`); this file could not be
executed in this environment (no running app, no e2e CI job — see NOTES.md), so it is written and
reviewable but unverified.

Run against a `standalone` instance::

    ./mvnw spring-boot:run                     # separate shell, from the repo root
    uv run pytest -m e2e                       # from ledger-cli/

Run against `full`, pointed at a docker-compose stack with Keycloak provisioned::

    LEDGER_PROFILE=full LEDGER_USERNAME=alice LEDGER_PASSWORD=dev-only uv run pytest -m e2e
"""

from collections.abc import Iterator

import pytest
from rich.console import Console

from ledger_cli import scenarios
from ledger_cli.client import LedgerClient
from ledger_cli.config import Settings

pytestmark = pytest.mark.e2e


@pytest.fixture
def client() -> Iterator[LedgerClient]:
    with LedgerClient(Settings()) as c:
        yield c


def test_movement_chain(client: LedgerClient) -> None:
    """Four movements in a row, running balance asserted after each (task brief's first axis)."""
    result = scenarios.movement_chain(client, Console())
    assert result.ok, result.detail


def test_zero_boundary(client: LedgerClient) -> None:
    """Withdraw to exactly zero, then attempt one more (task brief's second axis)."""
    result = scenarios.zero_boundary(client, Console())
    assert result.ok, result.detail


def test_concurrent_withdrawals(client: LedgerClient) -> None:
    """N2. Ten parallel withdrawals, individually affordable, collectively over balance."""
    result = scenarios.concurrent_withdrawals(client, Console())
    assert result.ok, result.detail


def test_consistency_boundary(client: LedgerClient) -> None:
    """Deposit/read sequence crossing the eventual-consistency boundary, §4.4 (third axis)."""
    result = scenarios.consistency_boundary(client, Console())
    assert result.ok, result.detail


def test_edge_cases_smoke_flow(client: LedgerClient) -> None:
    """§11's own named smoke flow — open, deposit, withdraw, verify, replay, no double credit."""
    result = scenarios.edge_cases(client, Console())
    assert result.ok, result.detail


def test_rate_limit(client: LedgerClient) -> None:
    """§6.1's write bucket. A vacuous, honest pass under `standalone` (loopback is exempt);
    exercises the real 429 + Retry-After path under `full`."""
    result = scenarios.rate_limit(client, Console())
    assert result.ok, result.detail
