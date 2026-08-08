# Tiny Ledger

[![CI](https://github.com/ffroliva/tiny-ledger/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ffroliva/tiny-ledger/actions/workflows/ci.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=alert_status&branch=main)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger&branch=main)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=coverage&branch=main)](https://sonarcloud.io/component_measures?id=ffroliva_tiny-ledger&metric=coverage&branch=main)

[![Reliability](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=reliability_rating&branch=main)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger&branch=main)
[![Security](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=security_rating&branch=main)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger&branch=main)
[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=sqale_rating&branch=main)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger&branch=main)
[![Duplication](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=duplicated_lines_density&branch=main)](https://sonarcloud.io/component_measures?id=ffroliva_tiny-ledger&metric=duplicated_lines_density&branch=main)
[![Licence: MIT](https://img.shields.io/github/license/ffroliva/tiny-ledger)](LICENSE)

<!-- Every badge above is LIVE: each is fetched from CI or SonarCloud at render time and cannot drift
     from the thing it claims. That is deliberate, and it is why there is no hand-written "Java 25" or
     "Spring Boot 4.1" badge here — a version badge is a copy of pom.xml with nothing keeping the two
     in step, and this repository has already spent a documentation pass deleting exactly that kind of
     claim (spec v3.8, finding CR14). The toolchain versions live in `pom.xml` and are listed under
     Prerequisites below, once.

     The coverage figure is unit AND integration coverage combined (.github/workflows/ci.yml merges
     two JaCoCo reports), not the unit suite alone — see docs/spec.md §9.

     EVERY BADGE IS PINNED TO `main` (`?branch=main` / `&branch=main`), and that is a statement rather
     than a default. Without it the Sonar badges fall back to the main branch implicitly and the CI
     badge to the default branch — correct by accident, and silently wrong the day either default
     changes. Pinned, these badges mean one thing on every branch and in every fork: *this is the state
     of main*.

     They deliberately do NOT follow the branch you are reading. A badge URL is static text in a file,
     so per-branch badges would mean editing this block on every branch — a README conflict in every
     pull request, and a stale claim the first time someone forgets. It would not work anyway:
     SonarCloud analyses feature branches as PULL REQUESTS, and a pull request has no badge URL. The
     honest answer to "is this branch healthy" is the pull request's own checks, which are already
     required and already visible on the PR.

     The licence badge is not pinned because a licence has no branch. -->

An event-sourced banking ledger — accounts, deposits, withdrawals, balances — built as a Spring
Modulith modular monolith. It runs as a single JDK process with nothing installed, or against
Postgres, Redis and Kafka, from one codebase.

## Contents

- [Prerequisites](#prerequisites)
- [Quick start](#quick-start) — running in three commands
- [If you are reviewing this](#if-you-are-reviewing-this) — where to look, by how long you have
- [Two run modes](#two-run-modes)
- **[Running with Docker](docs/docker.md)** — the `full` profile end to end: image, stack, token,
  a real deposit, teardown. Verified commands, and the responses that look like faults but are not
- **[Security material](docs/security-material.md)** — where keys and credentials live, and what
  does not exist yet
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

Compose brings up the whole system — Postgres, Redis, Kafka, a Keycloak preloaded with the realm and
its fixture users, and **the application itself**:

```bash
./mvnw spring-boot:build-image -DskipTests                                  # produces tiny-ledger:0.1.0-SNAPSHOT
docker compose -f docker/docker-compose.yml --profile app up -d
```

The build is a separate step on purpose. The image is produced by **buildpacks**, not by a
`Dockerfile` that Compose could build for you, so there is exactly one way to make it — see spec §12.
If the tag is missing, `up` fails rather than quietly starting something else.

**Full runbook: [`docs/docker.md`](docs/docker.md)** — getting a token, moving money, proving the
Kafka hop landed, and a symptom→cause table for the things that look broken and are not.

`--profile app` is what adds the application. A plain `up` still starts exactly the four backing
services, which is what you want when you would rather run the app from your IDE:

```bash
docker compose -f docker/docker-compose.yml up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=full
```

Every route then requires a bearer token, so `full` is the mode to read if you care about the
authorisation model (spec §6.4) rather than the ledger mechanics.

The integration suite is independent of both — Testcontainers starts its own Postgres, Redis, Kafka
and Keycloak on random ports, so `./mvnw verify -Pit` exercises the real authenticated path without
anything running beforehand.

## Telemetry, and why it is off until you ask for it

The application always *creates* spans and meters — that is what puts a trace id and a span id on
every log line — but it **exports nothing by default**. There is no local Prometheus, Grafana, Tempo
or Loki here, deliberately: a five-container visualisation stack that most runs never open is a cost
paid on every `docker compose up` for a benefit taken occasionally (spec §6.6).

Turning it on is one extra Compose profile and one flag on each exporter:

```bash
set -a; . ./.env.grafana; set +a          # names in .env.example; values never committed
docker compose -f docker/docker-compose.yml --profile observability up -d

./mvnw spring-boot:run -Dspring-boot.run.profiles=full \
  -Dspring-boot.run.arguments="\
    --management.tracing.export.otlp.enabled=true \
    --management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces \
    --management.otlp.metrics.export.enabled=true \
    --management.otlp.metrics.export.url=http://localhost:4318/v1/metrics"
```

Without the profile, `docker compose up` starts exactly the same four containers it always did.

One Collector receives OTLP and forwards to hosted Grafana Cloud. It **tail-samples**: the
application records 100% of traces because only the Collector can see how a trace *ended*, so every
error and every request slower than 150 ms is kept and the rest is sampled at 5%. Metrics are never
sampled — sampling a counter does not thin it, it corrupts it.

**Nothing in CI runs any of this, and CI holds no Grafana credential.** Step 9's gate is
`OtlpExportIT`, which starts its own Collector with a file exporter and asserts that real spans and
metrics arrive — so a fork's build passes with no secret and no third-party account. The Compose
Collector above has **no automated check at all**; it is proven by being run, and saying so is the
point (`AGENTS.md`: an unenforced rule is a hope).

Two things worth knowing before you debug it. The Grafana OTLP gateway accepts **OTLP/HTTP JSON**, so
the endpoint can be probed by hand with `curl` and no OpenTelemetry dependency; the application
itself still uses `http/protobuf`. And a value in `.env.grafana` that contains a space **must be
quoted** — `Authorization=Basic <token>` sourced unquoted truncates at the space and the Collector
authenticates with the word `Basic`, which fails as a 401 that looks like a bad token.

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

**Not yet built:** tracing, OTLP export, JSON logs and the Collector — the rest of observability, and
the FAPI/DPoP work. Health probes and the outbox-lag gauge *are* built: `/actuator/health/liveness`
and `/actuator/health/readiness` answer unauthenticated on management port `9090`, and nothing else
under `/actuator` is reachable at all (spec §6.6). The auditor endpoints
`GET /api/v1/accounts/{id}/events` and `GET /api/v1/audit/entries` are `full`-only; in `standalone`
both answer `501` with a problem detail rather than pretending (spec §6.5, §7).

## How this was built

The code was written by AI. Every design decision was made by a human, and the record shows both —
including where the agents were wrong, where a review pass missed what the next one caught, and where
the human overrode the analysis.

That account is [`docs/agentic-workflow.md`](docs/agentic-workflow.md). The rules any agent reads
before touching this repository — Claude, Codex, Cursor, Gemini or another — are in
[`AGENTS.md`](AGENTS.md), so they are the same whoever picks it up.
