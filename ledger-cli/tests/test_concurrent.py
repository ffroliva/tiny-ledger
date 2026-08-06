"""The fan-out helper: N callables at once, every outcome returned, nothing swallowed."""

import threading

from ledger_cli.concurrent import fan_out


def test_returns_one_outcome_per_call_in_submission_order() -> None:
    results = fan_out([lambda i=i: i * 2 for i in range(5)])
    assert [o.value for o in results] == [0, 2, 4, 6, 8]
    assert all(o.error is None for o in results)


def test_captures_exceptions_instead_of_raising() -> None:
    def boom() -> int:
        raise ValueError("nope")

    results = fan_out([boom, lambda: 7])
    assert isinstance(results[0].error, ValueError)
    assert results[0].value is None
    assert results[1].value == 7


def test_calls_actually_overlap() -> None:
    """A sequential implementation would pass the two tests above. This one it cannot."""
    barrier = threading.Barrier(4, timeout=5)

    def wait_for_everyone() -> str:
        barrier.wait()
        return "together"

    results = fan_out([wait_for_everyone] * 4)
    assert [o.value for o in results] == ["together"] * 4
