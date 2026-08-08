# `ledger-cli` — installing it, authenticating it, and driving the ledger with it

**A runbook for the Python CLI, the same way [`docker.md`](docker.md) is one for the `full` stack.**
`ledger-cli` is two things at once and the second is the one that usually gets forgotten: it is the
e2e driver CI runs at stage 9, **and it is a genuine operator tool** — the thing you reach for when
you want to move money through a running ledger without hand-assembling `curl`, minting a token by
hand, or converting pounds to minor units in your head.

> **Which document?** Contract questions → [`spec.md`](spec.md) (§11 is this CLI's own section).
> Running the stack it talks to → [`docker.md`](docker.md). Where credentials live →
> [`security-material.md`](security-material.md).

**What is verified here, and what is not.** The install steps, the `--help` output, the failure
modes in §7 and the scenario list were executed in this repository on 2026-08-08 and the output is
what came back. **The money-moving examples were not run while writing this** — they need a JDK 25
application on the other end. They are transcribed from the CLI's own code and from
`docs/api/openapi.yaml`, and the suite that *does* execute them end to end is CI stage 9, which
drives seven unmocked scenarios against the containerised app on every push. Where a claim here is
inherited rather than observed, it says so.

---

## 0. Install

The CLI lives in `ledger-cli/` and is managed by **`uv`**. That is the one prerequisite: uv
provisions its own Python (3.11+), so Python is not a separate install.

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh      # macOS / Linux
powershell -c "irm https://astral.sh/uv/install.ps1 | iex"   # Windows
```

Two ways to run it, and they are not equivalent:

```bash
cd ledger-cli
uv sync --locked          # the pinned environment — uv.lock is committed
uv run ledger-cli --help
```

```bash
uv tool install ./ledger-cli    # from the repository root; puts `ledger-cli` on your PATH
ledger-cli --help
```

**Prefer `uv run` inside `ledger-cli/` when the answer matters.** `uv sync --locked` fails on a
stale lockfile rather than quietly resolving something else, so it is the reproducible path and the
one CI uses. `uv tool install` resolves fresh and ignores `uv.lock` — fine for an operator who wants
the command on their PATH, wrong for reproducing a run.

Verified output of `ledger-cli --help`:

```
Usage: ledger-cli [OPTIONS] COMMAND [ARGS]...

  ledger-cli — operator tool and e2e driver for the Tiny Ledger API (spec §11).

Options:
  --profile [standalone|full]
  --base-url TEXT              Where the app is listening (both profiles, one port).
  --issuer-uri TEXT            Keycloak realm issuer (full profile only).
  --client-id TEXT             Keycloak public client id (full profile only).
  --user TEXT                  Fixture username for a password grant.
  --password TEXT
  --token TEXT                 A bearer token, bypassing Keycloak entirely.
  --json                       Machine-readable output.
  --help                       Show this message and exit.

Commands:
  account   Open, list and read accounts.
  audit     Auditor-only operations — `full` profile only; `standalone`...
  balance
  deposit   Deposit money — PUT to a client-generated movement UID (§6.3).
  history
  scenario  Sequences a single operation can't exercise — the CLI's...
  withdraw  Withdraw money — 422 insufficient-funds on overdraft (no...
```

**The CLI does not start the ledger.** Something has to be listening on `--base-url` first:
`./mvnw spring-boot:run` for `standalone`, or the Compose stack for `full` ([`docker.md`](docker.md)).

---

## 1. Against `standalone` — no auth, nothing to configure

`standalone` is the default profile and needs no credentials at all, because there is no
authentication in that mode (the startup banner says `AUTH DISABLED (standalone)`).

Every command from here on is written as `ledger-cli …`, assuming the `uv tool install` above. If
you took the other path, the equivalent is `uv run ledger-cli …` from inside `ledger-cli/`.

```bash
ledger-cli account open --name ACC-001 --currency GBP
ledger-cli deposit  --account ACC-001 --amount 100.00
ledger-cli balance  --account ACC-001
ledger-cli history  --account ACC-001
```

**`--account` takes a name or an `accountUid`.** A UUID-shaped string is used verbatim; anything
else is resolved against your own `GET /api/v1/accounts` and matched by name. An ambiguous name is
an error listing every candidate uid — it never guesses.

**Amounts are decimal here and integers on the wire.** You type `100.00`; the CLI sends
`{"currency":"GBP","minorUnits":10000}`. Extra precision is refused locally rather than truncated —
`--amount 100.005` for a GBP account fails with *"has more precision than GBP supports (2 decimal
places)"* before any request is sent. Zero-decimal (JPY, KRW…) and three-decimal (KWD, BHD…)
currencies are handled; the table is the common exceptions, not all of ISO 4217.

**There are no pre-seeded accounts.** `ACC-001` above is a name *you* just created. Spec §6.4
describes `ACC-001`…`ACC-900` as fixtures owned by the test users, but §6.4 also says plainly that
the seed script is **not built** — a fresh stack has an empty ledger whichever profile it runs.

---

## 2. Authorisation in `full` — how the CLI gets a token

In `full`, every route requires a bearer token and the route's role (spec §6.4). The CLI obtains one
itself: a **Direct Access Grant** (`grant_type=password`) against the public `ledger-test` client in
`docker/keycloak/realm-tiny-ledger.json`.

```bash
ledger-cli --profile full --user alice --password dev-only balance --account ACC-001
```

**Not client-credentials, despite what spec §6.4's table says.** That table lists a `ledger-cli`
service account owning `ACC-900`; the realm file defines no such client and no confidential client
at all, so that grant is not available. The realm file is the fixture that actually runs, so the CLI
follows it. Recorded in `ledger-cli/NOTES.md` rather than silently reconciled.

The fixture users, straight from the realm file — every password is `dev-only`, public on purpose
(see [`security-material.md`](security-material.md)):

| `--user` | Realm roles | Use it to see |
|---|---|---|
| `alice` | `ledger:writer`, `ledger:reader` | The positive path — open, deposit, withdraw, read back |
| `bob` | `ledger:writer`, `ledger:reader` | A second independent stream; aggregates are isolated |
| `carol` | `ledger:reader` | **403 on any write.** A reader may not move money |
| `dave` | `ledger:auditor` | The `audit` commands. **403 on every write**, and owns no accounts |
| `mallory` | `ledger:writer`, `ledger:reader` | **403 across accounts.** Valid token, right role, wrong owner |
| `trent` | `+ ledger:admin` | On-behalf-of: moves money on an account he does not own, recorded as `actor`. **403 on the audit trail** |
| `nobody` | *(none)* | **403 everywhere.** Authenticated and entitled to nothing |

Three ways to supply credentials, highest precedence first — flags, then `LEDGER_*` environment
variables, then defaults:

```bash
ledger-cli --profile full --user alice --password dev-only ...     # flags
LEDGER_PROFILE=full LEDGER_USERNAME=alice LEDGER_PASSWORD=dev-only ledger-cli ...   # env
ledger-cli --profile full --token "$ACCESS_TOKEN" ...              # a token you already hold
```

`--token` bypasses Keycloak entirely — the escape hatch for a CI-minted token, or for inspecting
what a hand-crafted token does. Otherwise tokens are **cached on disk**, keyed by
`(issuer_uri, client_id, username)`, under `platformdirs`' user cache directory
(`~/.cache/ledger-cli` on Linux, `~/Library/Caches/ledger-cli` on macOS, `%LOCALAPPDATA%` on
Windows), written `0600` where the filesystem supports it, and refreshed via the refresh token
before falling back to a fresh password grant. A stale or corrupt cache file is ignored, not fatal.
Delete that directory if you want to force a clean grant.

**The issuer must match what the app validates.** The default is
`https://auth.localhost/realms/tiny-ledger`, mirroring the app's own `LEDGER_ISSUER_URI` — Keycloak
is behind Traefik since TLS landed, so this client speaks HTTPS to the identity provider as well as
to the API and needs the dev CA (`SSL_CERT_FILE`, which `scripts/e2e/run-e2e.sh` exports). A token
minted from a different host string is rejected with `401` even though it is otherwise valid — the
single most common `full`-profile confusion, and `docker.md`'s symptom table carries it too.

---

## 3. Worked example — one account, end to end

Against `full` as `alice`. Export the credentials once so the lines stay readable:

```bash
export LEDGER_PROFILE=full LEDGER_USERNAME=alice LEDGER_PASSWORD=dev-only
```

```bash
ledger-cli account open --name ACC-001 --currency GBP
# opened ACC-001 6f1c… (GBP)

ledger-cli deposit --account ACC-001 --amount 100.00 --reference "opening float"
# deposit recorded 100.00 GBP -> balanceAfter 100.00 (movementUid 3f2a…)

ledger-cli withdraw --account ACC-001 --amount 30.00
# withdraw recorded 30.00 GBP -> balanceAfter 70.00 (movementUid 9c04…)

ledger-cli balance --account ACC-001
# 70.00 GBP (asOf 2026-08-08T…, streamVersion 3)
```

**Idempotency is visible from here.** Each movement generates a fresh `movementUid` unless you pass
one, so a retried command is a *new* movement — which is what you want interactively. Pass the same
uid twice and the CLI tells you which happened:

```bash
ledger-cli deposit --account ACC-001 --amount 10.00 --movement-uid 11111111-1111-4111-8111-111111111111
# deposit recorded 10.00 GBP -> balanceAfter 80.00 (movementUid 1111…)

ledger-cli deposit --account ACC-001 --amount 10.00 --movement-uid 11111111-1111-4111-8111-111111111111
# deposit replayed (idempotent — original result, not re-applied) -> balanceAfter 80.00
```

That wording is the CLI's, and it is the difference between a `201` and a `200` on the same URL —
the property a payments client actually needs from a retry (§6.3).

**Two reads, deliberately different.** `balance` prints its staleness markers (`asOf`,
`streamVersion`) because the default read is served from the projection. `--consistency strong`
bypasses it and reads the aggregate:

```bash
ledger-cli balance --account ACC-001 --consistency strong
ledger-cli balance --account ACC-001 --watch --interval 2     # poll until Ctrl+C
```

`history` pages with `--limit`/`--cursor`, and `--all` follows `links.next` until exhausted.

---

## 4. The audit commands — `full` only, and auditor-only

```bash
LEDGER_USERNAME=dave LEDGER_PASSWORD=dev-only \
  ledger-cli --profile full audit entries --limit 20

LEDGER_USERNAME=dave LEDGER_PASSWORD=dev-only \
  ledger-cli --profile full audit events --account <accountUid>
```

Two things bite here, both by design:

- **In `standalone` these answer `501`**, with a problem detail, rather than pretending to have an
  audit trail (spec §6.5, §7). That is the correct response, not a fault.
- **`audit events --account` is effectively uid-only.** `dave` is an auditor who owns no accounts,
  so there is nothing for a *name* to resolve against — his `GET /api/v1/accounts` is empty. Pass
  the `accountUid`, which `audit entries` will show you.

A non-empty `audit entries` on a `full` stack is also the strongest single proof the stack is wired
end to end: the trail is populated by a Kafka consumer, so a row means the relay published *and* the
consumer read it back.

---

## 5. Scenarios — the part a single command cannot show

`scenario run` is the CLI's actual reason to exist. Every Java test in this repository drives one
operation against prepared state, longest chain two steps; these are **sequences**, each opening its
own scratch account and asserting the running result at every step.

```bash
ledger-cli scenario run edge-cases
ledger-cli scenario run movement-chain --currency GBP
```

| Scenario | What it proves |
|---|---|
| `movement-chain` | Four movements in a row, running balance checked against `Decimal` arithmetic **after each one**, not just at the end |
| `zero-boundary` | Withdraw to exactly zero, then one more — refused specifically `422 /errors/insufficient-funds`, not merely "an error" |
| `concurrent-withdrawals` | N2: ten parallel withdrawals of 20.00 against 100.00 — exactly five settle, five refused, balance never negative. A `409` is retried, because under optimistic concurrency retrying *is* the contract |
| `racing-replays` | N19: the same `movementUid` deposited five times at once, credited exactly once |
| `consistency-boundary` | Deposit, read `?consistency=strong`, then poll the projection until `streamVersion` catches up — §4.4's read model across the eventual-consistency boundary |
| `edge-cases` | §11's smoke flow: open, deposit, withdraw, verify, replay the same uid, confirm no double credit and a `200` rather than a `201` |
| `rate-limit` | Floods the write bucket and asserts the `429` (§6.1) |

Two behaviours worth knowing before you read a result:

- **`rate-limit` under `standalone` is a vacuous pass, and says so.** `standalone` exempts loopback
  from the limiter, so there is nothing to exhaust; the scenario checks the profile and reports
  `ok` with the reason rather than looping 130 times and failing for a reason that is not a defect.
- **Ordinary commands honour `Retry-After`** on a `429` (bounded to three retries). The `rate-limit`
  scenario deliberately uses a client that does *not*, because it needs to observe the `429` rather
  than survive it.

`scenario run` exits **0** on pass and **1** on fail, printing `PASS`/`FAIL` with a detail line — so
it is usable as a check in a script, not just a thing to read.

---

## 6. The same scenarios as the e2e gate

`scenario run` is the interactive form. The gate form is pytest driving the same code against a
containerised app, and it is one command from the repository root:

```bash
docker compose -f docker/docker-compose.yml up -d --wait
bash scripts/e2e/run-e2e.sh
```

That is CI stage 9, and the full sequence — including reading its output correctly, where `7
deselected` means it tested nothing — is [`docker.md`](docker.md) §6. The CLI's own offline suite is
separate and needs neither Docker nor a running app:

```bash
cd ledger-cli && uv run pytest      # 52 passed, 7 deselected (the e2e marker)
```

---

## 7. Troubleshooting, by symptom

| Symptom | Cause | Fix |
|---|---|---|
| `httpx.ConnectError: [Errno 111] Connection refused`, as a traceback | nothing is listening on `--base-url` | start the app. Takes ~2 s: `GET` is idempotent, so the transport retries three times before giving up |
| `RuntimeError: the full profile needs --token, or --user/--password …` | `--profile full` with no credentials | pass `--user`/`--password`, or `LEDGER_USERNAME`/`LEDGER_PASSWORD`, or `--token` |
| Every request `401` with a valid-looking token | issuer mismatch | mint against the same host string the app validates — `--issuer-uri https://auth.localhost/realms/tiny-ledger`. **Never disable issuer validation** |
| `CERTIFICATE_VERIFY_FAILED` talking to Keycloak | the dev CA is not trusted by this client | `export SSL_CERT_FILE=$PWD/docker/tls/ca.crt`, or run through `scripts/e2e/run-e2e.sh`, which does it for you |
| `403` on a write that should work | the user's role, or ownership | `carol` is read-only; `dave` writes nothing; `mallory` may not touch another owner's account. That is the model working (§6.4) |
| `501` from `audit events` / `audit entries` | you are on `standalone` | those are `full`-only by design (§7) |
| `audit events --account ACC-001` cannot resolve the name | `dave` owns no accounts, so there is nothing to resolve against | pass the `accountUid`; `audit entries` lists them |
| `422 /errors/insufficient-funds` | there is no overdraft (§15) | deposit first — and note this is exactly what `zero-boundary` asserts |
| "has more precision than GBP supports" | more decimals than the currency has | the CLI refuses locally rather than truncating; `10000.5` minor units is a `400` server-side for the same reason |
| `404 /errors/account-not-found` — *"No account named 'ACC-001' among the caller's own accounts"* | the name resolved against nothing | this one is **synthesised by the CLI**, not returned by the server — you are either on a fresh ledger with no accounts, or authenticated as someone who owns none |
| An ambiguous-name error listing several uids | two accounts share a name | pass the `accountUid`. Opening the same name twice is legal and makes two independent streams (N22) |
| `uv: command not found` from `run-e2e.sh` | uv is not installed | §0. The script checks on its first line, so nothing was built or started |

**Errors arrive as tracebacks, not as messages.** A refused connection and a missing credential both
end in a Python stack trace whose *last line* is the real cause. Only `LedgerApiError` — the
ledger's own RFC 7807 problem details — is caught and printed as `status type: detail`. That is a
rough edge, stated rather than dressed up.

---

## What this CLI is not

- **`--json` is accepted and currently does nothing.** The flag parses and sets a setting no command
  reads; output is rich tables and human strings either way. Spec §11 specifies `--json` for machine
  use, so this is a known gap between §11 and the code, not a documented choice. `scenario run`'s
  **exit code** is machine-readable and is the reliable hook today.
- **It has no `ledger-cli` service account.** §6.4's client-credentials row and `ACC-900` do not
  exist in the realm file (§2 above).
- **It seeds nothing.** No `ACC-001` exists until you open one; the seed script §6.4 refers to is
  unbuilt.
- **`pytest-bdd` is not wired.** §9.6's binding of the Gherkin catalogue through this CLI is
  specified and deliberately not built — adding the dependency with no runner to consume it would be
  dead weight (`ledger-cli/NOTES.md`).
- **No gate enforces this document.** Nothing in CI checks documentation in this repository
  (`INDEX.md`, spec §8.4). What *is* gated is the CLI itself: stage 8 runs `ruff`, `pyright --strict`
  and its unit suite on every push, and stage 9 runs the scenarios against the real stack.
