# Pitfalls — the failures that cost hours, and the symptom each one shows you

**This page exists because every entry on it wasted real time, and every one of them looks like
something else.** They are grouped by the symptom you will actually see, not by the subsystem they
live in, because the whole problem with these is that the symptom points at the wrong place.

> This is the **runtime** catalogue. Design rules an agent must know unprompted →
> [`../AGENTS.md`](../AGENTS.md), whose *Traps this repository has already paid for* section covers
> the **build and test** equivalents. Contract → [`spec.md`](spec.md). URL map →
> [`urls-and-tls.md`](urls-and-tls.md).

**Nothing enforces this page.** Where a trap has since been given a gate, the gate is named on the
row. Where it has not, that is said plainly.

---

## "Everything returns 401 and the token looks fine"

| Cause | How to tell | Fix |
|---|---|---|
| **`iss` mismatch.** Keycloak derives the issuer from the request host, so two spellings of the same host mint different issuers and only one authenticates | decode the token: `iss` must equal `https://auth.localhost/realms/tiny-ledger` **exactly** | mint through the proxy, not a port. Check `KC_HOSTNAME` and `TINY_LEDGER_AUTH_ORIGIN`. **Never relax issuer validation** |
| **The JVM does not trust the dev CA.** Only the *host jar* paths hit this — the container fetches keys in-network over plain HTTP | the app log says `PKIX path building failed: unable to find valid certification path` | `-Djavax.net.ssl.trustStore=docker/tls/truststore.p12` — and **before `-jar`** |
| **A stale jar.** `target/*-exec.jar` was built before a properties change and carries the old issuer | the app log names a host or port that no longer exists | `./mvnw -q -DskipTests package` |
| **The port moved but only half the sites followed.** `TINY_LEDGER_AUTH_ORIGIN` reaches the two server-side sites; client defaults are independent literals | the CLI dials `:443` while the stack is on `:9443` | export `LEDGER_ISSUER_URI` too |

**Measured, and the reason `KC_HOSTNAME` exists at all** — from the days Keycloak was published on
8081:

```
minted via 127.0.0.1:8081  ->  iss = http://127.0.0.1:8081/realms/tiny-ledger  ->  401
minted via localhost:8081  ->  iss = http://localhost:8081/realms/tiny-ledger  ->  200
```

Same host, same realm, same user, two spellings of loopback, one of them works.

---

## "TLS fails, or curl returns 000, on Windows but not in CI"

**Git for Windows ships a Schannel-backed curl**, which resolves chains through the Windows
certificate store. It accepts `--cacert`, loads the certificate, and rejects the chain anyway:

```
schannel: added 1 certificate(s) from CA file 'docker/tls/ca.crt'
schannel: CertGetCertificateChain trust error CERT_TRUST_IS_UNTRUSTED_ROOT
```

`CURL_SSL_BACKEND=openssl` does not switch it — the build has no OpenSSL backend.

**Do not reach for `-k`.** It deletes the check rather than satisfying it, and every TLS check in
this repository exists to be a check. Use `scripts/e2e/https-check.py`, which is Python and therefore
OpenSSL on every platform. Installing the throwaway CA into the operator's Windows store is also the
wrong answer: it is a change to their machine, made by a test script, that nothing removes.

---

## "Traefik serves a certificate I did not generate"

```
subject=CN=TRAEFIK DEFAULT CERT
[SSL: CERTIFICATE_VERIFY_FAILED] certificate verify failed: self-signed certificate
```

Two causes, and the first is nastier because **every request still succeeds end to end**:

1. **Certificate selection is by SNI, and RFC 6066 forbids an IP literal in SNI.** Dialling
   `https://127.0.0.1` sends no server name, so nothing in a `tls.certificates:` list can match and
   Traefik falls back to its built-in certificate. Fixed with
   `tls.stores.default.defaultCertificate`, which is served whenever SNI matches nothing.
2. **`gen-dev-ca.sh` was never run.** Compose creates an empty **directory** for a missing bind-mount
   source rather than failing, so Traefik starts with nothing to read. On Linux that directory is
   root-owned, which then makes a later `gen-dev-ca.sh` fail with permission denied.

**Gate:** `scripts/e2e/https-check.py` runs the same request against the dev CA and against the
public trust store and requires **both** outcomes. It caught cause 1 on its first run. Nothing
catches either cause on the plain `docker compose up` path a human follows.

---

## "The rate limiter fires for no reason", or "it never fires"

| Cause | Why it is invisible | Gate |
|---|---|---|
| **Traefik's address is not pinned**, so `internal-proxies` matches nothing, the valve no-ops, and every caller in the world shares one bucket | the by-hand spoof check scores the same either way — it is **not differential** | `ProxyAddressPinTest` |
| **Redis carries the previous run's buckets.** They are stored with the capacity they were created with, and `test_rate_limit` deliberately exhausts alice's write bucket | a second e2e leg inside the same minute fails on its *first* write with `429` | `run-e2e.sh` flushes, and now warns loudly if the flush fails |
| **A by-hand run after the e2e suite** reads that suite's `capacity=10000` and nothing appears to fire | looks like the limiter is broken | `docker exec tiny-ledger-redis-1 redis-cli FLUSHALL` |

**The lesson from the first row is the one worth carrying.** Four requests with four different
spoofed `X-Forwarded-For` values return `401, 401, 429, 429` *whether the trust works or not* —
because Traefik strips untrusted forwarded headers at the edge, so the spoof never arrives either
way. A control that scores identically under both configurations proves nothing. The differential
that does work uses the exempt-IP list as the observable:

```
internal-proxies = <Traefik's address>  -> app sees the real client -> exempt -> 401,401,401,401
internal-proxies = <anything else>      -> app sees Traefik         -> not    -> 401,401,429,429
```

---

## "Compose will not start, or starts the wrong thing"

| Symptom | Cause | Fix |
|---|---|---|
| `Pool overlaps with other one on this address space` | another project holds `10.89.0.0/24` | set **both** `TINY_LEDGER_SUBNET` and `TINY_LEDGER_TRAEFIK_IP` |
| `failed to set up container networking: Address already in use` | a **low** static address collides with Docker's dynamic allocation, which starts at the bottom | keep Traefik high (`.250`) |
| `Bind for 0.0.0.0:5432 failed` | another Postgres | `TINY_LEDGER_PG_PORT=55432` |
| Teardown reports success but the next `up` races a stale container | `down` without `--profile app` leaves the profiled containers **up and exits 0** | always `--profile app down` |
| The app connects to *another* Postgres and reads it silently | a host port clash, not a refused bind | the guard in `run-e2e.sh` refuses a partial stack |
| `up` succeeds but nothing answers on 443 | `--profile app` omitted, so Traefik never started — and Keycloak has no port of its own | `--profile app` |

---

## "The app will not boot"

| Symptom | Cause |
|---|---|
| `Error creating bean 'rateLimitRedisConnection'` | Redis did not answer within **250 ms**. That timeout is deliberate (`RateLimitConfig`) so a Redis outage degrades rather than stalls a worker; on a loaded machine it also means a slow start fails. Retry |
| `refusing to start without a known security posture` | an unknown profile. `FailClosedGuard`, working as designed |
| `standalone profile is active but full-mode config … is present` | full-shaped config leaked into `standalone`. Also `FailClosedGuard` |
| Hangs on startup under `full` | Postgres, Kafka or Liquibase unreachable. An AOT training run **must** use `standalone` for this reason |

---

## "The tool behaves differently on this machine"

| Trap | Symptom | Fix |
|---|---|---|
| **MSYS path rewriting.** Git Bash turns any argument that looks like a Unix path into a Windows one | `openssl`: `Subject does not start with '/'`; `docker run --entrypoint=/…` silently wrong | `export MSYS_NO_PATHCONV=1` |
| **Process substitution.** Native Windows `openssl` cannot open `/dev/fd/63` | `Can't open "/dev/fd/63" for reading` | use a real temp file |
| **`chmod +x` does not reach the git index** | CI: `Permission denied`, exit 126 | `git update-index --chmod=+x` |
| **`localhost` resolves to `::1` first** and that path does not route here | connections time out on IPv6 while IPv4 is open | dial `127.0.0.1`, or a `*.localhost` name — those resolve to IPv4 only |
| **`keytool` not on `PATH`** (JDK reachable only via `JAVA_HOME`) | no `truststore.p12`, and every host-jar path 401s | the generator now warns loudly and its guard regenerates |

---

## Things that look broken and are correct

| Observation | Why it is right |
|---|---|
| `app:9090/actuator/health` → **403** | the health *root* is `denyAll`; only the probe sub-paths are exposed (§6.6). A `200` would be the bug |
| `127.0.0.1:8080` and `:9090` refuse the connection | neither is published any more. That absence **is** the control |
| The plaintext entrypoint serves nothing but a `301` | it exists to move callers to TLS, not to serve |
| ZAP reports `Non-Storable Content` on two 401s | non-storable is *required* for an authenticated ledger. Dispositioned in `.zap/rules.tsv` |
| `docker compose up --wait` returns before the app is ready | the run image has no shell, so the app service can carry **no** healthcheck. `wait-for.sh` and `https-check.py` are the real gates |
| Stage 10 (`load`) misses its §9.7 thresholds | measured at 20 users on one laptop, not 500 on representative hardware. `performance-findings.md` §2.4 |

---

## HSTS is deliberately **not** sent

The obvious thing to do at a TLS terminator is set `Strict-Transport-Security`, and this repository
did for one revision. It is removed, and the reason is worth keeping.

The application's router is a catch-all, so a browser dialling `https://localhost` would receive the
pin. **HSTS is host-scoped and port-independent**, so a one-year pin on the bare host `localhost`
force-upgrades `http://localhost:3000`, `http://localhost:8080` and every other local development
server on that machine — failing with `ERR_SSL_PROTOCOL_ERROR`, clearable only through
`chrome://net-internals/#hsts`.

Breaking a developer's unrelated projects is a steep price for a header on a throwaway certificate. A
real deployment on a real domain should set it and mean it; that is a named gap, not a default.
