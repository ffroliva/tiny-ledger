"""Typed errors over the RFC 7807 catalogue (spec §6.5)."""

from __future__ import annotations

from ledger_cli.models import ProblemDetail


class LedgerApiError(Exception):
    """Any non-2xx response the API answered as a `ProblemDetail`."""

    def __init__(self, problem: ProblemDetail) -> None:
        super().__init__(f"{problem.status} {problem.type}: {problem.detail or problem.title}")
        self.problem = problem


class RateLimitExceededError(LedgerApiError):
    """429 `/errors/rate-limit-exceeded` (§6.1) after the client's own retry budget is spent, or
    immediately when the caller opted out of retrying (see `LedgerClient(honor_rate_limit=False)`,
    used by the `rate-limit` scenario to observe the bucket rather than absorb it)."""

    def __init__(self, problem: ProblemDetail, retry_after: float | None) -> None:
        super().__init__(problem)
        self.retry_after = retry_after
