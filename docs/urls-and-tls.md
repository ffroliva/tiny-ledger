# URLs and TLS — what is exposed, to whom, over what, and how to run without it

**This page answers four questions in one place, because the answer to each was previously scattered
across a Compose file, two runbooks, a properties file and a spec section — and that is exactly how
five of them ended up disagreeing.**

1. Which URLs does this system expose?
2. Which are reachable from outside, and which only from inside the network?
3. Which are encrypted, and where does the encryption stop?
4. Can I run it **without** TLS?

> Contract → [`spec.md`](spec.md) §6.4a. Where keys and certificates live →
> [`security-material.md`](security-material.md). Running the stack → [`docker.md`](docker.md).
> Traps that will cost you an hour → [`pitfalls.md`](pitfalls.md).

---

## The short answer

| Mode | Transport | What you dial |
|---|---|---|
| **`standalone`** (default) | **No TLS at all.** Plain HTTP on loopback | `http://127.0.0.1:8080` |
| **`full`, image** | **HTTPS**, terminated by Traefik | `https://app.localhost` · `https://auth.localhost` |
| **`full`, host jar** | Application plaintext; **identity provider still HTTPS** | `http://127.0.0.1:8080` + `https://auth.localhost` |

**There is no "full mode without TLS".** Since Keycloak moved behind the proxy it publishes no host
port, so a `full` stack without Traefik cannot mint a token at all. That is deliberate — see
*Running without TLS* below, which explains what to use instead.

---

## The full map

### Published — reachable from the host, and from anything that can reach the host

| URL | Serves | Encrypted | Notes |
|---|---|---|---|
| `https://app.localhost` | the whole ledger API | **yes** | the catch-all route; `https://127.0.0.1` reaches the same place |
| `https://auth.localhost` | Keycloak — tokens **and its admin console** | **yes** | see *The admin console* below |
| `http://127.0.0.1:80` | **nothing but a `301`** to the HTTPS entrypoint | no | exists so the redirect is observable, not to serve traffic |
| `http://127.0.0.1:8080` | the ledger API — **`standalone` and host-jar only** | no | not published by the container in `full` |

Both HTTPS names resolve through **`*.localhost`**, which the resolver maps to loopback with no
`/etc/hosts` edit. Measured on the development machine — and it is strictly better than bare
`localhost`, which offers `::1` first on a path that does not route here:

```
localhost      -> ::1, 127.0.0.1
app.localhost  -> 127.0.0.1
auth.localhost -> 127.0.0.1
```

### Internal — reachable only from inside the Compose network

| URL | Serves | Encrypted | Why it is not published |
|---|---|---|---|
| `http://app:8080` | the API, as Traefik dials it | no | a published `8080` would be a plaintext route straight past the terminator |
| `http://app:9090` | the actuator probes (§6.6) | no | ADR 0005: a misconfigured endpoint should be **unreachable**, not merely denied |
| `http://keycloak:8080` | the JWKS the application fetches | no | issuer validation and key fetching are separate questions; see below |
| `postgres:5432`, `redis:6379`, `kafka:29092` | the backing services | **no — named gap** | not fronted, not encrypted, not in scope |

To reach an internal URL by hand, put a container on the network rather than publishing a port:

```bash
docker run --rm --network tiny-ledger_default curlimages/curl:8.11.1 \
  -s -o /dev/null -w '%{http_code}\n' http://app:9090/actuator/health/readiness   # 200
```

---

## Where the encryption stops, stated plainly

```
        ┌── HTTPS ──┐                    ┌───────── all plaintext ─────────┐
client ─┤           ├─ Traefik ──────────┤ app:8080                        │
        │  TLS 1.2+ │      (terminates)  │ keycloak:8080                   │
        └───────────┘                    │ postgres · redis · kafka        │
                                         └─────────────────────────────────┘
```

**"The ledger uses TLS" does not mean "the ledger encrypts everything".** One hop is encrypted: the
one between a caller and the edge. Everything behind Traefik is plaintext, by decision, and a
service mesh is the tool for closing that — ADR 0005 records why one is out of scope.

**The issuer is HTTPS while the key set is fetched over plain HTTP, and that is not a contradiction.**
`iss` is a *name* that must match exactly; `jwk-set-uri` is an *address* to fetch from. Boot lets
them be answered separately, so `iss` is `https://auth.localhost/realms/tiny-ledger` and the
application fetches keys from `http://keycloak:8080/…` inside the network. Issuer **and** audience
validation stay fully enforced; nothing is relaxed. Verified in the shipped bytecode during #11.

## The admin console — a named exposure, not a hidden one

`https://auth.localhost` routes **everything** to Keycloak, including `/admin`, and the fixture realm
bootstraps `admin`/`admin`. Because Traefik binds `0.0.0.0:443`, anything that can reach this machine
on 443 and sends `Host: auth.localhost` reaches that console.

**That is acceptable only because this is a fixture marked *never deploy*.** It is written down here
rather than left to be discovered. Two things make it materially safer if you want them:

- bind the published port to loopback: `TINY_LEDGER_HTTPS_PORT=127.0.0.1:443` is **not** currently
  supported by the compose mapping — the honest fix is a firewall rule or a machine not on a shared
  network;
- narrow the router to `Host(\`auth.localhost\`) && PathPrefix(\`/realms\`)`, which costs one line
  and removes the console from the edge entirely. **Not done**, because the runbooks use the console
  and no deployment exists to protect.

---

## Running without TLS

**Use `standalone`.** It is the mode that exists for this, it needs no Docker, no certificate and no
identity provider, and it is what the README's Quick start runs:

```bash
./mvnw spring-boot:run          # http://127.0.0.1:8080, AUTH DISABLED (standalone)
```

**`full` cannot be run plaintext end to end**, and the reason is a security property rather than an
oversight: Keycloak derives `iss` from the request host, so a plaintext Keycloak on a published port
mints tokens with a *different* issuer from the one the application trusts. Publishing it again would
reintroduce exactly the 401-that-explains-nothing this stack has already measured once. The
`8081:8080` mapping is gone on purpose.

**The half-way house is the host jar**, and it is a supported, CI-exercised path (stage 9b):

```bash
docker compose -f docker/docker-compose.yml --profile app up -d --wait   # Traefik + Keycloak
./mvnw -q -DskipTests package
E2E_MODE=jar ./scripts/e2e/run-e2e.sh
```

The application is plaintext on `127.0.0.1:8080` and only the token exchange crosses TLS. **A JVM
needs its own truststore for that** — it reads neither `SSL_CERT_FILE` nor a PEM — which
`gen-dev-ca.sh` writes to `docker/tls/truststore.p12` and the runner passes with
`-Djavax.net.ssl.trustStore` **before** `-jar`.

---

## The ports, and the one that is not a free knob

| Variable | Default | Safe to change alone? |
|---|---|---|
| `TINY_LEDGER_HTTPS_PORT` | `443` | **No** — moves with `TINY_LEDGER_AUTH_ORIGIN` |
| `TINY_LEDGER_HTTP_PORT` | `80` | yes |
| `TINY_LEDGER_AUTH_ORIGIN` | `https://auth.localhost` | **No** — moves with the HTTPS port |
| `TINY_LEDGER_SUBNET` | `10.89.0.0/24` | **No** — moves with the Traefik address |
| `TINY_LEDGER_TRAEFIK_IP` | `10.89.0.250` | **No** — moves with the subnet, and is a security control |
| `TINY_LEDGER_PG_PORT` | `5432` | yes |

**443 is the default for a reason that is not aesthetic.** Keycloak sits behind the proxy, so the
published port lands inside `iss` — and 443 is the one port that does not, because it is the scheme
default and drops out of the URL. That keeps the issuer as `https://auth.localhost` with no port, and
keeps a port number out of the files that have to spell it identically.

Move it and you must move the auth origin too, in the same shell:

```bash
export TINY_LEDGER_HTTPS_PORT=9443 TINY_LEDGER_HTTP_PORT=9000 \
       TINY_LEDGER_AUTH_ORIGIN=https://auth.localhost:9443
```

**A caveat that is real and is not fixed**: `TINY_LEDGER_AUTH_ORIGIN` reaches only the two
*server-side* sites (Compose derives `KC_HOSTNAME` and the app's `LEDGER_ISSUER_URI` from it). The
client-side spellings — `ledger-cli`'s default and `ci.yml`'s env — are independent literals, so a
moved port also needs `LEDGER_ISSUER_URI` exported for any host-side client. See
[`pitfalls.md`](pitfalls.md).

**`LEDGER_TRUSTED_PROXIES` is not settable from `.env`.** `docker-compose.yml` sets it on the `app`
service from `TINY_LEDGER_TRAEFIK_IP`, and a Compose `environment:` mapping wins over the shell. Set
the Traefik address instead; the trusted-proxy value follows it, and `ProxyAddressPinTest` fails if
the two ever disagree.

---

## What secures each surface

| Surface | Control |
|---|---|
| Transport, edge | TLS 1.2 floor, dev CA locally and in CI, Let's Encrypt blocked on a deployment decision |
| `X-Forwarded-For` | Two layers: **Traefik strips** untrusted forwarded headers at the edge, **and** the application trusts them only from Traefik's pinned address (§6.1) |
| Response headers | `X-Content-Type-Options`, `X-Frame-Options` and `Cross-Origin-Resource-Policy: same-origin` at the terminator. **HSTS deliberately not sent** — see `pitfalls.md` for why it would break unrelated localhost projects. CORP was added because the stage 11e API scan found it missing on live `200` responses; the baseline never saw it, having only ever reached `401`s |
| Authentication | OIDC bearer tokens, issuer **and** audience validated (§6.4) |
| Authorisation | Five comparison points across four sites, §6.4 |
| Rate limiting | Four buckets, §6.1, per principal and per IP |
| Management port | Unpublished, plus `denyAll` on everything but the probes (§6.6) |

**No gate enforces this page.** Its facts are checked where they can be: `ProxyAddressPinTest` pins
the proxy address, `scripts/e2e/https-check.py` proves the round trip is real TLS differentially, and
the ZAP baseline checks the edge headers. The URL table itself is prose, and prose goes stale — if it
disagrees with `docker/docker-compose.yml`, the Compose file is right.
