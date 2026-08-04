# Tiny Ledger

An event-sourced banking ledger — accounts, deposits, withdrawals, balances — built as a Spring
Modulith modular monolith.

**Start here:** [`docs/architecture.md`](docs/architecture.md) — three diagrams covering the two run
modes, the module boundaries and the domain. Ten minutes, and you have the argument.

Full design contract: [`docs/spec.md`](docs/spec.md). How it was built:
[`docs/agentic-workflow.md`](docs/agentic-workflow.md). Documentation map:
[`docs/INDEX.md`](docs/INDEX.md).

## Quickstart (standalone)

Prerequisite: **JDK 25**. No database, no broker, no auth to configure — `standalone` is the
default Spring profile and the only mode this build ships.

```bash
./mvnw spring-boot:run
```

The startup log prints `AUTH DISABLED (standalone)` and binds `127.0.0.1:8080` only.

With the app running, open an account, deposit into it, and read the balance:

```bash
DEP_UID=$(python -c "import uuid;print(uuid.uuid4())")
ACC=$(curl -s -X POST 127.0.0.1:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"name":"ACC-001","currency":"GBP"}' | python -c "import json,sys;print(json.load(sys.stdin)['accountUid'])")
curl -s -X PUT 127.0.0.1:8080/api/v1/accounts/$ACC/deposits/$DEP_UID -H 'Content-Type: application/json' \
  -d '{"amount":{"currency":"GBP","minorUnits":10000}}'
curl -s 127.0.0.1:8080/api/v1/accounts/$ACC/balance
```

The `POST` returns `201` with the account; the `PUT` returns the deposit's transaction, including
`balanceAfter`; the final `GET` returns the balance with its staleness markers, `asOf` and
`streamVersion` (spec §4.4).

Money is one shape on the wire, everywhere — requests, responses, balances:

```json
{ "currency": "GBP", "minorUnits": 10000 }
```

`minorUnits` is an integer (pence/cents). Sending a non-integer (`10000.5`) is rejected `400` —
there is no silent truncation to an int.

## Run modes

The full design ships two run modes from one codebase (spec §1); both are runnable in this build.

| Mode | Command | What runs | Status |
|---|---|---|---|
| **`standalone`** (default) | `./mvnw spring-boot:run` | In-memory event store, in-memory cache, no auth, no broker. Binds `127.0.0.1` only. | Implemented — this build. |
| **`full`** | `docker compose -f docker/docker-compose.yml up -d`, then `./mvnw spring-boot:run -Dspring-boot.run.profiles=full` | PostgreSQL, Redis, Kafka (KRaft, no ZooKeeper). Auth (Keycloak) and observability (OTel, Grafana, …) arrive in later plans (spec §14). | Implemented — this build. |

Both modes run the same domain code and the same core ledger API; only the adapters differ.

The `full`-profile adapters are exercised against real Postgres/Redis/Kafka via Testcontainers in
the integration test suite: `./mvnw verify -Pit`.

## Auditor endpoints

`GET /api/v1/accounts/{accountUid}/events` and `GET /api/v1/audit/entries` are `full`-profile-only
(they need Kafka and the auth-scoped `ledger:auditor` role). In `standalone` both answer `501` with
an RFC 7807 problem detail of type `/errors/not-available-in-standalone`.

## Further reading

- [`docs/spec.md`](docs/spec.md) — the full technical specification (architecture, API, quality gates).
- [`docs/agentic-workflow.md`](docs/agentic-workflow.md) — how this repository was built.
- [`docs/INDEX.md`](docs/INDEX.md) — the documentation map.
