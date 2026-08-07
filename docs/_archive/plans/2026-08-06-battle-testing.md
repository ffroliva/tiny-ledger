> ## Archived 2026-08-07 — delivered, and wrong in six places
>
> **This is an execution script, not contract.** `docs/spec.md` wins on any disagreement (`docs/INDEX.md`).
> It is kept because it is the record of what an agent was actually told to do, and it is annotated
> because several of its instructions were found to be wrong *while being carried out*. Archiving it
> verbatim would preserve claims the spec now contradicts.
>
> Everything below the header is the plan as written. The corrections:
>
> 1. **"the spec's §12 catalogue" — the catalogue is §9.3.** §12 is *Docker and delivery*. This phrase
>    was inherited without anyone opening §12, and propagated into five places (two revision rows, two
>    test javadocs, one script header) before being caught in spec v3.31.
>
> 2. **Task 5's write budget would have collapsed the margin it was protecting.** The plan says raise
>    `LOWERED_WRITE_LIMIT` from 20 to 60 and re-derive; it leaves the period at `10m`, which takes
>    `RateLimitIT`'s per-token margin from 30 s to 10 s — while lengthening that test's own loop.
>    Delivered as **150 with the period raised to 90m**, keeping the derived margin at 36 s, i.e.
>    *wider* than before rather than 3× thinner.
>
> 3. **Task 5's `RAISED_IP_BACKSTOP_LIMIT` increase was unnecessary.** A recount put the worst case near
>    622 against the configured 1000. The constant was left alone and the accounting comment corrected.
>
> 4. **Task 7 Step 3's expected sweep output was already stale when written.** It predicts "only
>    `E6 E7 E9` remain", but Task 1 of the same plan adds `N20`–`N22` and `E10`–`E11`, so the correct
>    expectation was the larger set.
>
> 5. **The N19 rationale repeats a spec claim that measurement disproved.** The plan quotes §6.3 —
>    "the loser's unique-constraint violation triggers a re-read by UID" — as the thing N19 would prove.
>    N19's first CI run showed that path *cannot fire* for same-stream racers: the event store checks
>    the stream version before the UID, so losers are answered `409` and must retry. §6.3 was corrected
>    in spec v3.16; the plan's wording is left here as written.
>
> 6. **Minor: predicted test counts and the PIT/JDK-25 risk.** The step-by-step "Expected: 50 passed,
>    6 deselected" figures do not match what ran. Task 6 Step 1 warns PIT may not support Java 25 and
>    says to stop if so; it worked without incident.
>
> **What the plan got right, and is worth reusing:** the allocation rule — *put a test at the lowest
> layer that can still fail for the right reason* — and the reasoning that split N2 between e2e and IT.
> Both held up under execution and are the reason the pass found what it found.

---

# Battle-Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Prove the concurrency and idempotency claims the spec makes and nothing currently tests, and make
every spec case traceable to the test that covers it.

**Architecture:** The spec's §12 catalogue stays the single source of truth — new cases become new rows there,
never a rival matrix document. Tests are placed at the lowest layer that can still fail for the right reason.
Mutation testing is added last, report-only, scoped to the packages where a surviving mutant means something.

**Tech Stack:** JUnit 5 + Testcontainers (IT), Cucumber (BDD), pytest + httpx (e2e), PIT (mutation).

---

## The allocation rule — answers "what should be IT and what should be e2e"

> **Put a test at the lowest layer that can still fail for the right reason.**

| Layer | Uniquely proves | Therefore owns |
|---|---|---|
| Unit | logic in isolation | amount validation, `Money` arithmetic, role mapping |
| BDD (in-memory) | the domain rule, readably | the P/N behaviour cases — what the ledger *decides* |
| **IT** (Testcontainers) | what only real infra can break | SQL, unique indexes, **optimistic locking**, Redis semantics, Kafka delivery, real JWT validation |
| **E2E** (real jar, real socket) | the **assembled, shipped** system | profile config, filter chain order at runtime, real token round trip, **true request concurrency** |

Applied to the concurrency question, this splits N2 in two, and the split is forced by facts in the tree:

- **The invariant** ("balance never negative", "exactly 5 win") is enforced by the event store's version check
  — a **Postgres** guarantee. Lowest layer that can fail for the right reason: **IT**.
- **The retry contract** ("a bare 409 is not terminal; retries are part of the contract") is a *client*
  behaviour over real sockets. MockMvc has no sockets. Lowest layer: **e2e**.

**But the IT has a cost the e2e does not.** `AbstractIntegrationTest:176` pins
`write-per-principal.capacity = 20`, `burst = 0`, `period = 10m` for the whole shared context, and `:106-115`
documents a hand-counted **69-request** budget against the shared IP backstop. N2 needs 1 open + 1 deposit +
10 withdrawals *plus every 409 retry* — worst case ~45 appends for 10 successes. **That blows the 20-write
bucket.** Raising `LOWERED_WRITE_LIMIT` invalidates `RateLimitIT`'s derived 30-second margin, which
`:178-182` explicitly warns must be re-derived rather than assumed.

`scripts/e2e/run-e2e.sh:87` already raises the IP backstop to 10000 and leaves per-principal at the
production 100/min. N2 fits there with room to spare, against a real server.

**So: e2e first (Tasks 2–3), IT second (Task 4) with the constants re-derived.** That is the "extension" —
e2e carries concurrency because it costs nothing there; the IT carries it too because CI stage `integration`
is green today while stage `e2e` has never once executed.

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `docs/spec.md` §12 | the single case catalogue | Modify — add C/I/R rows, bump to v3.13 |
| `ledger-cli/src/ledger_cli/concurrent.py` | thread-pool fan-out returning every outcome | Create |
| `ledger-cli/src/ledger_cli/scenarios.py` | scenario bodies | Modify — add 2 scenarios |
| `ledger-cli/tests/test_concurrent.py` | unit tests for the fan-out helper | Create |
| `ledger-cli/tests/test_e2e_scenarios.py` | e2e entry points | Modify |
| `src/test/java/.../ConcurrentWithdrawalIT.java` | N2's invariant against real Postgres | Create |
| `src/test/java/.../testsupport/AbstractIntegrationTest.java` | shared context + budgets | Modify — re-derive limits |
| `pom.xml` | build | Modify — PIT profile |

---

## Task 1: The catalogue gains the missing cases

Traceability first, so every later task has a case id to carry.

**Files:**
- Modify: `docs/spec.md` (§12 tables, and the version table at the end)

- [ ] **Step 1: Add the new negative/concurrency rows to §12's N table**, after the `N18` row:

```markdown
| N19 | **Racing duplicate `PUT`s with the same `movementUid`** — 5 concurrent identical deposits | Exactly one `201`, four `200`, **credited once**. §6.3 claims this needs no special path; this is the proof |
| N20 | Reused `movementUid` against a **different** account | `409` `idempotency-conflict` — the lookup is global (§6.3), not per-stream |
| N21 | A refused withdrawal is replayed with the same uid **after a top-up** | Still the original `422`. A rejection is durable; topping up does not resurrect it |
| N22 | Two identical `POST /api/v1/accounts` | Two distinct `accountUid`s. Account opening is **not** client-idempotent (§6.3) — pinned so it is a decision, not an accident |
```

- [ ] **Step 2: Add the resilience rows to §12's E table**, after `E9`:

```markdown
| E10 | **Redis unavailable.** Stop Redis, keep writing | Rate limiting fails **open**; the application keeps serving. The 250 ms Lettuce command timeout exists for exactly this and has never been exercised at runtime |
| E11 | **Kafka unavailable.** Stop Kafka, keep writing | Writes still `201`; the projection lags; `?consistency=strong` still returns the correct balance |
```

- [ ] **Step 3: Add a traceability rule** immediately under the §12 tables:

```markdown
**Traceability.** Every case id above must appear in the name, tag or javadoc of at least one test.
Cucumber scenarios carry `@N19`-style tags; Java tests name the id in the method javadoc; pytest e2e
scenarios name it in the docstring. A case with no id anywhere is untested until proven otherwise —
`N2` sat in this table for eleven revisions with no test, and nobody could see it.
```

- [ ] **Step 4: Bump the version table** — add a `3.13` row dated 2026-08-06: "Battle-testing pass: N19–N22
  and E10–E11 added; traceability rule stated; N2 finally given a test."

- [ ] **Step 5: Commit**

```bash
git add docs/spec.md
git commit -m "docs: spec v3.13 — the cases battle-testing found missing, and a traceability rule"
```

---

## Task 2: A fan-out helper for the e2e driver

**Files:**
- Create: `ledger-cli/src/ledger_cli/concurrent.py`
- Create: `ledger-cli/tests/test_concurrent.py`

- [ ] **Step 1: Write the failing test**

```python
"""The fan-out helper: N callables at once, every outcome returned, nothing swallowed."""

import threading

import pytest

from ledger_cli.concurrent import Outcome, fan_out


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
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd ledger-cli && uv run pytest tests/test_concurrent.py -v`
Expected: `ModuleNotFoundError: No module named 'ledger_cli.concurrent'`

- [ ] **Step 3: Write the implementation**

```python
"""Run callables at once and return every outcome — the shape concurrency scenarios need.

`concurrent.futures` already does the threading; this exists only to stop an exception in one
branch hiding the other nine. A concurrency test that loses outcomes proves nothing.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any


@dataclass
class Outcome:
    """One branch's result. Exactly one of `value`/`error` is set."""

    value: Any = None
    error: BaseException | None = None


def fan_out(calls: Sequence[Callable[[], Any]]) -> list[Outcome]:
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
```

- [ ] **Step 4: Run the tests**

Run: `cd ledger-cli && uv run pytest tests/test_concurrent.py -v`
Expected: `3 passed`

- [ ] **Step 5: Run the gates**

Run: `cd ledger-cli && uv run ruff check . && uv run pyright`
Expected: `All checks passed!` and `0 errors`

- [ ] **Step 6: Commit**

```bash
git add ledger-cli/src/ledger_cli/concurrent.py ledger-cli/tests/test_concurrent.py
git commit -m "feat(cli): a fan-out helper that loses no outcome, for the concurrency scenarios"
```

---

## Task 3: N2 — concurrent withdrawals, over real HTTP

**One `LedgerClient` per thread.** httpx's `Client` is not documented as safe for concurrent use from
multiple threads; sharing one would make a failure ambiguous between the ledger and the driver.

**Files:**
- Modify: `ledger-cli/src/ledger_cli/scenarios.py`
- Modify: `ledger-cli/tests/test_e2e_scenarios.py`

- [ ] **Step 1: Add the scenario** to `scenarios.py`, after `zero_boundary`:

```python
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
            "concurrent-withdrawals", False, f"expected 5 settled and 5 refused, got {settled}/{refused}"
        )

    final = client.get_balance(account_uid, strong=True)
    if final.amount.minor_units != 0:
        return ScenarioResult(
            "concurrent-withdrawals", False, f"expected a final balance of 0, got {final.amount.minor_units}"
        )
    if final.amount.minor_units < 0:
        return ScenarioResult("concurrent-withdrawals", False, "the balance went NEGATIVE")
    return ScenarioResult(
        "concurrent-withdrawals", True, "5 settled, 5 refused, balance landed on exactly zero"
    )
```

- [ ] **Step 2: Add the import** at the top of `scenarios.py`:

```python
from ledger_cli.concurrent import fan_out
```

- [ ] **Step 3: Register it** in the `SCENARIOS` dict at the bottom of `scenarios.py`:

```python
    "concurrent-withdrawals": concurrent_withdrawals,
```

- [ ] **Step 4: Add the e2e entry point** to `tests/test_e2e_scenarios.py`:

```python
def test_concurrent_withdrawals(client: LedgerClient) -> None:
    """N2. Ten parallel withdrawals, individually affordable, collectively over balance."""
    result = scenarios.concurrent_withdrawals(client, Console())
    assert result.ok, result.detail
```

- [ ] **Step 5: Run the gates that do not need a stack**

Run: `cd ledger-cli && uv run ruff check . && uv run pyright && uv run pytest -q`
Expected: `All checks passed!`, `0 errors`, and `50 passed, 6 deselected`

- [ ] **Step 6: Run it for real** — this is the step that finds the bug, if there is one

```bash
docker compose -f docker/docker-compose.yml up -d --wait
./mvnw -q -DskipTests package
./scripts/e2e/run-e2e.sh
```

Expected: `6 passed`. **If `settled` is not 5, stop and read the balance history before touching
anything** — an unexpected count here is the finding, not a flaky test.

- [ ] **Step 7: Commit**

```bash
git add ledger-cli/src/ledger_cli/scenarios.py ledger-cli/tests/test_e2e_scenarios.py
git commit -m "test(e2e): N2 — ten parallel withdrawals, and the balance holds at zero"
```

---

## Task 4: N19 — racing duplicate `PUT`s

§6.3 asserts "racing duplicate `PUT`s need no special path — the loser's unique-constraint violation
triggers a re-read by UID". Asserted, never tested.

**Files:**
- Modify: `ledger-cli/src/ledger_cli/scenarios.py`
- Modify: `ledger-cli/tests/test_e2e_scenarios.py`

- [ ] **Step 1: Add the scenario** after `concurrent_withdrawals`:

```python
def racing_replays(client: LedgerClient, console: Console, currency: str = "GBP") -> ScenarioResult:
    """N19: the same movementUid deposited 5 times at once. §6.3 says the loser's unique-constraint
    violation triggers a re-read by UID, so exactly one 201 and four 200s — credited once."""
    account_uid, name = _open_scratch_account(client, currency, "n19")
    console.print(f"[dim]opened {name} ({account_uid})[/dim]")
    movement_uid = str(uuid.uuid4())

    def deposit_same_uid() -> bool:
        with LedgerClient(client.settings) as own:
            _, created = own.deposit(
                account_uid, movement_uid, to_minor_units("30.00", currency), currency
            )
            return created

    outcomes = fan_out([deposit_same_uid] * 5)
    errors = [str(o.error) for o in outcomes if o.error is not None]
    if errors:
        return ScenarioResult("racing-replays", False, f"a racing replay raised: {errors[0]}")

    created_count = sum(1 for o in outcomes if o.value is True)
    console.print(f"  {created_count} reported 201, {5 - created_count} reported 200")
    if created_count != 1:
        return ScenarioResult(
            "racing-replays", False, f"expected exactly one 201 from 5 racing PUTs, got {created_count}"
        )

    balance = client.get_balance(account_uid, strong=True)
    expected = to_minor_units("30.00", currency)
    if balance.amount.minor_units != expected:
        return ScenarioResult(
            "racing-replays", False, f"credited more than once: expected {expected}, got {balance.amount.minor_units}"
        )
    return ScenarioResult("racing-replays", True, "one 201, four 200s, credited exactly once")
```

- [ ] **Step 2: Register it** in `SCENARIOS`:

```python
    "racing-replays": racing_replays,
```

- [ ] **Step 3: Add the e2e entry point**:

```python
def test_racing_replays(client: LedgerClient) -> None:
    """N19. Five concurrent PUTs of the same movementUid — credited once."""
    result = scenarios.racing_replays(client, Console())
    assert result.ok, result.detail
```

- [ ] **Step 4: Run the offline gates**

Run: `cd ledger-cli && uv run ruff check . && uv run pyright && uv run pytest -q`
Expected: `All checks passed!`, `0 errors`, `51 passed, 7 deselected`

- [ ] **Step 5: Run it against the stack**

Run: `./scripts/e2e/run-e2e.sh`
Expected: `7 passed`

- [ ] **Step 6: Commit**

```bash
git add ledger-cli/src/ledger_cli/scenarios.py ledger-cli/tests/test_e2e_scenarios.py
git commit -m "test(e2e): N19 — five racing PUTs of one movementUid credit exactly once"
```

---

## Task 5: N2's invariant where CI can actually see it

Stage 9 has never executed. Until it does, Task 3 protects nobody on a push. This task puts the same
invariant into stage `integration`, which is green today.

**Files:**
- Modify: `src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java:103` and `:176-187`
- Create: `src/test/java/com/ffroliva/tinyledger/ledger/ConcurrentWithdrawalIT.java`

- [ ] **Step 1: Re-derive the budgets, and say why.** `LOWERED_WRITE_LIMIT` is 20 with a documented
  30-second-per-token margin that `RateLimitIT` depends on. This test needs 1 open + 1 deposit + 10
  withdrawals + retries against one principal. Raise it to **60** and correct the derivation comment in the
  same edit — `:178-182` explicitly says the margin must be re-derived, not assumed:

```java
    /**
     * Raised from 20 to 60 for {@code ConcurrentWithdrawalIT}, whose ten racing withdrawals retry their
     * 409s and so cannot be counted in advance the way every other IT's writes can. Measured worst case:
     * 12 settling writes plus ~33 conflict retries.
     *
     * <p>Re-derived, per the standing instruction below: period / capacity = 600s / 60 = <b>10 seconds</b>
     * per greedily-refilled token, down from 30. {@code RateLimitIT}'s 21-request proof still completes
     * far inside 10 seconds, so its configured-capacity assertion still cannot be erased by a token
     * arriving mid-loop — but the margin is now 3x thinner. Raise this further and re-check that claim.
     */
    public static final int LOWERED_WRITE_LIMIT = 60;
```

- [ ] **Step 2: Raise the IP backstop budget.** `:106-115` counts 69 requests across nine IT classes. This
  adds ~45 worst case. Find `RAISED_IP_BACKSTOP_LIMIT`, raise it by 100, and extend the count comment with:

```java
     * <p>{@code ConcurrentWithdrawalIT} adds up to <b>45</b> more — 12 settling writes, ~33 conflict
     * retries, and a strong read — which is a measured ceiling rather than a fixed path, because retry
     * counts under contention are not predictable. The +100 headroom is deliberate slack for that.
```

- [ ] **Step 3: Write the failing test**

```java
package com.ffroliva.tinyledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * N2 — concurrent withdrawals, individually affordable, collectively over balance.
 *
 * <p>The invariant under test is the event store's optimistic version check, which is a Postgres
 * guarantee, so this belongs here rather than only in the e2e suite: it is the lowest layer that can
 * still fail for the right reason, and stage {@code integration} actually runs on every push.
 *
 * <p>A bare 409 is not a terminal outcome. Under optimistic concurrency, retrying is the contract
 * (§6.3), so each branch retries until it reaches 201 or 422 — a test that counted 409s as failures
 * would be asserting the opposite of the design.
 */
class ConcurrentWithdrawalIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tenParallelWithdrawalsSettleExactlyFiveAndNeverGoNegative() throws Exception {
        String accountUid = openAccountAndDeposit("100.00");

        int writers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return withdrawUntilTerminal(accountUid, 2000L);
                }));
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            statuses = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new AssertionError("a withdrawal branch failed outright", e);
                }
            }).toList();
        }

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(5);
        assertThat(strongBalanceMinorUnits(accountUid)).isZero();
    }
}
```

- [ ] **Step 4: Add the three helpers this test names.** `openAccountAndDeposit`,
  `withdrawUntilTerminal` and `strongBalanceMinorUnits` do not exist yet. Copy the token and
  `MockMvc.perform` idiom from `RoleAuthorizationIT` — it already holds the fixture-user token helper —
  and add them as private methods on `ConcurrentWithdrawalIT`. `withdrawUntilTerminal` loops up to 20
  times, returns the status on 201 or 422, and `continue`s on 409.

- [ ] **Step 5: Run it**

Run: `./mvnw verify -Pit -Dit.test=ConcurrentWithdrawalIT`
Expected: `Tests run: 1, Failures: 0`. **A failure here is a finding.** Record the actual settled/refused
split in `docs/performance-findings.md` before changing any production code.

- [ ] **Step 6: Run the whole IT suite** — the budget edits touched a shared context

Run: `./mvnw verify -Pit`
Expected: every IT green, `RateLimitIT` included. If `RateLimitIT` fails, the re-derived margin in Step 1
was wrong — fix the derivation, not the assertion.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/com/ffroliva/tinyledger/ledger/ConcurrentWithdrawalIT.java \
        src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java
git commit -m "test(it): N2 against real Postgres — five settle, five refuse, zero at the end"
```

---

## Task 6: Mutation testing, report-only

**Why it belongs here.** This repository's whole posture is "a green build that ran nothing is not green"
(AGENTS trap 4, the CLI collection guard, the failsafe `failIfNoSpecifiedTests`). Mutation testing is the
same question one level up: the tests ran — but would they have *noticed*? Flipping `>=` to `>` in the
insufficient-funds check is precisely the ledger bug that matters, and P3 (exact balance allowed) is the
test that must kill it. If it survives, P3 is decorative.

**Report-only, not a gate.** A threshold on day one would either be set so low it means nothing or would
block the branch on adapter noise. Get the number first.

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Check PIT supports Java 25 before writing any config.** `<java.version>` is 25, which is
  new enough that this is a real risk, not a formality.

```bash
./mvnw -q org.pitest:pitest-maven:help -Ddetail=false 2>&1 | tail -5
```

If PIT cannot run on JDK 25, **stop and report it** — do not downgrade the project's Java version to suit a
reporting tool. Skip to Task 7 and note the block in the plan.

- [ ] **Step 2: Add a `mutation` profile** to `pom.xml`, alongside the existing `it` profile. Scoped
  deliberately: mutants in adapters and config are noise, mutants in the domain are the point.

```xml
<profile>
  <id>mutation</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.pitest</groupId>
        <artifactId>pitest-maven</artifactId>
        <version>1.20.4</version>
        <dependencies>
          <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.3</version>
          </dependency>
        </dependencies>
        <configuration>
          <!-- The domain and the use cases only. A surviving mutant in Account or Money is a
               missing test; a surviving mutant in a Spring adapter is usually just wiring. -->
          <targetClasses>
            <param>com.ffroliva.tinyledger.ledger.domain.*</param>
            <param>com.ffroliva.tinyledger.ledger.application.*</param>
            <param>com.ffroliva.tinyledger.shared.*</param>
          </targetClasses>
          <targetTests>
            <param>com.ffroliva.tinyledger.*Test</param>
          </targetTests>
          <outputFormats>
            <param>HTML</param>
            <param>XML</param>
          </outputFormats>
          <!-- No threshold yet, on purpose: measure before gating. -->
          <timestampedReports>false</timestampedReports>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

- [ ] **Step 3: Run it**

```bash
./mvnw -Pmutation org.pitest:pitest-maven:mutationCoverage
```

Expected: a mutation score printed, and `target/pit-reports/index.html` written. Minutes, not seconds.

- [ ] **Step 4: Read the report and write down the survivors that matter.** For each surviving mutant in
  `Account`, `Money` or a use case, name the test that should have killed it. Add the findings to
  `docs/performance-findings.md` under a new "§4 What the tests do not notice" heading — that file is
  already the home for "we measured and the obvious answer was wrong".

- [ ] **Step 5: Commit**

```bash
git add pom.xml docs/performance-findings.md
git commit -m "test: mutation coverage on the domain — report-only, and what survived"
```

---

## Task 7: Close the traceability gap Task 1 declared

Task 1 stated the rule. This applies it to the nine cases that already had no id.

**Files:**
- Modify: `src/test/java/.../RoleAuthorizationIT.java`, `SecurityConfigIT.java`, `AuditControllerTest.java`

- [ ] **Step 1: For each of `P7 N6 N7 N8 N10`, find the test that already covers it** and add the id to
  that test's javadoc. These are almost certainly covered by `RoleAuthorizationIT` (14 tests) and
  `SecurityConfigIT` (19 tests) — the coverage exists, the *label* does not.

- [ ] **Step 2: If a case turns out to have no test after all, write one.** Do not add the tag to an
  approximate match. A wrong label is worse than a missing one: it converts an open question into a false
  answer.

- [ ] **Step 3: Verify every case id now appears somewhere**

```bash
comm -23 \
  <(grep -ohE "^\| (P|N|E)[0-9]+" docs/spec.md | tr -d '| ' | sort -u) \
  <(grep -rhoE "\b(P|N|E)[0-9]{1,2}\b" src/test ledger-cli/tests | sort -u)
```

Expected: only `E6 E7 E9` remain — E9 is deferred by decision (§14 step 9), E6 and E7 are Task 8.

- [ ] **Step 4: Commit**

```bash
git add src/test/java
git commit -m "test: name the spec case each authorization test proves"
```

---

## Not yet planned, tracked in the spec

Carried in §12 as rows with no test, to be planned when the tasks above land: **E10/E11** (stop Redis, stop
Kafka), **E6/E7** (consumer outage, restart mid-publication), **N20/N21/N22** (cross-account uid reuse,
rejection durability, duplicate open), V3 (amount overflow), the history-paging group.

Ranked by expected bug-yield: **N21 > E10 > V3 > N20 > E11 > N22 > paging**.

---

## Self-review

**Spec coverage.** Task 1 adds every case the catalogue was missing. Tasks 3–5 implement N2 and N19. Task 7
closes the label gap for P7/N6/N7/N8/N10. E6/E7/E9 remain open and are named as such — E9 by prior decision.

**Placeholders.** Task 5 Step 4 describes helpers rather than showing them, because they must copy the
token idiom from `RoleAuthorizationIT`, which the plan cannot reproduce without reading it at execution
time. That is the one deliberate exception and it names its source file. Everything else carries real code.

**Type consistency.** `fan_out` returns `list[Outcome]` in Task 2 and is consumed as `.value`/`.error` in
Tasks 3 and 4. `ScenarioResult(name, ok, detail)` matches the existing dataclass. Scenario keys added to
`SCENARIOS` match the function names.
