# Tiny Ledger

[![CI](https://github.com/ffroliva/tiny-ledger/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ffroliva/tiny-ledger/actions/workflows/ci.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=alert_status&branch=main)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger&branch=main)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=coverage&branch=main)](https://sonarcloud.io/component_measures?id=ffroliva_tiny-ledger&metric=coverage&branch=main)

[![Reliability](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=reliability_rating&branch=main)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger&branch=main)
[![Security](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=security_rating&branch=main)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger&branch=main)
[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=sqale_rating&branch=main)](https://sonarcloud.io/summary/overall?id=ffroliva_tiny-ledger&branch=main)
[![Duplication](https://sonarcloud.io/api/project_badges/measure?project=ffroliva_tiny-ledger&metric=duplicated_lines_density&branch=main)](https://sonarcloud.io/component_measures?id=ffroliva_tiny-ledger&metric=duplicated_lines_density&branch=main)
[![Licence: MIT](https://img.shields.io/github/license/ffroliva/tiny-ledger)](LICENSE)

<!-- Every badge is live — fetched from CI or SonarCloud at render time, so none can drift from what
     it claims — and every one is pinned to `main`, so it means the same thing in every fork and on
     every branch. Toolchain versions live in `pom.xml`, listed under Prerequisites below, once. -->

An event-sourced banking ledger — accounts, deposits, withdrawals, balances — built as a Spring
Modulith modular monolith. It runs as a single JDK process with nothing installed, or against
Postgres, Redis and Kafka, from one codebase.

## Contents

- [Prerequisites](#prerequisites)
- [Quick start](#quick-start) — running in three commands
- [Where to start](#where-to-start) — what to read, by how long you have
- [Two run modes](#two-run-modes)
- **[Running with Docker](docs/docker.md)** — the `full` profile end to end: image, stack, token,
  a real deposit, teardown. Verified commands, and the responses that look like faults but are not
- **[The `ledger-cli` operator tool](docs/ledger-cli.md)** — installing the Python CLI, how it gets
  a token in `full`, worked deposit/withdraw/balance examples, and the seven scenario sequences
- **[Security material](docs/security-material.md)** — where keys and credentials live, and what
  does not exist yet
- [The engineering, briefly](#the-engineering-briefly)
- [How this was built](#how-this-was-built)

Full documentation index: [`docs/INDEX.md`](docs/INDEX.md).

## Prerequisites

**JDK 25 — and that is the whole list for the default profile.** The Quick start below and
`./mvnw verify` need nothing else: no database, no broker, no configuration, nothing installed.

**It is not the whole list for this repository.** This is a Java service with a Python CLI beside it
and a Docker stack under both, and exercising the *full* stack needs all three at once. Saying "JDK
25" and stopping would be true of the front door and misleading about everything behind it, so the
split is stated per task:

| To run | You need | Why |
|---|---|---|
| **Quick start** (`standalone`) and `./mvnw verify` | **JDK 25** | In-memory event store and cache; `verify` starts **zero containers by construction** (ADR 0003) |
| `./mvnw verify -Pit` — the integration suite | JDK 25 + **Docker** | Testcontainers starts its own Postgres, Redis, Kafka and Keycloak on random ports |
| **`full` mode** — the Compose stack ([`docs/docker.md`](docs/docker.md)) | JDK 25 + **Docker**, Compose v2 + **`openssl`** | The image is produced by buildpacks, which is a daemon build; the four backing services are containers. `openssl` generates the dev certificate Traefik terminates TLS with — it is bundled with Git for Windows and present on every Linux and macOS install. `curl` and `jq` for the runbook's commands |
| `ledger-cli` — `ruff`, `pyright`, its unit tests | **uv** | The Python CLI in `ledger-cli/` is a real component with its own CI gate. Needs no Docker and no running app — [runbook](docs/ledger-cli.md) |
| **The e2e scenarios** (`scripts/e2e/run-e2e.sh`) | JDK 25 + **Docker** + **uv** + `bash` + `openssl` | The seven unmocked scenarios are `pytest` driving the Python CLI **over HTTPS** against the containerised application, behind Traefik — every toolchain in the repository, in one command. The script generates the certificate itself if it is missing |

**Versions.** Java **25** (Corretto in CI). Python **3.11, 3.12 or 3.13** — but installing Python is
not a separate step: `uv` uses a matching interpreter if the machine has one and downloads one if it
does not, which is why CI's `cli` job sets up uv and no Python at all.
Docker is verified on **28.3.0** / Compose **v2.38.1**. Everything else is a wrapper or a locked
dependency: `./mvnw` needs only a JDK, and `uv.lock` is committed and installed with `uv sync
--locked`.

`bash` is listed because `scripts/e2e/*.sh` are shell scripts; on Windows that means Git Bash or WSL.
The Java and Python paths are cross-platform — see the `curl.exe` note under the Quick start.

**One Windows caveat that only bites in `full` mode.** Git for Windows ships a **Schannel**-backed
`curl`, which resolves certificate chains through the Windows store and therefore refuses the private
dev CA even when it is passed with `--cacert`. The `curl` recipes in
[`docs/docker.md`](docs/docker.md) are correct on Linux and macOS; on Windows use the repository's
Python probe, which that document names. **Do not reach for `-k`** — it deletes the check rather than
satisfying it. Nothing in `standalone` is affected: it is plaintext on loopback.

**No gate enforces this table** — nothing in CI checks documentation here (`docs/INDEX.md` says so,
and spec §8.4 records why). Its evidence is `.github/workflows/ci.yml`, whose jobs are split along
exactly these lines: `unit` (a runner with no Docker, on purpose), `integration`, `cli` (uv, no
Docker), and `e2e` (Java, Docker and uv together). If the table and that file ever disagree, the file
is right.

## Quick start

Start it. `standalone` is the default profile; the log prints `AUTH DISABLED (standalone)` and binds
`127.0.0.1:8080`.

```bash
./mvnw spring-boot:run          # macOS / Linux
```
```powershell
.\mvnw.cmd spring-boot:run      # Windows
```

Open an account, deposit into it, then read the balance. Account uids are server-generated, so both
blocks below capture the uid into a variable rather than asking you to paste it between commands —
**each block runs as one copy-paste, unedited.**

**Unix shells** — Linux, macOS, WSL. Uses [`jq`](https://jqlang.github.io/jq/) to read one field out
of the response, and `uuidgen` to mint a movement uid; both are conveniences for this snippet, not
dependencies of the ledger. Without jq, drop the `| jq -r .accountUid` and copy the uid by hand.
(Git Bash on Windows ships neither — use the PowerShell block below, which needs no extra tool.)

```bash
ACC=$(curl -s -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"ACC-001","currency":"GBP"}' | jq -r .accountUid)

MV=$(uuidgen)   # the movement uid is yours to choose — see the note below

curl -X PUT "localhost:8080/api/v1/accounts/$ACC/deposits/$MV" \
  -H 'Content-Type: application/json' \
  -d '{"amount":{"currency":"GBP","minorUnits":10000}}'

curl "localhost:8080/api/v1/accounts/$ACC/balance"
```

**PowerShell** — needs nothing beyond the JDK. `Invoke-RestMethod` parses the response itself, so
there is no `jq` and no second tool to install.

```powershell
$acc = (Invoke-RestMethod -Method Post http://localhost:8080/api/v1/accounts `
  -ContentType 'application/json' `
  -Body '{"name":"ACC-001","currency":"GBP"}').accountUid

$mv = [guid]::NewGuid()   # the movement uid is yours to choose — see the note below

Invoke-RestMethod -Method Put "http://localhost:8080/api/v1/accounts/$acc/deposits/$mv" `
  -ContentType 'application/json' `
  -Body '{"amount":{"currency":"GBP","minorUnits":10000}}' | ConvertTo-Json

Invoke-RestMethod "http://localhost:8080/api/v1/accounts/$acc/balance" | ConvertTo-Json
```

Do not mix the two: in PowerShell, bare `curl` is an alias for `Invoke-WebRequest` and will not take
curl's flags. If you want the curl form on Windows, spell it `curl.exe`. (`uuidgen` ships with macOS
and Linux; on Windows use the PowerShell block, which needs no such tool.)

**Why the movement uid is generated rather than written into this page.** `PUT`ting a movement means
*you* choose its uid, and that uid is the idempotency key — **globally, not per account** (§6.3). A
literal uid printed in a README therefore works exactly once per running instance: open a second
account, paste the same block, and the server correctly answers `409 Idempotency conflict`, because
that key already belongs to a different account's movement. Generating it keeps the snippet
re-runnable, which is the whole point of a quick start.

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

Those two are the Java gates, and a JDK is enough for the first. The repository has a third, which is
neither Java nor optional — the Python CLI in `ledger-cli/` and the e2e scenarios it drives:

```bash
cd ledger-cli && uv sync --locked && uv run pytest   # the CLI's own suite — offline, no Docker
bash scripts/e2e/run-e2e.sh                          # the seven e2e scenarios, from the repo root
```

The e2e run expects the Compose stack already up and needs JDK, Docker and uv together — the full
sequence, and how to read its output, is [`docs/docker.md`](docs/docker.md) §6.

**On Windows, run these in Git Bash, not WSL.** At a PowerShell prompt bare `bash` resolves to
`C:\windows\system32\bash.exe`, which is WSL — a separate Linux environment where none of this
repository's toolchain is installed. Name the shell instead, and the script will tell you if you
land in the wrong one anyway:

```powershell
& "C:\Program Files\Git\bin\bash.exe" scripts/e2e/run-e2e.sh
```

## Where to start

| You have | Read |
|---|---|
| **10 minutes** | [`docs/architecture.md`](docs/architecture.md) — three diagrams: the two run modes, the module boundaries, the domain |
| **20 minutes** | [`docs/agentic-workflow.md`](docs/agentic-workflow.md) — how this was built, including §5, where the agents were wrong |
| **Longer** | [`docs/spec.md`](docs/spec.md) — the full contract. [`docs/INDEX.md`](docs/INDEX.md) navigates everything else |
| **You want to read the code** | `git log` — one commit per reviewed task, with the reasoning in the messages |

## Two run modes

One codebase, one set of domain classes. Only the adapters differ.

| Mode | What runs |
|---|---|
| **`standalone`** (default) | In-memory event store and cache. No database, broker or auth. Binds `127.0.0.1` only |
| **`full`** | PostgreSQL, Redis, Kafka (KRaft) and Keycloak. JWT authentication and role authorisation |

That duality is the design's central bet: a reader who wants the minimal ledger gets it in
one command; a reader who wants production concerns gets those too, without forking the code that
holds the money.

Compose brings up the whole system — Postgres, Redis, Kafka, a Keycloak preloaded with the realm and
its fixture users, and **the application itself**:

```bash
./mvnw spring-boot:build-image -DskipTests                                  # produces tiny-ledger:local
scripts/tls/gen-dev-ca.sh                                                   # throwaway CA + certificate, gitignored
docker compose -f docker/docker-compose.yml --profile app up -d
```

The build is a separate step on purpose. The image is produced by **buildpacks**, not by a
`Dockerfile` that Compose could build for you, so there is exactly one way to make it — see spec §12.
If the tag is missing, `up` fails rather than quietly starting something else.

**In `full`, everything arrives over HTTPS on 443** — the API at `https://app.localhost` and
**Keycloak at `https://auth.localhost`**, split by hostname. Traefik terminates TLS and **nothing
else publishes a port**: not the application, not the management endpoints, not the identity
provider. A published `8080` would leave a plaintext route straight past the terminator, and a
published Keycloak would mint tokens with a different `iss` from the one the app trusts. The
plaintext entrypoint on `80` answers a `301` and serves nothing.
The certificate is generated on demand, never committed, and **CI holds no certificate secret**: it
runs that same script inside the run. TLS stops at Traefik — the backing services and Keycloak are
still plaintext, which spec §6.4a names as a gap rather than leaving implied.

**Full runbook: [`docs/docker.md`](docs/docker.md)** — getting a token, moving money, proving the
Kafka hop landed, and a symptom→cause table for the things that look broken and are not.

`--profile app` is what adds the application. A plain `up` still starts exactly the four backing
services, which is what you want when you would rather run the app from your IDE:

```bash
docker compose -f docker/docker-compose.yml --profile app up -d --wait
./mvnw -q -DskipTests package
E2E_MODE=jar ./scripts/e2e/run-e2e.sh
```

The **application** binds `8080` on your host with no proxy and no TLS, which is what running a jar
actually is; CI exercises this as stage 9b. But `--profile app` is still required, because
**Keycloak is behind Traefik and publishes no port of its own** — and the JVM needs the dev CA in a
truststore to fetch its signing keys, which `run-e2e.sh` passes for you and a bare
`spring-boot:run` does not. [`docs/urls-and-tls.md`](docs/urls-and-tls.md) is the full map;
[`docs/pitfalls.md`](docs/pitfalls.md) has the symptom if you skip a step.

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

**Observability is built in full** (spec §14 step 9, v3.41) — health probes, the outbox-lag gauge,
distributed tracing, OTLP export, JSON logs in `full`, and the opt-in Collector described above.
`/actuator/health/liveness` and `/actuator/health/readiness` answer unauthenticated on management
port `9090`, and nothing else under `/actuator` is reachable at all (spec §6.6).

**Not yet built:** the FAPI/DPoP work (spec §7.2), the seed script for the `ACC-00x` fixture
accounts (§6.4), stage 9's pytest-bdd binding of the whole Gherkin catalogue (§9.6), image
publishing (§12.1 stage 12), and Kubernetes manifests (ADR 0005). The auditor endpoints
`GET /api/v1/accounts/{id}/events` and `GET /api/v1/audit/entries` are `full`-only; in `standalone`
both answer `501` with a problem detail rather than pretending (spec §6.5, §7).

## How this was built

The code was written by AI. Every design decision was made by a human, and the record shows both —
including where the agents were wrong, where a review pass missed what the next one caught, and where
the human overrode the analysis.

That account is [`docs/agentic-workflow.md`](docs/agentic-workflow.md). The rules any agent reads
before touching this repository — Claude, Codex, Cursor, Gemini or another — are in
[`AGENTS.md`](AGENTS.md), so they are the same whoever picks it up.
