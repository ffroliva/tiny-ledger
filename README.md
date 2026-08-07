# Tiny Ledger

[![CI](https://github.com/ffroliva/tiny-ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/ffroliva/tiny-ledger/actions/workflows/ci.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=alert_status)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=coverage)](https://sonarcloud.io/component_measures?id=ffroliva_tiny-ledger&metric=coverage)

[![Reliability](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=reliability_rating)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger)
[![Security](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=security_rating)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger)
[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=sqale_rating)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger)
[![Duplication](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=duplicated_lines_density)](https://sonarcloud.io/component_measures?id=ffroliva_tiny-ledger&metric=duplicated_lines_density)

<!-- Every badge above is LIVE: each is fetched from CI or SonarCloud at render time and cannot drift
     from the thing it claims. That is deliberate, and it is why there is no hand-written "Java 25" or
     "Spring Boot 4.1" badge here — a version badge is a copy of pom.xml with nothing keeping the two
     in step, and this repository has already spent a documentation pass deleting exactly that kind of
     claim (spec v3.8, finding CR14). The toolchain versions live in `pom.xml` and are listed under
     Prerequisites below, once.

     The coverage figure is unit AND integration coverage combined (.github/workflows/ci.yml merges
     two JaCoCo reports), not the unit suite alone — see docs/spec.md §9. -->

An event-sourced banking ledger — accounts, deposits, withdrawals, balances — built as a Spring
Modulith modular monolith. It runs as a single JDK process with nothing installed, or against
Postgres, Redis and Kafka, from one codebase.

## Contents

- [Prerequisites](#prerequisites)
- [Quick start](#quick-start) — running in three commands
- [If you are reviewing this](#if-you-are-reviewing-this) — where to look, by how long you have
- [Two run modes](#two-run-modes)
- [The engineering, briefly](#the-engineering-briefly)
- [How this was built](#how-this-was-built)

Full documentation index: [`docs/INDEX.md`](docs/INDEX.md).

## Prerequisites

**JDK 25.** That is the whole list for the default profile — no database, no broker, no
configuration. Docker is needed only for `full` mode and the integration suite.

## Quick start

Start it. `standalone` is the default profile; the log prints `AUTH DISABLED (standalone)` and binds
`127.0.0.1:8080`.

```bash
./mvnw spring-boot:run          # macOS / Linux
```
```powershell
.\mvnw.cmd spring-boot:run      # Windows
```

Open an account, and copy the `accountUid` from the response:

```bash
curl -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"ACC-001","currency":"GBP"}'
```

Deposit into it, then read the balance — substitute the `accountUid` you just got:

```bash
curl -X PUT localhost:8080/api/v1/accounts/<accountUid>/deposits/11111111-1111-4111-8111-111111111111 \
  -H 'Content-Type: application/json' \
  -d '{"amount":{"currency":"GBP","minorUnits":10000}}'

curl localhost:8080/api/v1/accounts/<accountUid>/balance
```

On Windows use `curl.exe` — PowerShell aliases bare `curl` to `Invoke-WebRequest`, which does not
take these flags. PowerShell 7 passes the single-quoted JSON through unchanged; otherwise the
arguments are identical.

**Now run the deposit again.** Same URL, same body, same balance. The movement UID in the path is the
idempotency key, enforced by a unique index, so a retried request is answered rather than reapplied —
which is the property a payments client actually needs from a retry.

`POST` returns `201` with the account. `PUT` returns the transaction including `balanceAfter`. `GET`
returns the balance with its staleness markers, `asOf` and `streamVersion`.

Run the tests:

```bash
./mvnw verify          # unit, architecture and BDD — starts zero containers
./mvnw verify -Pit     # integration suite against real Postgres, Redis, Kafka and Keycloak
```

## If you are reviewing this

| You have | Read |
|---|---|
| **10 minutes** | [`docs/architecture.md`](docs/architecture.md) — three diagrams: the two run modes, the module boundaries, the domain |
| **20 minutes** | [`docs/agentic-workflow.md`](docs/agentic-workflow.md) — how this was built, including §5, where the agents were wrong |
| **Longer** | [`docs/spec.md`](docs/spec.md) — the full contract. [`docs/INDEX.md`](docs/INDEX.md) navigates everything else |
| **You want to judge the code** | `git log` — one commit per reviewed task, with the reasoning in the messages |

## Two run modes

One codebase, one set of domain classes. Only the adapters differ.

| Mode | What runs |
|---|---|
| **`standalone`** (default) | In-memory event store and cache. No database, broker or auth. Binds `127.0.0.1` only |
| **`full`** | PostgreSQL, Redis, Kafka (KRaft) and Keycloak. JWT authentication and role authorisation |

That duality is the design's central bet: a reviewer who wants the ledger from the brief gets it in
one command; a reviewer who wants production concerns gets those too, without forking the code that
holds the money.

Compose brings up the infrastructure, including a Keycloak preloaded with the realm and its fixture
users; the jar then runs on the host against it:

```bash
docker compose -f docker/docker-compose.yml up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=full
```

Every route then requires a bearer token, so `full` is the mode to read if you care about the
authorisation model (spec §6.4) rather than the ledger mechanics.

The integration suite is independent of both — Testcontainers starts its own Postgres, Redis, Kafka
and Keycloak on random ports, so `./mvnw verify -Pit` exercises the real authenticated path without
anything running beforehand.

## The engineering, briefly

Event-sourced write side with optimistic concurrency on the stream version, and client-generated
movement UIDs as the idempotency key enforced by a unique index. CQRS read side fed by domain events,
served from a cache with the projection as fallback, carrying explicit staleness markers rather than
pretending to be current. Hexagonal boundaries enforced at build time by ArchUnit — the domain
depends on no framework — and module boundaries verified by Spring Modulith. Errors are RFC 7807
throughout, with the machine-readable `type` as the contract.

Money is one shape everywhere — requests, responses, balances:

```json
{ "currency": "GBP", "minorUnits": 10000 }
```

`minorUnits` is an integer of pence or cents. A non-integer (`10000.5`) is rejected `400`; there is
no silent truncation.

The API surface follows Starling Bank's public API where its conventions fit a ledger, so naming,
money representation and error shape are settled by precedent rather than by taste.

**Authorisation.** In `full`, the filter chain enforces `ledger:reader` / `ledger:writer` /
`ledger:auditor` per route. A fourth role, `ledger:admin`, is deliberately *not* a chain role: it
widens ownership inside `RecordMovementService` for change operations only. An admin may move money
on an account they do not own, but may not read its balance or transactions, and is not an auditor.
Every event records the acting principal as `actor`, so the audit trail carries who acted alongside
who owns (spec §6.4, §2.3).

**Not yet built:** observability, and the FAPI/DPoP work. The auditor endpoints
`GET /api/v1/accounts/{id}/events` and `GET /api/v1/audit/entries` are `full`-only; in `standalone`
both answer `501` with a problem detail rather than pretending (spec §6.5, §7).

## How this was built

The code was written by AI. Every design decision was made by a human, and the record shows both —
including where the agents were wrong, where a review pass missed what the next one caught, and where
the human overrode the analysis.

That account is [`docs/agentic-workflow.md`](docs/agentic-workflow.md). The rules any agent reads
before touching this repository — Claude, Codex, Cursor, Gemini or another — are in
[`AGENTS.md`](AGENTS.md), so they are the same whoever picks it up.
