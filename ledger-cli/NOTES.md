# ledger-cli — design record

Built against `docs/api/openapi.yaml` (the contract authority) and `docs/spec.md` §11 (the intended
command surface), for implementation-order step 10. This file is the design record the task brief
asked for — not a scratch file, committed alongside the code it describes.

## Command surface -> `openapi.yaml` operations

Nine operations in the contract; nine commands (grouped into five verbs plus two groups) map onto
them 1:1:

| Command | Operation | Notes |
|---|---|---|
| `account open --name --currency` | `openAccount` | `POST /api/v1/accounts`. `ledger:writer`. |
| `account list` | `listAccounts` | `GET /api/v1/accounts`. `ledger:reader`. Backs name -> uid resolution. |
| `account get --account` | `getAccount` | `GET /api/v1/accounts/{accountUid}`. |
| `deposit --account --amount [--reference] [--movement-uid]` | `putDeposit` | Client-generated UID, defaulted to a fresh `uuid4()` per invocation unless overridden. |
| `withdraw --account --amount [--reference] [--movement-uid]` | `putWithdrawal` | Same shape as deposit. |
| `balance --account [--consistency strong] [--watch] [--interval]` | `getBalance` | `--watch` is a client-side poll loop, not a server push — the API has no subscription mechanism. |
| `history --account [--limit] [--cursor] [--min/max-timestamp] [--all]` | `listTransactions` | `--all` follows `links.next` until exhausted. |
| `audit events --account [--cursor] [--limit]` | `getEvents` | `full` only; `standalone` answers `501`, surfaced as a normal `LedgerApiError`. |
| `audit entries [--account] [--cursor] [--limit] [--min/max-timestamp]` | `listAuditEntries` | Same `full`-only note. `--account` is optional (omit for every account). |

Plus `scenario run <name>` — the actual point of this build (see "Sequences" below), not an
`openapi.yaml` operation.

**`--account` accepts a name or a raw `accountUid`.** A UUID-shaped string is used verbatim
(`uuid.UUID(...)` succeeds -> skip resolution); anything else is resolved against the caller's own
`GET /api/v1/accounts` and matched by `name`, per §11's own requirement, including erroring with
every candidate `accountUid` listed on an ambiguous name (never guessing). The UUID bypass exists
because `dave` (`ledger:auditor`) owns no accounts to resolve names against — the `audit` commands
are effectively UUID-only in practice, and the help text says so.

## Where §11 and `openapi.yaml` disagree

1. **§11's example command line doesn't match the contract's positional shape.** `docs/spec.md`
   §11 shows `ledger-cli deposit --account ACC-001 --amount 100.00` with no way to express the
   movement UID that `putDeposit`'s path requires. `openapi.yaml` is unambiguous: the movement UID
   is part of the path and is the identity/dedup key (§6.3). **Followed `openapi.yaml`**: the CLI
   generates a `uuid4()` per invocation (making tenacity's retries safe by construction, per §11's
   own text) and exposes `--movement-uid` to override it — needed for the sequence tests to control
   idempotency deliberately (e.g. `edge-cases` replaying the *same* deposit on purpose).

2. **§11 lists `ledger-cli account open --currency GBP` with no `--name`.** `openAccount`'s request
   body (`OpenAccountRequest`) requires `name` — and §11's own prose two paragraphs later says the
   CLI resolves accounts *by name*, which is impossible if opening never sets one. **Followed
   `openapi.yaml`**: `--name` is required on `account open`.

3. **§11 never separates `account open`/`account list`/`account get` from `deposit`/`withdraw`.**
   The example shows a flat command list, but `openapi.yaml`'s `accounts` tag covers three distinct
   operations (open, list, get) that the flat shape can't express without inventing flag soup.
   **Followed `openapi.yaml`**: `account` is a group with three subcommands; `deposit`/`withdraw`/
   `balance`/`history` stay top-level, matching §11's flat examples for the operations it did show
   flat.

None of these are contradictions in the sense of "the spec says X, the contract says not-X" — §11
is a sketch (`docs/spec.md` calls it "the intended command surface"), and every gap is the sketch
being underspecified against the contract, not wrong. Reported per the task brief's instruction to
say so rather than silently reconcile.

## Auth: Direct Access Grants, not client-credentials

`docs/spec.md` §6.4's test-user table lists `ledger-cli` as a "service account" for a
"client-credentials flow." **`docker/keycloak/realm-tiny-ledger.json` — the actual fixture — has no
such client.** It defines exactly two clients, `ledger-test` and `ledger-other`, both
`"publicClient": true` with `"directAccessGrantsEnabled": true` and no client secret; the six
fixture principals (`alice`, `bob`, `carol`, `dave`, `mallory`, `nobody`) are *users* with password
credentials, not service accounts. There is no confidential client anywhere in the file, so
client-credentials — which needs one — is not an available grant.

The CLI therefore authenticates via **Resource Owner Password Credentials** (`grant_type=password`)
against `ledger-test`, using `--user`/`--password` (or `LEDGER_USERNAME`/`LEDGER_PASSWORD`) for one
of the fixture users. `--token` is also accepted, bypassing Keycloak entirely — the escape hatch for
a CI-minted token or a future service-account client if one is added. Tokens are cached under
`platformdirs.user_cache_dir("ledger-cli")`, keyed by `(issuer_uri, client_id, username)`, refreshed
via the refresh token when possible, and re-fetched via password grant when the refresh itself fails
(covered by `tests/test_auth.py`). This is a second, smaller §6.4-vs-realm disagreement, reported
per the same instruction as the two above — followed the realm file, as the task brief said to.

## Rate-limit decision (`full` profile, §6.1)

**Two different postures for two different jobs**, both implemented:

- **Every ordinary command and every scenario except `rate-limit` stays under the limit.** Nothing
  this CLI does loops hard — the longest sequence (`movement-chain`) is four writes. `LedgerClient`
  additionally **honours `Retry-After`** on any `429` it does encounter (bounded to 3 retries) as a
  defensive default, in case an operator runs two sequences back-to-back against the same principal.
- **The `rate-limit` scenario deliberately floods the bucket**, because that's its entire job (§9.6:
  "exhaust the rate limit, confirm the `429`"). It uses a second `LedgerClient(honor_rate_limit=False)`
  so the flood isn't silently absorbed by the same retry logic every other command relies on — it
  needs to *observe* the `429`, not survive past it.
- **Under `standalone`, `rate-limit` is a vacuous, honest pass, not a false failure.** The task
  brief notes `standalone` exempts loopback, so there is nothing to exhaust locally; the scenario
  checks `client.settings.profile` first and returns `ok=True` with a detail explaining why, rather
  than looping 130 times and reporting a failure that isn't one.

## Sequences — the actual deliverable

The audit behind this task found every existing Java test drives **one** operation against prepared
state, longest chain two steps. `scenarios.py` covers the three axes the brief named, plus the two
smoke flows §11 names directly:

- **`movement-chain`** — four movements in a row (deposit, deposit, withdraw, deposit), running
  balance asserted against `Decimal` arithmetic after *each* one, not just the last.
- **`zero-boundary`** — withdraw to exactly zero, then attempt one more; asserts the second
  withdrawal is refused with `422 /errors/insufficient-funds` specifically (not just "an error").
- **`consistency-boundary`** — deposit, read `?consistency=strong` (the aggregate), then poll the
  default (projection) read until its `streamVersion` catches up (bounded to 5s), asserting the
  amounts agree once it does. Exercises §4.4's read model across the eventual-consistency boundary
  the brief named.
- **`edge-cases`** — §11's own smoke flow verbatim: open, deposit, withdraw, verify balance, replay
  the *same* deposit UID, confirm `balanceAfter` is unchanged (no double credit) and the replay
  reports `200`, not `201`.
- **`rate-limit`** — see above.

## What I could not verify without a running app

> **Superseded on 2026-08-06 for the first bullet only.** `test_e2e_scenarios.py` has now been run
> against the real jar under the `full` profile on a docker-compose stack that does have Keycloak:
> **7 passed, 52 deselected**. The section is kept rather than deleted because it is the record of
> what was and was not known at the time, and because everything below the first bullet still holds.
>
> The run earned its keep immediately, finding a defect in the harness rather than the ledger:
> `scripts/e2e/run-e2e.sh` changed directory before invoking pytest, so its EXIT trap resolved the
> relative `APP_LOG` against `ledger-cli/` and printed "(no application log was produced)" while the
> real 21 KB log sat unread at the repository root. That surfaced on a genuinely failing run — six
> scenarios 401ing — which is precisely the case the trap exists for. Fixed with a subshell.

**Nothing in this build was run against the Java app.** There is no running instance in this
environment and no e2e CI job to borrow one from (§12.1 stage 9 is one of the specified-but-missing
stages). Concretely:

- `tests/test_e2e_scenarios.py` exists, is real (no mocking — genuine `LedgerClient` over genuine
  HTTP), and is marked `e2e`. It is **excluded from the default `uv run pytest` run** by
  `addopts = "-m 'not e2e and not live'"` and was never executed here. Running it requires
  `./mvnw spring-boot:run` (standalone) or a `full`-profile stack with Keycloak provisioned (not yet
  built — `docker/docker-compose.yml` has no Keycloak service; §12.1 confirms it as "specified, not
  yet built"). Instructions are in that file's module docstring. **See the note above: this bullet
  is now historical — the stack has Keycloak and the file has run.**
- Everything else — money conversion, the hand-written models against the contract's own example
  payloads, `LedgerClient`'s retry/parsing/resolution logic, the CLI's argument wiring, and
  `scenarios.py`'s pass/fail *detection* — is tested against a scripted fake HTTP transport (respx),
  never the real app. `tests/test_scenarios.py` is deliberately built so each scenario has one test
  where the fake behaves correctly and one where it's made to misbehave (e.g. a corrupted
  `balanceAfter`, a fake that wrongly allows overdraft), proving the assertions actually catch a
  violation rather than always passing — the discipline this repository's own `AGENTS.md` names as
  trap 4. That proves the CLI's own logic is sound; it proves nothing about whether the real app
  enforces insufficient-funds, idempotency, or projection convergence correctly. That remains
  unverified until `test_e2e_scenarios.py` runs against a live instance.
- **A Windows-specific bug was caught, not guessed at**: `--basetemp=tmp/pytest` fails on a fresh
  clone (`FileNotFoundError`, pytest's basetemp creation is a non-recursive `mkdir`, and `tmp/`
  doesn't exist yet). Fixed to `--basetemp=tmp` (one level, always under the already-existing
  `ledger-cli/`) and re-verified with `rm -rf tmp` first to simulate a fresh clone.
- `pytest-bdd` (named in the toolchain table) is a dependency I did **not** add — see below.

## Runtime dependencies

Everything in `[project.dependencies]` — `click`, `httpx`, `tenacity`, `rich`, `structlog`,
`pydantic`, `pydantic-settings`, `platformdirs` — is exactly the table this repository's own
`docs/spec.md` §11 specifies for this CLI; none is an addition of my own judgment. Justification for
each, briefly: `click` for the command tree; `httpx` for a modern typed HTTP client with a real
`Client(base_url=...)` (stdlib `urllib` would mean hand-rolling connection reuse, JSON handling and
error typing this deliverable needs); `tenacity` for the transient-network retry around the same
movement UID (§6.3 — safe by construction, not reimplemented by hand); `rich` for table/console
output distinguished from logging; `structlog` for the latter; `pydantic`/`pydantic-settings` for
the wire models and layered config (flags > env > defaults); `platformdirs` for a correct,
cross-platform token cache location instead of hand-rolling `%LOCALAPPDATA%`/`~/.cache` branching.

**`pytest-bdd`, also named in the table, was deliberately not added.** Its purpose per §9.6 is
binding the committed `.feature` files to CLI-driven step definitions for the e2e job — but that job
does not exist yet (§12.1 stage 9 is unbuilt), and wiring a Gherkin-to-Python bridge with no runner
to consume it is exactly the speculative infrastructure the task brief says not to build. Adding it
empty would be dead weight; adding it wired to `.feature` files would be a second, larger piece of
work outside this task's scope (driving the *existing* Java Gherkin catalogue through this CLI).
Flagged here rather than silently built or silently dropped, per the task's own instruction on
dependencies. If/when stage 9 is built, `pytest-bdd` is a one-line addition to `[dependency-groups]
dev` and `tests/test_e2e_scenarios.py`'s scenario functions are already the natural step
implementations to bind.

## Toolchain, verified in this environment

```
uv run ruff format .     # 16 files unchanged (clean)
uv run ruff check .      # All checks passed!
uv run pyright           # 0 errors, 0 warnings, 0 informations (strict on src/ledger_cli)
uv run pytest            # 48 passed, 5 deselected (the e2e file), exit 0
uv lock --check          # lock is current
uv run ledger-cli --help # entry point resolves and runs
```

`uv.lock` is committed. Python 3.13.7 is what this environment resolved against; `requires-python =
">=3.11"` and the classifiers cover 3.11–3.13 per spec §11, but only 3.13 was actually exercised
here — the 3.11/3.12 legs of the CI matrix (§12.1 stage 8) are unverified for the same reason
everything e2e is: no CI job ran them.
