# Tiny Ledger

An event-sourced banking ledger — accounts, deposits, withdrawals, balances — built as a Spring
Modulith modular monolith, runnable either as a single JDK process or against Postgres, Redis and
Kafka, from one codebase.

**The code was written by AI. Every design decision was made by a human, and the record shows both.**
That is the part worth your attention: this repository is an experiment in agentic engineering with a
human in the loop, and it is documented as such — including where the agents got things wrong.

---

## Run it

Prerequisite: **JDK 25**. No database, no broker, nothing to configure — `standalone` is the default
profile.

```bash
./mvnw spring-boot:run
```

The log prints `AUTH DISABLED (standalone)` and binds `127.0.0.1:8080` only. Then open an account,
deposit into it, and read the balance:

```bash
DEP_UID=$(python -c "import uuid;print(uuid.uuid4())")
ACC=$(curl -s -X POST 127.0.0.1:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"name":"ACC-001","currency":"GBP"}' | python -c "import json,sys;print(json.load(sys.stdin)['accountUid'])")
curl -s -X PUT 127.0.0.1:8080/api/v1/accounts/$ACC/deposits/$DEP_UID -H 'Content-Type: application/json' \
  -d '{"amount":{"currency":"GBP","minorUnits":10000}}'
curl -s 127.0.0.1:8080/api/v1/accounts/$ACC/balance
```

`POST` returns `201` with the account. `PUT` returns the transaction including `balanceAfter`. `GET`
returns the balance with its staleness markers, `asOf` and `streamVersion`.

Money is one shape everywhere — requests, responses, balances:

```json
{ "currency": "GBP", "minorUnits": 10000 }
```

`minorUnits` is an integer of pence or cents. A non-integer (`10000.5`) is rejected `400`; there is no
silent truncation.

```bash
./mvnw verify          # unit, architecture and BDD tests — starts zero containers
./mvnw verify -Pit     # integration suite against real Postgres, Redis and Kafka
```

---

## What this is really demonstrating

Anyone can have an AI write a ledger. The interesting question is whether the *engineering discipline*
survives contact with generated code. The claim this repository makes is that it can, provided a human
owns the decisions and the process leaves evidence.

**Decisions are recorded with their rejected alternatives.** `docs/spec.md` is a contract, not a
description; the ADRs in `docs/adr/` each state what was chosen, what was rejected, and what the choice
costs. Where the human overrode the AI — including a minimalism analysis that argued, correctly against
the brief as written, for building far less — the override and its reasoning are on record.

**The agents' mistakes are kept, not hidden.** `docs/agentic-workflow.md` §5 exists specifically to
record where agents were wrong: an implementation agent orphaned by a closed session that finished its
work with nobody left to report to; a test that trusted a remembered framework default instead of
checking the jar and asserted against the wrong topic name. A process document that records only
successes is marketing.

**Review is treated as fallible.** Plan 2 went through three independent review passes. The third found
a defect *inside the second's own fix*. That is recorded too, because "reviewed" is not a binary, and a
single pass over money-handling code is optimism.

**Gates are proven to fail, not observed to pass.** A green build is not evidence that a rule is
enforced — an architecture rule matching zero classes passes silently. So the rules here are checked by
deliberately violating them and confirming the build breaks. The same standard applies to tests: one
that would still pass with its fix reverted is not coverage, and several such tests were found and
replaced.

**The human decides; the AI executes and argues.** Scope, architecture, trade-offs, what to defer and
what to refuse — those were human calls, made against AI analysis that was often right and sometimes
confidently wrong. `AGENTS.md` is what any agent — Claude, Codex, Cursor, Gemini or another — reads
before touching this repository, so the rules are the same whoever picks it up.

---

## If you are reviewing this

| You have | Read |
|---|---|
| 10 minutes | [`docs/architecture.md`](docs/architecture.md) — three diagrams: the two run modes, the module boundaries, the domain |
| 20 minutes | [`docs/agentic-workflow.md`](docs/agentic-workflow.md) §5 (where the agents were wrong), §6 (decisions, human over agent), §7 (per-phase gate record with real numbers) |
| Longer | [`docs/spec.md`](docs/spec.md) — the full contract, and [`docs/INDEX.md`](docs/INDEX.md) to navigate everything else |
| You want to judge the code | `git log` — one commit per reviewed task, and the commit messages carry the reasoning |

---

## Run modes

Both ship in this build, from one codebase, and run the same domain code — only the adapters differ.

| Mode | Command | What runs |
|---|---|---|
| **`standalone`** (default) | `./mvnw spring-boot:run` | In-memory event store and cache. No database, broker or auth. Binds `127.0.0.1` only. |
| **`full`** | `docker compose -f docker/docker-compose.yml up -d` then `./mvnw spring-boot:run -Dspring-boot.run.profiles=full` | PostgreSQL, Redis and Kafka (KRaft) — infrastructure only. Compose carries **no Keycloak and no app service**; the jar runs on the host, and the Keycloak the `full` chain needs is started by the integration suite's Testcontainers, not by this file. |

That duality is the design's central bet: a reviewer who wants the tiny ledger from the brief gets it in
one command, and a reviewer who wants production concerns gets those too, without forking the code that
holds the money.

Authentication and role authorisation are built: `full` requires a JWT issued by a real Keycloak realm
(spec §6.4), and the filter chain enforces `ledger:reader` / `ledger:writer` / `ledger:auditor` per
route. **Not yet built:** observability and the FAPI/DPoP work. The auditor endpoints
`GET /api/v1/accounts/{id}/events` and `GET /api/v1/audit/entries` are `full`-only; in `standalone` both
answer `501` with an RFC 7807 problem detail — see [`docs/superpowers/plans/`](docs/superpowers/plans/).

---

## The engineering, briefly

Event-sourced write side with optimistic concurrency on the stream version, and client-generated
movement UIDs as the idempotency key enforced by a unique index. CQRS read side fed by domain events,
served from a cache with the projection as fallback, carrying explicit staleness markers rather than
pretending to be current. Hexagonal boundaries enforced at build time by ArchUnit — the domain depends
on no framework — and module boundaries verified by Spring Modulith. Errors are RFC 7807 throughout, with
the machine-readable `type` as the contract.

The API surface follows Starling Bank's public API where its conventions fit a ledger, so that
naming, money representation and error shape are settled by precedent rather than by taste.
