# Plan — TLS at the edge: Traefik, a generated dev CA, and the `X-Forwarded-For` control

**Date:** 2026-08-08 · **Branch:** `plan-tls-traefik` · **Authority:** `docs/spec.md` wins over this
file on any disagreement (`AGENTS.md`, *Authorities*).

**The shape is already decided** — `docs/security-material.md` §TLS and ADR 0005. This plan does not
re-open it. It says how to build it, in what order, and which claim each step earns the right to make.

---

## Why this piece of work exists, and what it must not break

Adding TLS to this repository is not "turn on HTTPS". It is **putting a reverse proxy in front of an
application whose rate limiter meters by client IP**, and that is a security *regression* risk before
it is a feature:

> spec §6.1 row 4 — *any traffic, per IP (backstop), 300/minute*. `IpBackstopFilter` reads
> `request.getRemoteAddr()`. Behind a proxy, every request's source address becomes the proxy's. If
> the application then trusts `X-Forwarded-For` from **anywhere**, any caller can put any address in
> that header and mint themselves a fresh 300/minute bucket per request.

So the deliverable is not "the e2e suite speaks HTTPS". It is **"the e2e suite speaks HTTPS and the IP
backstop still works"**, and the second half needs a test that goes red if the trust is widened.

## What is decided and NOT up for discussion here

From `docs/security-material.md` and ADR 0005:

- **Traefik terminates TLS** — the same tool locally and as a Kubernetes ingress controller.
- **A locally generated CA for dev *and* CI.** CI gets **no certificate secret**; it generates a
  throwaway CA in-run, exactly as it holds no Grafana credential.
- **No service mesh.** Backing-service TLS (Postgres, Redis, Kafka) is a **named gap**, not scope.
- **mTLS / FAPI is a separate, later piece of work.**

## What is BLOCKED, and by what

**Let's Encrypt is not blocked by TLS. It is blocked by the absence of a deployment.** HTTP-01 needs a
publicly reachable `archb.uk:80/443`; DNS-01 needs a provider token. ADR 0005 names Kubernetes as the
production target and **no manifests exist**, so there is no environment to issue a certificate *for*
and nowhere to put the ACME account key. This plan builds the dev-CA half in full and records
Let's Encrypt as waiting on a **deployment decision** — writing an `acme` resolver into
`traefik.yml` today would be configuration for a host that does not exist, which is the exact shape of
claim §12 has already had to retract once.

---

## Three decisions this plan takes, with their reasons

### 1. `native`, not `framework` — because only one of the two can be told whom to trust

`server.forward-headers-strategy` has three values. Read out of the shipped jars rather than assumed:

| Value | Mechanism | Trusted-proxy control |
|---|---|---|
| `framework` | `ForwardedHeaderFilter` | **none** — it processes `X-Forwarded-*` from any peer |
| `native` | Tomcat `RemoteIpValve` | `server.tomcat.remoteip.internal-proxies` |
| `none` (default) | nothing | n/a |

`framework` is therefore the setting that *creates* the vulnerability described above. **`native` is
the only option that can express "trust this proxy and no one else"**, so it is what this plan uses.

`TomcatWebServerFactoryCustomizer#customizeRemoteIpValve`
(`spring-boot-tomcat-4.1.0-sources.jar`) confirms the valve is added when the strategy is set and that
`internal-proxies` is passed straight through. `RemoteIpValve` rewrites `remoteAddr` **only when the
directly connected peer already matches `internalProxies`** — which is precisely the control: a
caller that reaches the app without going through Traefik has its header ignored entirely.

### 2. Boot's DEFAULT `internal-proxies` is not safe here, and that is the trap

Its default is:

```
192.168.0.0/16, 172.16.0.0/12, 169.254.0.0/16, fc00::/7, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, fe80::/10, ::1/128
```

**`172.16.0.0/12` is the range Docker hands to Compose networks.** Leaving the default in place would
trust *every container on the network* — and, on a Kubernetes pod network, every pod. So the plan
pins a **subnet and a static address for Traefik** in `docker-compose.yml` and sets
`internal-proxies` to that one address. A deployment overrides it with its own ingress address.

### 3. Keycloak IS routed through Traefik — **this plan originally said the opposite**

> **CORRECTED.** This plan was written proposing that Keycloak stay on plain HTTP, for the reasons
> below. While it was being executed, PR #25 landed on `main` deciding the other way, deliberately
> and with the blast radius enumerated: one ingress, one certificate story, and no second scheme in
> the stack. **`main` is the authority and the implementation follows it** — Keycloak is behind
> Traefik at `https://auth.localhost`, published on no host port, and the issuer was renamed in all
> eight places at once. The reasoning below is kept because it is the cost that decision accepted,
> not because it is what shipped.

Keycloak derives `iss` from the `Host` header; `KC_HOSTNAME: http://localhost:8081` pins it, and
`docker-compose.yml` already carries the measurement showing `127.0.0.1:8081` and `localhost:8081`
mint different issuers of which only one authenticates. Fronting it with HTTPS **changes the issuer
string** for the CLI, `application-full.properties`, `ci.yml` and `ledger-cli/config.py` at once, to
buy TLS on a fixture identity provider that is marked *never deploy*.

**So Traefik fronts the application only.** In any real deployment the IdP is a managed service with
its own certificate; the local fixture is not rehearsing that. Recorded as a named gap, not silently
skipped, and **issuer validation is not relaxed by one millimetre** — the option that would make this
easy is the option this repository has already refused twice.

---

## Tasks

Each task states the claim it earns and the proof that earns it. **No claim is written into a document
before its check has been run** (`AGENTS.md`: *claim only what you verified*).

### Task 1 — `scripts/tls/gen-dev-ca.sh`

A CA key + self-signed CA certificate, and a leaf signed by it with
`subjectAltName = DNS:localhost, DNS:app, DNS:traefik, IP:127.0.0.1`. Output under `docker/tls/`,
**gitignored**. Idempotent: it regenerates only with `--force`, so a local run does not invalidate a
running stack.

Both SAN forms matter. `run-e2e.sh` pins `127.0.0.1` everywhere because `localhost` resolves to `::1`
on the Windows dev machine and the IPv6 path does not route; a certificate carrying only
`DNS:localhost` would fail verification on exactly the address this repository is required to use.

**Proof:** `openssl verify -CAfile ca.crt server.crt` returns OK, and a run against a CA the leaf was
*not* signed by returns the error — the differential form, so "OK" is not merely "the command ran".

### Task 2 — Traefik in Compose

- `traefik` service pinned by tag **and** digest, in `profiles: [app]` so the default `up` is still
  the four backing services §6.6 and the README both assert.
- Static config `docker/traefik/traefik.yml`, dynamic config `docker/traefik/dynamic.yml` (the
  certificate, and the TLS floor: minimum version 1.2).
- Entrypoints: `web` → permanent redirect to `websecure`; `websecure` terminates TLS.
- Router labels on the `app` service pointing at `app:8080` — no `host.docker.internal` seam, because
  #11 already made the application a Compose service.
- **`app` stops publishing 8080 and 9090.** A published 8080 lets a caller bypass Traefik entirely,
  which would make the whole control decorative; and unpublishing 9090 restores §6.6's claim that the
  management port "relies on the port not being published", which publishing it had falsified.
- An explicit network `subnet` and a static `ipv4_address` for Traefik, for decision 2 above.

**Proof:** a real HTTPS request to the published port returns the application's own 401 with a
certificate that verifies against `ca.crt`, and the plaintext entrypoint returns a 301 to `https://`.

### Task 3 — the `X-Forwarded-For` control, and its two tests

`application.properties`, beside the existing §6.1 block:

```
server.forward-headers-strategy=native
server.tomcat.remoteip.internal-proxies=${LEDGER_TRUSTED_PROXIES:<traefik's pinned address>}
```

Two `@SpringBootTest(webEnvironment = RANDOM_PORT)` classes on the **fast `verify` path** — no
containers. They fork the context deliberately (`AGENTS.md` trap 5 requires a written reason; theirs
is that the property under test is a *server factory* setting, which cannot be changed after the
context is built). Both drive the IP backstop with `capacity=1` and an emptied `exempt-ips`, because
the backstop's bucket key **is** the observable for "what address did the application think you are":

1. **`ForwardedHeaderSpoofingTest`** — production configuration, so the test client at `127.0.0.1`
   is **not** a trusted proxy. Two requests carrying *different* `X-Forwarded-For` values must share
   one bucket: the second gets **429**. This is the security gate. It goes red the moment
   `internal-proxies` is widened to cover the caller.
2. **`ForwardedHeaderTrustedProxyTest`** — `internal-proxies=127.0.0.1`, so the same client *is*
   trusted. The same two requests now land in **different** buckets and both pass.

Test 2 is not a nicety. Without it, test 1 would pass just as happily if the valve were absent, the
property misspelled, or the strategy `none` — the "green run that checked nothing" AGENTS.md trap 1
exists for. **Together they are differential**: the identical request shape scores opposite outcomes
either side of one property.

**Red proof owed for each:** widen `internal-proxies` to `172.16.0.0/12` and watch test 1 fail;
set it back to the pinned address and watch test 2 fail. Both failures captured verbatim in the
commit message.

### Task 4 — the e2e suite over real HTTPS

- `run-e2e.sh` generates the CA if absent, brings up `traefik` with `app`, and points
  `LEDGER_BASE_URL` at `https://127.0.0.1:<https port>`.
- `wait-for.sh` needs no change: `curl` honours **`CURL_CA_BUNDLE`**, so the runner exports it.
- The Python CLI needs the CA too. Preference order: an environment variable `httpx` already honours;
  a `ca_bundle` setting on `Settings` passed as `verify=` only if it does not. **To be settled by
  running it, not by reading about it.**
- Keycloak stays on plaintext `8081` — decision 3.

**Proof:** the five e2e scenarios pass over HTTPS, and the run is *shown* to be HTTPS rather than
asserted (the URL alone is not evidence; a request with the CA removed must fail verification).

### Task 5 — OWASP ZAP baseline

`zaproxy/action-baseline@v0.15.0` against the HTTPS entrypoint, in its own job. Deferred to here on
purpose: run before Task 2 its first report would have been a list of the TLS findings Task 2 fixes.

Whether it becomes a **required** check is a branch-protection change only the repository owner can
make, and is stated as such rather than implied — the same honesty `depcheck` is already recorded with.

### Task 6 — `E2E_MODE=jar` stops drifting

Nothing in CI runs it. It was kept to avoid silent coverage loss for `java -jar`, and as wired it *is*
that loss. Fix: a `strategy: matrix: mode: [image, jar]` leg on the e2e job. The `image` leg goes
through Traefik over HTTPS; the `jar` leg stays plaintext and direct, which is the topology that mode
actually documents — and it only became conflict-free now that the `app` container has stopped
publishing 8080.

### Task 7 — the documents, and one of them is a rewrite

- **`docs/security-material.md`** — the TLS section is **replaced**, not amended. Its own text says
  so: *"When TLS lands, this section gets replaced by the real thing."*
- `docs/docker.md` — the HTTPS recipe and the CA-generation step.
- `docs/spec.md` — §6.1 (the forwarded-header sentence becomes a description of something that
  exists), §6.4, §12.1 (the ZAP stage), plus a revision-history row. **v3.44.**
- `README.md` prerequisites — `openssl` joins the toolchain split.
- `HANDOFF.md` — updated before context runs short, not after.

---

## Done when

1. The e2e suite performs a **real HTTPS round trip** against the Compose stack with a certificate
   chaining to the generated dev CA.
2. A test **proves a spoofed `X-Forwarded-For` cannot bypass the IP backstop**, and a second test
   proves the mechanism is live rather than absent.
3. Both run in CI **with no secret**.
4. The ZAP baseline is wired and green.
5. Every document above agrees with what was built, and each claim names the check that earned it.
