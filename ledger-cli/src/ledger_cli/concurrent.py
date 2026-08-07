"""Run callables at once and return every outcome — the shape concurrency scenarios need.

`concurrent.futures` already does the threading; this exists only to stop an exception in one
branch hiding the other nine. A concurrency test that loses outcomes proves nothing.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Generic, TypeVar

T = TypeVar("T")


@dataclass
class Outcome(Generic[T]):
    """One branch's result. Exactly one of `value`/`error` is set."""

    value: T | None = None
    error: BaseException | None = None


def fan_out(calls: Sequence[Callable[[], T]]) -> list[Outcome[T]]:
    """Run every callable concurrently; return outcomes in submission order.

    The pool is sized to `len(calls)` deliberately: a smaller pool would serialise some branches
    and the race under test would not happen.
    """
    with ThreadPoolExecutor(max_workers=len(calls)) as pool:
        futures = [pool.submit(c) for c in calls]
        return [
            Outcome(value=f.result()) if f.exception() is None else Outcome(error=f.exception())
            for f in futures
        ]
