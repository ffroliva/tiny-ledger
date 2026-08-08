# Security material — keys, credentials, certificates: what exists, where it lives, where it is injected

**Read this before adding any credential, key or certificate to this repository.**

This is a **live document**. It describes what is true today, not what is planned. Until 2026-08-08
the section that mattered most was the one saying **there are no TLS certificates yet**; that work
has landed, and the TLS section below has been **replaced** by what exists — which is what that
section said would happen, rather than amended around the edges.

> Contract questions → [`spec.md`](spec.md) §6.4. Running the stack → [`docker.md`](docker.md).
> The rules an agent must know → [`../AGENTS.md`](../AGENTS.md).

---

## The one rule

**This repository is PUBLIC. It holds the NAMES and SHAPES of secrets. It never holds their values.**

`.env.example` is committed and carries empty assignments. `.env` and `.env.*` are ignored, with
`!.env.example` as the single exception (`.gitignore:53-54`). There is deliberately **no production
`.env`** — nothing here loads a dotenv file at all. The application reads
`application.properties`, then `application-{profile}.properties`, then real environment variables
and `--args`. A `.env` configures *your shell*, never the application.

In a deployment those variables come from the platform's secret store — Compose or Kubernetes env,
GitHub Actions secrets for CI.

**What enforces this:** `gitleaks`, pinned by version and sha256, runs on every push in the
`security` job and fails the build. `.gitignore` prevents the common accident. Nothing else does —
and per `AGENTS.md`, a rule with no gate is a hope, so that is stated rather than implied.

---

## What exists today

**Verified 2026-08-08, and re-verified after TLS landed:** `git ls-files | grep -iE
'\.(pem|key|p12|jks|crt|cer|pfx)$'` returns **nothing**. Control: the same search shape finds 3
`.json` files, so the zero is an absence and not a broken search. Note that `git ls-files` is the
right question and `ls` is not — `docker/tls/` holds real key material on any machine that has run
the e2e suite, and is kept out of git by `.gitignore` rather than by not existing.

| Material | Where it lives | How it is injected | Secret? |
|---|---|---|---|
| **Keycloak realm fixture** | `docker/keycloak/realm-tiny-ledger.json` (committed) | mounted read-only into the `keycloak` container; imported by `start-dev --import-realm` | **No — deliberately public.** See below |
| **Unit-test JWT signing key** | nowhere — **generated in memory** | `TestJwt` calls `KeyPairGenerator.getInstance("RSA").initialize(2048)` at class load | No — it does not outlive the JVM |
| **The app's JWT trust anchor** | not a key at all | `issuer-uri` + `jwk-set-uri`; public keys are **fetched from Keycloak at runtime** | No |
| `SONAR_TOKEN` | GitHub Actions secret; `.env.sonar` locally | `env:` on the Sonar step | **Yes** |
| `NVD_API_KEY` | GitHub Actions secret; `.env.nvd` locally | `env:` on the Dependency-Check step, passed **by variable name** | **Yes** |
| `GRAFANA_SERVICE_ACCOUNT_TOKEN` | `.env.grafana` (ignored) | `${VAR}` expansion in `.mcp.json`, read from the shell Claude Code was launched with | **Yes** |
| `GRAFANA_CLOUD_OTLP_TOKEN` / `..._HEADERS_AUTHORIZATION` | `.env.grafana` (ignored) | `environment:` on the opt-in `otel-collector` service | **Yes** |
| **Dev CA + server certificate** | `docker/tls/` — **gitignored**, never committed | generated on demand by `scripts/tls/gen-dev-ca.sh`; CI runs the same script in-run | **No** — throwaway, per machine or per CI run |

**CI holds exactly two secrets: `SONAR_TOKEN` and `NVD_API_KEY`.** No Grafana credential, no registry
credential, no certificate. Both CI steps that use a secret **skip loudly and say they skipped**
when it is absent, never reporting a pass they did not earn — so a fork's build goes green holding
nothing.

---

## The fixture credentials, and why they are in plain sight

`docker/keycloak/realm-tiny-ledger.json` seeds seven users with the literal password `dev-only`, and
the realm's display name is *"tiny-ledger TEST FIXTURE — dev-only credentials, never deploy"*.

This is a decision, not an oversight:

- The value is 8 bytes, low-entropy, and **reused across every identity** — the opposite of what a
  leaked credential looks like, and below every bundled gitleaks rule's shape and entropy threshold.
  Checked against this job's exact pinned ruleset with a real scan: **zero findings on this path.**
- **It is deliberately NOT allowlisted.** There is no active finding to suppress, and a standing
  path exemption would silently cover any future content pasted into that same file — including a
  real secret pasted in by mistake. If a rule update ever does start matching it, fix it with an
  allowlist naming the **specific rule ID**, or swap the literal for a non-password-shaped fixture
  value. Never a blanket file exemption.
- `.env.example` writes `LEDGER_PASSWORD=dev-only` as an actual value rather than a blank, because it
  is already public and leaving it blank would imply it is a secret.

**Nothing in this realm is a template for a real one.** Both clients are public with direct access
grants enabled — appropriate for a fixture that has to mint tokens from a shell script, and
unacceptable anywhere else.

---

## The one piece of key material in git history

`src/test/resources/test-jwt-private.pem` was committed in `f334f81` and deleted from the tree in
`58f2638`. **It was never removed from history and is still reachable:**

```bash
git rev-list --all --objects -- src/test/resources/test-jwt-private.pem
```

**No rotation is owed, and that conclusion is deliberate rather than convenient.** The key was
test-only, had no consumer, and no issuer ever trusted it. The `filter-repo` rewrite that would have
erased it was explicitly dropped on 2026-08-06: it would have force-pushed eight branches, orphaned
every worktree — including one holding unpushed work — and required rewriting commit messages, all on
submission day. Highest risk, lowest value.

**But the calculation that justified it has changed, and this is the live item on this page.** That
decision was taken while the repository was **private**, and `CHANGELOG.md:97` recorded the trigger
as *"if the repository is ever made public, revisit that trade"*. **The repository is now public.**
The material facts have not changed — still test-only, still no consumer, still no issuer that
trusted it — so the conclusion still holds. What has changed is that the audience is now everyone,
so the reasoning has to be written down where a reader will find it rather than left in a changelog
line. That is what this section is.

The replacement is strictly better and needs no key at all: `TestJwt` generates an **ephemeral
2048-bit RSA key in memory** at class load, so the tests sign with a key that did not exist before
the JVM started and does not survive it.

---

## TLS — BUILT, for the edge only

**Traefik terminates TLS in front of the application.** The certificate is generated on demand, is
never committed, and CI holds no certificate secret at all — it generates its own throwaway CA
inside the run. What follows is what exists, verified by running it.

### Where the material lives, and why none of it is in git

| Thing | Where | How it gets there | In git? |
|---|---|---|---|
| Dev CA key + certificate | `docker/tls/ca.key`, `docker/tls/ca.crt` | `scripts/tls/gen-dev-ca.sh` | **No** — `docker/tls/` is gitignored |
| Server key + certificate | `docker/tls/server.key`, `docker/tls/server.crt` | signed by that CA, same script | **No** |
| The certificate Traefik serves | inside the container | read-only bind mount of `docker/tls` | n/a |
| The CA a client trusts | `SSL_CERT_FILE`, exported by `run-e2e.sh` | the file above | n/a |

The generator is **idempotent** — it regenerates only with `--force`, because handing Traefik a new
certificate under a running stack produces a failure that reads as a routing problem. It verifies
its own output before exiting:

```
openssl verify -CAfile docker/tls/ca.crt docker/tls/server.crt   -> OK                      exit 0
openssl verify -CAfile <a DIFFERENT CA>  docker/tls/server.crt   -> verification failed     exit 2
```

The second line is the control. Without it, "OK" would only mean the command ran.

**The certificate carries `DNS:localhost, DNS:app.localhost, DNS:auth.localhost, DNS:app,
DNS:keycloak, DNS:traefik, IP:127.0.0.1, IP:::1`.** `auth.localhost` is the one the whole
authentication path depends on, since `iss` is minted there. The IP SANs
are load-bearing: `scripts/e2e/run-e2e.sh` is obliged to dial `127.0.0.1` because `localhost`
resolves to `::1` first on the development machine and the IPv6 path does not route there.

### What TLS is, and what it is not, in this repository

| | |
|---|---|
| **Terminated at** | Traefik, `profiles: [app]`, published on **`443`** (HTTPS) and `80` (301 → HTTPS) |
| **Routed to** | `app:8080` **and `keycloak:8080`** by service name, in-network, plaintext |
| **NOT terminated for** | every backing service — Postgres, Redis, Kafka |
| **Minimum version** | TLS 1.2, set as the default TLS option |
| **Headers set at the edge** | `X-Content-Type-Options`, `X-Frame-Options`, `Cross-Origin-Resource-Policy: same-origin`. **HSTS deliberately not sent — and from 2026-08-08 that is actually true.** It was sent by the *application* (Spring Security's default writer + `forward-headers-strategy=native`) while this row and four other documents said otherwise; `SecurityConfig#hstsOff()` now disables it and `SecurityConfigTest#hstsIsNotSentOnASecureRequest` is the gate — a pin on bare `localhost` is port-independent and would break every other local dev server for a year ([`pitfalls.md`](pitfalls.md)) |

**The application-to-Traefik hop is plaintext, and so is every backing-service hop.** That is the
named gap this design chose to leave open, not an oversight — a service mesh is the tool for it, and
ADR 0005 records why one is not in scope.

**Keycloak IS fronted by Traefik**, decided 2026-08-08 and recorded before the work started. One
ingress, one certificate story, and no second scheme in the stack — an OIDC provider on plain HTTP
beside an HTTPS resource server is the shape that teaches people TLS is optional. It is **no longer
published on 8081 at all**, which matters more than it looks: a plaintext Keycloak on a host port
would mint tokens whose `iss` is derived from whatever the caller typed there, and that is a
*different* issuer from the one the application trusts.

**That made it a rename, not a toggle**, and the issuer now reads `https://auth.localhost/realms/tiny-ledger`
in every one of the eight places that spell it. They move together or nothing authenticates, and the
failure is a flat `401` that says nothing about which side is wrong.

**The port is absent from that string on purpose.** Traefik publishes **443**, the scheme default,
so it drops out of the URL — which is what a deployment's issuer looks like and keeps a port number
out of eight files. `TINY_LEDGER_HTTPS_PORT` is still an escape hatch for a clash, but it is **not a
free knob**: move it and `TINY_LEDGER_AUTH_ORIGIN` must move with it.

Two consequences, both decided rather than discovered:

- **`KC_PROXY_HEADERS=xforwarded` and `KC_HTTP_ENABLED=true`.** Traefik terminates TLS and speaks
  plain HTTP to the container; without the first, Keycloak builds URLs from that *internal* request
  and the issuer drifts back to something no client can reach.
- **`jwk-set-uri` stays in-network** (`http://keycloak:8080/…`) even though `iss` is HTTPS. Issuer
  validation and key fetching are independent — verified in the shipped bytecode during #11 — so the
  fetch stays plaintext inside the network and the dev CA never has to enter the app container's
  truststore. **Nothing about issuer or audience validation was relaxed to make any of this work.**

**One client cannot use that shortcut, and it is the one people forget.** `E2E_MODE=jar` runs the
application *on the host*, where it resolves the issuer itself over TLS — and a JVM reads neither
`SSL_CERT_FILE` nor a PEM. `gen-dev-ca.sh` therefore also emits `docker/tls/truststore.p12` (one CA,
password `changeit`, no private key), and the runner passes it with `-Djavax.net.ssl.trustStore`
**before** `-jar`, since after it those are application arguments and are silently ignored. Measured
with the store pointed at a path that does not exist:

```
PKIX path building failed: unable to find valid certification path to requested target   -> HTTP 500
```

and 7 passed with it. So the flag is load-bearing rather than defensive.

**Traefik is given no access to the Docker socket.** The usual Docker provider — which discovers
routes from container labels — requires mounting `/var/run/docker.sock`, which is root-equivalent on
the host. This uses the file provider instead: **two routers and two services** — the ledger and Keycloak —
written out by hand in `docker/traefik/dynamic.yml`. Adding TLS is not a reason to hand a network-facing container root.

### The `X-Forwarded-For` control — the part that is a security property

Spec §6.1 row 4 rate-limits *any traffic, per IP* at 300/minute, and `IpBackstopFilter` reads
`getRemoteAddr()`. A proxy in front makes every request arrive from the proxy's address, so the
application has to read the forwarded address — and the moment it does, the question is **from
whom**. Answer it wrong and adding TLS *removes* a control: a caller varying one header lands in a
fresh bucket every request and never exhausts one.

```properties
server.forward-headers-strategy=native
server.tomcat.remoteip.internal-proxies=${LEDGER_TRUSTED_PROXIES:10.89.0.250}
```

**`native`, not `framework`, and that choice is the control.** `framework` is Spring's
`ForwardedHeaderFilter`, which has no trusted-proxy concept at all and processes `X-Forwarded-*`
from any peer — it is the setting that *creates* the hole. `native` is Tomcat's `RemoteIpValve`,
which rewrites `remoteAddr` only when the directly connected peer already matches
`internal-proxies`.

**Boot's default for `internal-proxies` is unsafe here, and that is measured rather than argued.**
The default covers `172.16.0.0/12` — the range Docker hands to Compose networks — so it trusts every
container on the network, and on a Kubernetes pod network every pod. That is why
`docker-compose.yml` declares an explicit subnet and pins Traefik to a static address: the trust
names one host.

**Two tests are the gate, and neither is worth much alone** (`src/test/java/.../platform/`):

| Test | Configuration | Two requests, different `X-Forwarded-For` |
|---|---|---|
| `ForwardedHeaderSpoofingTest` | as shipped — the caller is **not** the trusted proxy | share one bucket → **429** |
| `ForwardedHeaderTrustedProxyTest` | `internal-proxies` covers the caller | separate buckets → **both 200** |

The first would pass just as happily with the valve missing, the property misspelled or the strategy
left at `none`. Together they are **differential**: one property, contradictory results. Both red
proofs were run; commenting the property out so Boot's default applies turns the spoofing test's
`429` into a `200`, which is what makes "the default is exploitable on this stack" a measurement.

**Proven live through the real proxy too**, backstop capacity 2, four requests each with a different
spoofed address: `401, 401, 429, 429`. Traefik *appends* the real client to `X-Forwarded-For` and
`RemoteIpValve` walks the list right to left, so the spoofed entry is discarded.

### How CI gets a certificate without a secret

`scripts/e2e/run-e2e.sh` and the `zap` job both call `scripts/tls/gen-dev-ca.sh`. A clean checkout
produces a fresh throwaway CA per run. **CI still holds exactly two secrets, `SONAR_TOKEN` and
`NVD_API_KEY`, and neither of these jobs uses either.** A fork's build goes green holding nothing.

**The e2e suite proves the round trip is real TLS rather than asserting it.**
`scripts/e2e/https-check.py` runs the same request against two trust stores and requires both
outcomes — verified against the dev CA, rejected by the public one. That control earned its keep on
its first run: Traefik selects certificates by SNI, RFC 6066 forbids an IP literal in SNI, so a
`127.0.0.1` dial carried no server name, nothing in a `tls.certificates:` list could match it, and
Traefik served its own `CN=TRAEFIK DEFAULT CERT` while every request succeeded end to end. Fixed
with `tls.stores.default.defaultCertificate`. **Without the control that ships as "HTTPS works".**

### What is NOT built, and what each is blocked on

- **Let's Encrypt — blocked on a *deployment* decision, not on TLS.** HTTP-01 needs a publicly
  reachable `archb.uk:80/443`; DNS-01 needs a provider token. ADR 0005 makes Kubernetes the
  production target and **no manifests exist**, so there is no environment to issue a certificate
  *for* and nowhere to hold an ACME account key. Writing an `acme` resolver into the configuration
  today would be configuration for a host that does not exist.
- **Backing-service TLS (Postgres, Redis, Kafka)** — a named gap. Every hop behind Traefik is
  plaintext.
- **mTLS / FAPI** — its own later plan. The archived OBIE assessment sized it **L**.
- **Certificate renewal** — there is none, and none is owed: the material is regenerated on demand
  and valid 825 days, which outlives any stack it is issued for.

### Rotation

**Nothing here is owed rotation.** The CA and leaf are per-machine or per-CI-run, no client outside
this stack trusts either, and nothing signed with them would be accepted anywhere. If a developer's
`docker/tls/` is ever suspected, the response is `scripts/tls/gen-dev-ca.sh --force` and a restart —
not an incident.

---

## Related: automated dependency and image scanning

Not credentials, but the same subject — see [`spec.md`](spec.md) §12.1 stages 11 and 11b:

- **GitHub Dependabot vulnerability alerts and automated security fixes are ENABLED.** They cost no
  CI minutes, email on a finding, and match exact package versions. This is the always-on instrument.
- **Trivy** scans the built container image on every push, in the required `security` job, **and
  since stage 11d also the Compose images** — the second of those reports rather than gates.

**What each ecosystem is actually covered by — checked, not assumed:**

| Ecosystem | Dependabot | OWASP Dependency-Check | Trivy |
|---|---|---|---|
| **Java / Maven** (`pom.xml`) | ✅ weekly, grouped, majors excluded | ✅ nightly + on `pom.xml` | ✅ inside the built image |
| **GitHub Actions** | ✅ weekly | ✗ | ✗ |
| **Python** (`ledger-cli`, `uv.lock`) | ✅ weekly, `uv` ecosystem | ✗ — the plugin is Maven-only | ✗ — not in the image |
| **Compose images** (postgres, redis, kafka, keycloak, traefik, collector) | ❌ **nothing, and no configuration fixes it** | ✗ | ⚠️ **scanned, reports only** — stage 11d, every push |
| **Buildpack builder / run image** (pinned by digest in `pom.xml`) | ❌ nothing | ✗ | ✅ *indirectly* — their OS layers are what Trivy scans |

**Dependabot cannot reach the Compose images, and that is structural rather than a configuration
oversight.** Its `docker` ecosystem reads **only** files matching `/dockerfile|containerfile/i` —
verified in `dependabot-core`'s `docker/lib/dependabot/docker/file_fetcher.rb` — and there is no
`docker-compose` ecosystem. This repository has **no Dockerfile at all**, by design: the image comes
from buildpacks (spec §12). So adding `package-ecosystem: docker` here would match nothing.

Those images are pinned (`postgres:16-alpine`, `redis:7-alpine`, `confluentinc/cp-kafka:7.6.0`,
`quay.io/keycloak/keycloak:26.4`, `traefik:v3.5` by digest,
`otel/opentelemetry-collector-contrib:0.158.0`), which is the right posture for reproducibility and
the wrong one for staleness. **Stage 11d is what now tells someone when one of them grows a CVE**: a
shell loop in the required `security` job over the image refs parsed out of
`docker/docker-compose.yml`, at `CRITICAL,HIGH` with `ignore-unfixed`, writing a per-image count to
the job summary and the CVE list to the log.

**It reports; it does not gate — and the distinction is deliberate, not an unfinished edge.** Those
tags are months old, so the step arrives with findings, and a scanner that lands red for reasons
nobody has triaged is how a gate gets ignored. `--exit-code` is therefore absent. Read the summary
table before treating this row as closed: **the gap is now observable, not yet closed.**

**One thing in that step can fail the build, and it is not a finding.** The step asserts it parsed
**six** image refs and exits 1 otherwise, because a parse that matched nothing would scan nothing and
print an empty table — a result identical to six genuinely clean images (`AGENTS.md` trap 8).
Proven differentially before it landed: the real file parses 6 and exits 0; the same file with its
`image:` keys renamed parses 0 and exits 1.

The compose file is the **only** authority for that list. Copying six refs into `ci.yml` would go
stale the first time one was bumped, and the staleness would be invisible — a green scan against
versions nothing runs.

- **OWASP Dependency-Check** scans the build tree — including test-scope dependencies — nightly and
  whenever `pom.xml` changes. Accepted findings live in `.github/owasp-suppressions.xml`, each
  naming a specific CVE, a reason, and **an expiry date**.

---

## Review cadence

This page goes stale silently, which is the failure mode it exists to prevent. **Re-read it when:**

1. **TLS changes shape** — Let's Encrypt is wired, a deployment appears, or backing-service TLS
   stops being a gap. The TLS section is then rewritten again, not amended. (Its first rewrite,
   for the dev-CA work, happened 2026-08-08.)
2. **A suppression expires** — the next is **2026-11-08**. Re-check for a stable upstream release
   *first*, upgrade if one exists, and suppress again only if none does.
3. **Anything is deployed anywhere real** — at which point the fixture realm, the public clients and
   the plaintext hops all stop being acceptable, together.
4. **A new secret is introduced** — add its name to `.env.example` and a row to the table above, in
   the same commit that introduces it.
