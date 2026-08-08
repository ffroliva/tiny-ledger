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
- **`uv`** — for §6 only, the e2e suite. `scripts/e2e/run-e2e.sh` ends in `uv run pytest -m e2e`; it
  now checks for uv on its first line rather than at that last one, so a missing install costs you
  the error and nothing else. uv provisions the Python interpreter itself (3.11+), so Python is not
  a separate install.
- `curl` and `jq` — every verification command below is written with them. On Windows use
  `curl.exe`; PowerShell aliases bare `curl` to `Invoke-WebRequest`, which does not take these flags.
- `bash`, for the `scripts/e2e/*.sh` helpers. Git Bash or WSL on Windows.
- **`openssl`** — for §1b, the dev certificate. Git for Windows bundles it; so does every Linux and
  macOS install.

**One warning about `curl` and TLS, measured on this machine.** From §2 onward the application is
reachable only over **HTTPS, on a private CA**. Git for Windows ships a **Schannel**-backed curl
(`curl 8.12.1 ... Schannel`), and Schannel resolves chains through the Windows certificate store, so
`--cacert` is accepted and the chain is rejected anyway:

```
schannel: added 1 certificate(s) from CA file 'docker/tls/ca.crt'
schannel: CertGetCertificateChain trust error CERT_TRUST_IS_UNTRUSTED_ROOT
```

The curl commands below are correct on Linux and macOS, where curl is OpenSSL-backed. **On Windows,
do not reach for `-k`** — that deletes the check. Use the repository's own Python probe instead,
which is OpenSSL on every platform:

```bash
(cd ledger-cli && uv run python ../scripts/e2e/https-check.py \
   https://127.0.0.1 http://127.0.0.1 60 "$OLDPWD/docker/tls/ca.crt")
```

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

## 1b. Generate the dev certificate

Traefik terminates TLS in front of the application, and it needs something to terminate it *with*.
Nothing is committed — this repository is public and holds the names of secrets, never their values.

```bash
scripts/tls/gen-dev-ca.sh
```

```
Certificate request self-signature ok
subject=CN=localhost
docker/tls/server.crt: OK
dev CA and leaf written to docker/tls/ (gitignored, valid 825 days)
```

`docker/tls/server.crt: OK` is `openssl verify` — the script gates on its own output, so a leaf that
does not chain to the CA never reaches Traefik. It is **idempotent**: run it again and it leaves an
existing certificate alone, because regenerating under a running stack hands Traefik a certificate
its clients no longer trust and the resulting failure reads as a routing problem. Force it with
`--force`.

CI runs this same script inside its own runs, which is why **no certificate is a secret anywhere**.

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
tiny-ledger-traefik-1   Started
```

**`--profile app` adds the application AND Traefik.** A plain `up` starts exactly the four backing
services — that is the mode you want when you would rather run the app from your IDE:

```bash
docker compose -f docker/docker-compose.yml config --services | sort   # 4 names
COMPOSE_PROFILES=app \
docker compose -f docker/docker-compose.yml config --services | sort   # 6 names
```

**The application no longer publishes any port, and that absence is a control rather than an
omission.** Everything arrives through Traefik:

| Host port | Serves | Why |
|---|---|---|
| `443` | HTTPS — the whole API **and Keycloak**, split by hostname | the only way in |
| `80` | **301 to `https://127.0.0.1`**, nothing else | a plaintext caller is moved, never served |
| ~~`8080`~~ | nothing | publishing it would leave a plaintext route straight past Traefik. TLS you can walk around is decoration |
| ~~`9090`~~ | nothing | spec §6.6 says the management endpoints "rely on the port not being published"; publishing it had made that sentence false |

Measured after this change: `127.0.0.1:8080` and `127.0.0.1:9090` refuse connections from the host
while `127.0.0.1:443` accepts. Keycloak's `8081` is gone with them. Both remain reachable **inside** the network, which is all either
needs — Traefik dials `app:8080`, and in production the kubelet dials the pod IP for the probes.

Override the published pair the same way you override Postgres, and for the same reason:

```bash
export TINY_LEDGER_HTTPS_PORT=9443
export TINY_LEDGER_HTTP_PORT=9000
export TINY_LEDGER_AUTH_ORIGIN=https://auth.localhost:9443   # NOT optional — see below
```

**`TINY_LEDGER_HTTPS_PORT` is not a free knob, and 443 is the default for that reason.** Keycloak
sits behind this proxy, so the published port ends up inside the `iss` claim — and 443 is the one
port that does not, because it is the scheme default and drops out of the URL. Move the port without
moving `TINY_LEDGER_AUTH_ORIGIN` and every token is refused with a bare `401`.

**`--wait` does not mean the application is ready.** It blocks on *healthchecks*, and the `app`
service has none — the run image is `ubuntu-noble-run-tiny`, which ships no shell, so there is no
`CMD` form a healthcheck could use. Compose returns as soon as the container **starts**. Use step 3
as the readiness signal.

---

## 3. Is it actually up?

**The readiness signal is a `401` over HTTPS**, not a health probe — the management port is no longer
published (§2). A `401` on a protected route proves two things at once: the application is serving,
and the security chain is wired. A `200` there would itself be a defect.

```bash
curl -s -o /dev/null -w '%{http_code}\n' --cacert docker/tls/ca.crt \
  https://127.0.0.1/api/v1/accounts        # 401
```

The certificate is verified, not skipped. If this reports `000` on Windows that is the Schannel
problem from §0, not a broken stack — use the Python probe named there.

**The probes still exist; they are in-network now.** Reach them from a throwaway container on the
same network rather than from the host:

```bash
docker run --rm --network tiny-ledger_default curlimages/curl:8.11.1 \
  -s -o /dev/null -w '%{http_code}\n' http://app:9090/actuator/health/readiness   # 200
```

**Two responses that look like faults and are not:**

| Request | Answer | Why it is correct |
|---|---|---|
| `app:9090/actuator/health` (in-network) | **403** | The health *root* is `denyAll`. Only the probe sub-paths are exposed (spec §6.6). A `200` here would be the bug |
| `127.0.0.1:9090` from the **host** | connection refused | The port is not published. ADR 0005's intent: a misconfigured endpoint is unreachable rather than merely denied |

**Do not use `curl -sf` to check these.** It exits non-zero on `403` and `404` alike, so both become
indistinguishable from a container that never started. Print the status code.

If readiness never reaches 200, read the log — and note that `docker exec` is of limited use here,
because the image has no shell and no coreutils:

```bash
docker compose -f docker/docker-compose.yml --profile app logs app | tail -40
```

---

## 4. Get a token

Every route in `full` requires a bearer token. **Keycloak is behind Traefik too, at
`https://auth.localhost`, and is published on no host port at all** — one ingress, one certificate
story, and no second scheme in the stack. `8081` is gone.

```bash
TOKEN=$(curl -s --cacert docker/tls/ca.crt -X POST \
  'https://auth.localhost/realms/tiny-ledger/protocol/openid-connect/token' \
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
normally derives the `iss` claim from the caller's `Host` header, which means two spellings of the
same host mint *different issuers* and only one of them authenticates. Measured on this stack back
when Keycloak was published on 8081:

```
minted via 127.0.0.1:8081  ->  iss = http://127.0.0.1:8081/realms/tiny-ledger  ->  401
minted via localhost:8081  ->  iss = http://localhost:8081/realms/tiny-ledger  ->  200
```

`KC_HOSTNAME` pins it. Since TLS landed it pins it to the proxy:

```
iss = https://auth.localhost/realms/tiny-ledger
```

**No port**, because Traefik publishes 443 and the scheme default drops out of the URL. That string
appears in eight places — Compose, the app's properties, the CLI's default, `.env.example`, `ci.yml`
and three documents — and they agree or nothing authenticates. Measured on the running stack:

```
POST https://auth.localhost/realms/tiny-ledger/protocol/openid-connect/token  ->  200
iss  =  https://auth.localhost/realms/tiny-ledger
aud  =  tiny-ledger-api
POST https://app.localhost/api/v1/accounts  (with that token)                 ->  201
```

The application validates that public issuer while fetching the signing keys **in-network**
(`jwk-set-uri` → `http://keycloak:8080`), which is why the dev CA never enters the app container's
truststore — issuer **and** audience validation stay fully enforced. Nothing is relaxed to make this
convenient.

**The host jar is the exception, and it is the one people trip on.** `E2E_MODE=jar` runs the
application outside the network, so it resolves the issuer itself over TLS — and a JVM reads neither
`SSL_CERT_FILE` nor a PEM. `gen-dev-ca.sh` also writes `docker/tls/truststore.p12` for exactly that,
and `run-e2e.sh` passes it with `-Djavax.net.ssl.trustStore` **before** `-jar`; after `-jar` those
become application arguments and are silently ignored. Measured with the store pointed at a
non-existent path:

```
PKIX path building failed: unable to find valid certification path to requested target   ->  500
```

---

## 5. Move some money

```bash
# Open an account — note the field is `name`, not `ownerName`
ACC=$(curl -s --cacert docker/tls/ca.crt -X POST https://127.0.0.1/api/v1/accounts \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"ACC-001","currency":"EUR"}' | jq -r .accountUid)

# Deposit. The UID in the PATH is the idempotency key, and amount is an OBJECT, not a string.
curl -s --cacert docker/tls/ca.crt -X PUT "https://127.0.0.1/api/v1/accounts/$ACC/deposits/$(uuidgen)" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount":{"currency":"EUR","minorUnits":10000}}' | jq

curl -s --cacert docker/tls/ca.crt "https://127.0.0.1/api/v1/accounts/$ACC/balance" -H "Authorization: Bearer $TOKEN" | jq
curl -s --cacert docker/tls/ca.crt "https://127.0.0.1/api/v1/accounts/$ACC/transactions" -H "Authorization: Bearer $TOKEN" | jq
```

Measured responses — re-measured over HTTPS after Traefik landed, and **identical** to the plaintext
ones. That is the point: TLS is terminated at the edge and the application's contract is unchanged.

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

curl -s --cacert docker/tls/ca.crt https://127.0.0.1/api/v1/audit/entries -H "Authorization: Bearer $AUDIT" | jq
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

The script brings `app` **and `traefik`** up itself and removes both afterwards; the four backing
services stay up for whoever started them. It generates the certificate if it is missing, and before
running a scenario it proves the transport:

```
tiny-ledger (full, HTTPS) ready after 6s (HTTP 401)
  public trust store        -> rejected, as it must be
  dev CA                    -> verified
  http://127.0.0.1 -> 301 https://127.0.0.1/api/v1/accounts
```

**Both trust-store lines are required.** A `https://` URL is not evidence of anything on its own —
the run would look identical against a client that skipped verification. The rejection is what makes
the verification mean something.

To exercise the host jar instead of the image — **plaintext and direct, with no proxy in front**,
because that is what `java -jar` actually is:

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
the published ports. Since TLS landed those are **Traefik's 8443 and 8000**; the application itself
publishes nothing.

The event store is a named volume, so plain `down` preserves it. `-v` is the one that discards the
ledger's system of record.

---

## Troubleshooting, by symptom

| Symptom | Cause | Fix |
|---|---|---|
| `Bind for 0.0.0.0:5432 failed: port is already allocated` | another Postgres | `export TINY_LEDGER_PG_PORT=55432` |
| `up` fails with a pull error on `tiny-ledger:0.1.0-SNAPSHOT` | the image was never built | `./mvnw spring-boot:build-image -DskipTests` |
| `app:9090/actuator/health` returns **403** | correct — the root is `denyAll` | use `/actuator/health/readiness` |
| `127.0.0.1:8080` or `:9090` refuse the connection | correct — neither is published any more | go through `https://127.0.0.1`; for the probes use a container on the network (§3) |
| curl reports `CERT_TRUST_IS_UNTRUSTED_ROOT`, or `000` | Windows curl is Schannel-backed and will not accept a private CA | use the Python probe in §0. **Not `-k`** |
| Traefik serves `CN=TRAEFIK DEFAULT CERT` | the CA was never generated, and Compose created an empty directory for the bind mount | `scripts/tls/gen-dev-ca.sh`, then recreate `traefik` |
| `Pool overlaps with other one on this address space` | another project holds `10.89.0.0/24` | `export TINY_LEDGER_SUBNET=10.90.0.0/24 TINY_LEDGER_TRAEFIK_IP=10.90.0.250` — **both**, they must move together |
| Every request `401` with a valid-looking token | `iss` mismatch | mint via `https://auth.localhost`; check `KC_HOSTNAME` on the `keycloak` service and `TINY_LEDGER_AUTH_ORIGIN`. **Never disable issuer validation** |
| A host jar answers `500` on any authenticated route, `PKIX path building failed` in its log | the JVM does not trust the dev CA | pass `-Djavax.net.ssl.trustStore=docker/tls/truststore.p12` **before** `-jar`, not after |
| `docker exec ... cat` → `executable file not found` | the run image has no shell or coreutils | read `docker compose logs app` instead |
| e2e aborts: "the full stack is not healthy" | a backing service really is unhealthy | `docker compose ps -a`; a container in `Created` usually means a taken host port |
| `7 deselected` instead of `7 passed` | the `e2e` marker override did not take | run through `scripts/e2e/run-e2e.sh`, not bare `pytest` |
| e2e aborts `::error::uv not found` | uv is not installed — §6 needs it, §§1–5 do not | install uv. The check is the script's first, before the image guard, so nothing was built or started and there is no application log to read past |
| App can't reach Kafka in-network | advertised listener | containers use `kafka:29092`; the host uses `localhost:9092` |

## What this stack is not

- **It is not published anywhere.** The image is built and scanned locally and in CI; pushing to a
  registry is spec §12.1 stage 12 and is deliberately unbuilt.
- **The root filesystem is not read-only.** It runs as a non-root user (`1002:1001`) and has no
  shell, both verified — but read-only rootfs is *not* configured, and spec §12 says so rather than
  claiming it.
- **TLS stops at Traefik.** The proxy-to-application hop and every backing-service hop (Postgres,
  Redis, Kafka) are plaintext, and so is Keycloak. That is a **named gap**, not an oversight; see
  [`security-material.md`](security-material.md).
- **The certificate is a throwaway.** Generated per machine and per CI run, trusted by no client
  outside this stack. Let's Encrypt is blocked on a *deployment* decision rather than on TLS — ADR
  0005 targets Kubernetes and no manifests exist.
- **Compose is local only.** Kubernetes is the stated production target (ADR 0005) and no manifests
  exist, on purpose.
