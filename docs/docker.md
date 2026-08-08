# Running Tiny Ledger with Docker — the `full` profile, end to end

**This is a runbook, not a design document.** Every command below was executed against this
repository on 2026-08-08 and the outputs are the ones that came back. Where something is surprising —
a `403` that is correct, a `404` that is correct, a port that is already taken — it is called out
where you will hit it rather than in a troubleshooting appendix.

`README.md`'s Quick start covers **`standalone`**: one command, no Docker, no auth. This covers
**`full`**: Postgres, Redis, Kafka, Keycloak, and the application itself as a container. If you only
want to see the ledger work, use the README. Read this when you want the production-shaped system.

> **Which document?** Contract questions → [`spec.md`](spec.md). Why the system is shaped this way →
> [`architecture.md`](architecture.md). Where keys and secrets live → [`security-material.md`](security-material.md).

---

## 0. Prerequisites, and one thing to check first

- Docker with Compose v2 (`docker compose`, not `docker-compose`). Verified on Docker **28.3.0**.
- JDK 25 and the Maven wrapper in this repository.
- **`uv`** — for §6 only, the e2e suite. `scripts/e2e/run-e2e.sh` ends in `uv run pytest -m e2e`, so
  without it the runbook fails at its last step having built and started everything. uv provisions
  the Python interpreter itself (3.11+), so Python is not a separate install.
- `curl` and `jq` — every verification command below is written with them. On Windows use
  `curl.exe`; PowerShell aliases bare `curl` to `Invoke-WebRequest`, which does not take these flags.
- `bash`, for the `scripts/e2e/*.sh` helpers. Git Bash or WSL on Windows.

Sections 1–5 need only Docker and the JDK. **Section 6 is the one that needs all three toolchains**
at once — Java to build the image, Docker to run the stack, uv to drive it — which is the honest
prerequisite list for "test the full stack", as opposed to the JDK-only list that gets you the
`standalone` ledger from the README.

**Check your port 5432 before you start.** It is the most contested port on a developer machine, and
the failure is worse than a refused bind: without the guard this repository now carries, the
application would connect to *the other* Postgres and read and write it silently.

```bash
docker ps --format '{{.Names}}\t{{.Ports}}' | grep 5432 || echo "5432 is free"
```

If it is taken, set a different **host** port and keep it set for every command in this document:

```bash
export TINY_LEDGER_PG_PORT=55432        # bash
$env:TINY_LEDGER_PG_PORT = '55432'      # PowerShell
```

This only moves the *published* port. The application dials `postgres:5432` inside the Compose
network and is unaffected — which is one of the things containerising it bought.

---

## 1. Build the image

```bash
./mvnw spring-boot:build-image -DskipTests
```

```
Successfully built image 'docker.io/library/tiny-ledger:0.1.0-SNAPSHOT'
```

**The build is a separate step on purpose, and Compose will not do it for you.** The image is
produced by [Paketo buildpacks](https://paketo.io) — there is no `Dockerfile`, so there is nothing
for Compose to build, and the `app` service deliberately has no `build:` key. One way to produce the
artefact. If the tag is missing, `up` fails with a pull error rather than starting something else.

The first run pulls the builder and takes several minutes; after that it is about 90 seconds.

```bash
docker images tiny-ledger --format '{{.Repository}}:{{.Tag}}\t{{.Size}}'
```
```
tiny-ledger:0.1.0-SNAPSHOT      804MB
```

**The JVM AOT cache is on**, trained during the build under the `standalone` profile. Measured over
three runs each: **6.588 s → 3.011 s to start, −54 %**.

---

## 2. Start the whole stack

```bash
docker compose -f docker/docker-compose.yml --profile app up -d --wait
```

```
tiny-ledger-postgres-1  Healthy
tiny-ledger-redis-1     Healthy
tiny-ledger-kafka-1     Healthy
tiny-ledger-keycloak-1  Healthy
tiny-ledger-app-1       Started
```

**`--profile app` is what adds the application.** A plain `up` starts exactly the four backing
services — that is the mode you want when you would rather run the app from your IDE:

```bash
docker compose -f docker/docker-compose.yml config --services | sort   # 4 names
COMPOSE_PROFILES=app \
docker compose -f docker/docker-compose.yml config --services | sort   # 5 names
```

**`--wait` does not mean the application is ready.** It blocks on *healthchecks*, and the `app`
service has none — the run image is `ubuntu-noble-run-tiny`, which ships no shell, so there is no
`CMD` form a healthcheck could use. Compose returns as soon as the container **starts**. Use step 3
as the readiness signal.

---

## 3. Is it actually up?

The management endpoints are on **port 9090**, not 8080.

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9090/actuator/health/readiness   # 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9090/actuator/health/liveness    # 200
```

```json
{"status":"UP"}
```

**Two responses that look like faults and are not:**

| Request | Answer | Why it is correct |
|---|---|---|
| `:9090/actuator/health` | **403** | The health *root* is `denyAll`. Only the probe sub-paths are exposed (spec §6.6). A `200` here would be the bug. |
| `:8080/actuator/health` | **404** | Actuator is on the management port. Nothing is wrong. |

**Do not use `curl -sf` to check these.** It exits non-zero on `403` and `404` alike, so both become
indistinguishable from a container that never started. Print the status code.

If readiness never reaches 200, read the log — and note that `docker exec` is of limited use here,
because the image has no shell and no coreutils:

```bash
docker compose -f docker/docker-compose.yml --profile app logs app | tail -40
```

---

## 4. Get a token

Every route in `full` requires a bearer token. Keycloak is published on **8081** with a fixture realm.

```bash
TOKEN=$(curl -s -X POST \
  'http://127.0.0.1:8081/realms/tiny-ledger/protocol/openid-connect/token' \
  -d 'grant_type=password' \
  -d 'client_id=ledger-test' \
  -d 'username=alice' \
  -d 'password=dev-only' | jq -r .access_token)
```

**These are committed test-fixture credentials, marked "never deploy".** See
[`security-material.md`](security-material.md) for why they are in the repository in plain sight and
what would have to change before anything resembling them went near a real deployment.

**The realm's users, and what each is for** — they exist to make authorisation testable, so pick the
one whose refusal you want to see:

| User | Realm roles | Use it to see |
|---|---|---|
| `alice`, `bob`, `mallory` | `ledger:writer`, `ledger:reader` | the ordinary path; `mallory` for "someone else's account" |
| `carol` | `ledger:reader` | a write refused with `403` |
| `dave` | `ledger:auditor` | the audit trail across all accounts, and reads refused |
| `trent` | `writer`, `reader`, `admin` | moving money on behalf of another owner |
| `nobody` | *(none)* | an authenticated caller with no authority at all |

Password is `dev-only` for all of them.

**The issuer is pinned, and this is the one piece of configuration worth understanding.** Keycloak
normally derives the `iss` claim from the caller's `Host` header, which means a token minted through
`127.0.0.1:8081` and one minted through `localhost:8081` carry *different issuers* and only one of
them authenticates. Measured here before it was fixed:

```
minted via 127.0.0.1:8081  ->  iss = http://127.0.0.1:8081/realms/tiny-ledger  ->  401
minted via localhost:8081  ->  iss = http://localhost:8081/realms/tiny-ledger  ->  200
```

`KC_HOSTNAME` now pins it, so either spelling works. The application validates that public issuer
while fetching the signing keys in-network (`jwk-set-uri` → `keycloak:8080`) — issuer **and**
audience validation stay fully enforced. Nothing is relaxed to make this convenient.

---

## 5. Move some money

```bash
# Open an account — note the field is `name`, not `ownerName`
ACC=$(curl -s -X POST http://127.0.0.1:8080/api/v1/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"ACC-001","currency":"EUR"}' | jq -r .accountUid)

# Deposit. The UID in the PATH is the idempotency key, and amount is an OBJECT, not a string.
curl -s -X PUT "http://127.0.0.1:8080/api/v1/accounts/$ACC/deposits/$(uuidgen)" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount":{"currency":"EUR","minorUnits":10000}}' | jq

curl -s "http://127.0.0.1:8080/api/v1/accounts/$ACC/balance" -H "Authorization: Bearer $TOKEN" | jq
curl -s "http://127.0.0.1:8080/api/v1/accounts/$ACC/transactions" -H "Authorization: Bearer $TOKEN" | jq
```

Measured responses:

```
POST /api/v1/accounts                      -> 201
PUT  /api/v1/accounts/{id}/deposits/{uid}  -> 201  status SETTLED, balanceAfter EUR 10000 minor
GET  /api/v1/accounts/{id}/balance         -> 200  streamVersion 2
GET  /api/v1/accounts/{id}/transactions    -> 200  the deposit
```

**Run the deposit again with the same UID.** Same response, same balance — the movement UID is the
idempotency key, enforced by a unique index, so a retry is *answered* rather than reapplied.

Two mistakes that produce a `400` and look like a server fault:

- `{"amount":"100.00"}` — amount is an **object**: `{"currency":"EUR","minorUnits":10000}`.
- `POST .../movements` — there is no such route. Deposits and withdrawals are `PUT` to
  `.../deposits/{uid}` and `.../withdrawals/{uid}`.

### Prove the Kafka hop actually happened

The audit trail is populated by a Kafka consumer, so a non-empty response here means the relay
published *and* the consumer read it back — which is the strongest single check that the stack is
wired end to end, not just answering health probes.

```bash
AUDIT=$(curl -s -X POST 'http://127.0.0.1:8081/realms/tiny-ledger/protocol/openid-connect/token' \
  -d 'grant_type=password' -d 'client_id=ledger-test' \
  -d 'username=dave' -d 'password=dev-only' | jq -r .access_token)

curl -s http://127.0.0.1:8080/api/v1/audit/entries -H "Authorization: Bearer $AUDIT" | jq
```

---

## 6. Run the e2e suite against the container

**This step needs `uv` on top of Docker and the JDK** (§0). The script starts the app service, waits
for it, then hands over to `pytest` inside `ledger-cli/`; it does not install anything for you.

```bash
bash scripts/e2e/run-e2e.sh
```

```
collected 59 items / 52 deselected / 7 selected
7 passed, 52 deselected in 19.92s
```

**Check for `selected`, not `deselected`.** `ledger-cli/pyproject.toml` excludes the `e2e` marker by
default, so a run reporting `7 deselected` is green having tested nothing.

The script brings the `app` service up itself and removes it afterwards; the four backing services
stay up for whoever started them. To exercise the host jar instead of the image:

```bash
./mvnw -q -DskipTests package
E2E_MODE=jar bash scripts/e2e/run-e2e.sh
```

---

## 7. Optional: the OpenTelemetry Collector

Off by default. It needs Grafana Cloud credentials in your **shell** — nothing here reads a dotenv
file, so source it before running Compose:

```bash
set -a; . ./.env.grafana; set +a
docker compose -f docker/docker-compose.yml --profile observability up -d
```

A missing variable makes the Collector fail to start, which is the honest failure: one that starts and
silently drops everything is worse than one that refuses. **Nothing in CI runs this.**

---

## 8. Stop

```bash
docker compose -f docker/docker-compose.yml --profile app down        # keep the event store
docker compose -f docker/docker-compose.yml --profile app down -v     # delete it too
```

**Always pass `--profile app` to `down`.** Without it, Compose leaves the app container running,
fails to remove the network, and **still exits 0** — verified on Compose v2.38.1. A teardown that
reports success while leaving things behind is how the next `up` ends up racing a stale container for
ports 8080 and 9090.

The event store is a named volume, so plain `down` preserves it. `-v` is the one that discards the
ledger's system of record.

---

## Troubleshooting, by symptom

| Symptom | Cause | Fix |
|---|---|---|
| `Bind for 0.0.0.0:5432 failed: port is already allocated` | another Postgres | `export TINY_LEDGER_PG_PORT=55432` |
| `up` fails with a pull error on `tiny-ledger:0.1.0-SNAPSHOT` | the image was never built | `./mvnw spring-boot:build-image -DskipTests` |
| `:9090/actuator/health` returns **403** | correct — the root is `denyAll` | use `/actuator/health/readiness` |
| `:8080/actuator/health` returns **404** | correct — actuator is on 9090 | use 9090 |
| Every request `401` with a valid-looking token | `iss` mismatch | mint via `localhost:8081`; check `KC_HOSTNAME` is set on the `keycloak` service. **Never disable issuer validation** |
| `docker exec ... cat` → `executable file not found` | the run image has no shell or coreutils | read `docker compose logs app` instead |
| e2e aborts: "the full stack is not healthy" | a backing service really is unhealthy | `docker compose ps -a`; a container in `Created` usually means a taken host port |
| `7 deselected` instead of `7 passed` | the `e2e` marker override did not take | run through `scripts/e2e/run-e2e.sh`, not bare `pytest` |
| e2e ends `uv: command not found` | uv is not installed — §6 needs it, §§1–5 do not | install uv; the script does not check for it up front, and its EXIT trap dumps the application log *after* the error, so the cause scrolls past |
| App can't reach Kafka in-network | advertised listener | containers use `kafka:29092`; the host uses `localhost:9092` |

## What this stack is not

- **It is not published anywhere.** The image is built and scanned locally and in CI; pushing to a
  registry is spec §12.1 stage 12 and is deliberately unbuilt.
- **The root filesystem is not read-only.** It runs as a non-root user (`1002:1001`) and has no
  shell, both verified — but read-only rootfs is *not* configured, and spec §12 says so rather than
  claiming it.
- **Nothing here is TLS.** Every hop is plaintext, including the backing services. TLS is the next
  piece of work; see [`security-material.md`](security-material.md).
- **Compose is local only.** Kubernetes is the stated production target (ADR 0005) and no manifests
  exist, on purpose.
