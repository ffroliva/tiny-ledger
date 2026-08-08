# Security material — keys, credentials, certificates: what exists, where it lives, where it is injected

**Read this before adding any credential, key or certificate to this repository.**

This is a **live document**. It describes what is true today, not what is planned, and the section
that matters most right now is the one saying **there are no TLS certificates yet** — because the
next piece of work creates them, and a document that pre-announced them would be the exact kind of
claim spec §12 had to retract once already.

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

**Verified 2026-08-08:** `git ls-files | grep -iE '\.(pem|key|p12|jks|crt|cer|pfx)$'` returns
**nothing**. Control: the same search shape finds 3 `.json` files, so the zero is an absence and not
a broken search.

| Material | Where it lives | How it is injected | Secret? |
|---|---|---|---|
| **Keycloak realm fixture** | `docker/keycloak/realm-tiny-ledger.json` (committed) | mounted read-only into the `keycloak` container; imported by `start-dev --import-realm` | **No — deliberately public.** See below |
| **Unit-test JWT signing key** | nowhere — **generated in memory** | `TestJwt` calls `KeyPairGenerator.getInstance("RSA").initialize(2048)` at class load | No — it does not outlive the JVM |
| **The app's JWT trust anchor** | not a key at all | `issuer-uri` + `jwk-set-uri`; public keys are **fetched from Keycloak at runtime** | No |
| `SONAR_TOKEN` | GitHub Actions secret; `.env.sonar` locally | `env:` on the Sonar step | **Yes** |
| `NVD_API_KEY` | GitHub Actions secret; `.env.nvd` locally | `env:` on the Dependency-Check step, passed **by variable name** | **Yes** |
| `GRAFANA_SERVICE_ACCOUNT_TOKEN` | `.env.grafana` (ignored) | `${VAR}` expansion in `.mcp.json`, read from the shell Claude Code was launched with | **Yes** |
| `GRAFANA_CLOUD_OTLP_TOKEN` / `..._HEADERS_AUTHORIZATION` | `.env.grafana` (ignored) | `environment:` on the opt-in `otel-collector` service | **Yes** |
| **TLS certificates** | **none exist** | — | — |

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

## TLS — DOES NOT EXIST YET

**There is no certificate, no CA, no keystore and no HTTPS listener in this repository today.** Every
hop in the Compose stack is plaintext, including the backing services. If you are looking for "where
is the certificate and how was it generated", the honest answer is that there is not one to find.

The design **is** agreed, and is recorded here so the next person does not re-open it:

- **Traefik terminates TLS** — the same tool in Compose and as a Kubernetes ingress controller, so
  local rehearses production.
- **A locally generated CA for dev and CI; Let's Encrypt for the deployed environment**, on the
  domain `archb.uk`.
- **CI gets no certificate secret.** It generates a throwaway CA in-run — the same principle that
  keeps the Grafana token out of CI entirely.
- **No service mesh.** Its value here would be encrypting the backing-service hops, which are a named
  gap rather than scope.
- **Backing-service TLS (Postgres, Redis, Kafka): a named gap, not built.**
- **mTLS / FAPI is a separate, later piece of work.**

**The trap that matters most in that work is a security regression, not a config detail.** Putting a
proxy in front changes every request's source address, and spec §6.1's IP backstop rate-limits by
client IP. If the application trusts `X-Forwarded-For` from anywhere, **any caller can spoof their IP
and walk past the backstop** — adding TLS would have quietly removed a control. Trust forwarded
headers only from Traefik's address, and give that a test that fails if the trust is widened.

**When TLS lands, this section gets replaced by the real thing:** how the dev CA is generated, where
the certificate and key live, how they reach the container, how CI produces its throwaway pair, and
what the renewal path is. Until then it says "not built", because that is what is true.

---

## Related: automated dependency and image scanning

Not credentials, but the same subject — see [`spec.md`](spec.md) §12.1 stages 11 and 11b:

- **GitHub Dependabot vulnerability alerts and automated security fixes are ENABLED.** They cost no
  CI minutes, email on a finding, and match exact package versions. This is the always-on instrument.
- **Trivy** scans the built container image on every push, in the required `security` job.
- **OWASP Dependency-Check** scans the build tree — including test-scope dependencies — nightly and
  whenever `pom.xml` changes. Accepted findings live in `.github/owasp-suppressions.xml`, each
  naming a specific CVE, a reason, and **an expiry date**.

---

## Review cadence

This page goes stale silently, which is the failure mode it exists to prevent. **Re-read it when:**

1. **TLS lands** — the section above is rewritten, not amended.
2. **A suppression expires** — the next is **2026-11-08**. Re-check for a stable upstream release
   *first*, upgrade if one exists, and suppress again only if none does.
3. **Anything is deployed anywhere real** — at which point the fixture realm, the public clients and
   the plaintext hops all stop being acceptable, together.
4. **A new secret is introduced** — add its name to `.env.example` and a row to the table above, in
   the same commit that introduces it.
