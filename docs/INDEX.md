# Documentation index

Routing table. **Read on need, not on principle** — this exists so an agent can find the one authority
that answers the question in front of it, rather than reading 200KB of specification to change a filter.

The rules you need *before* touching anything are not here — they are in **`../AGENTS.md`** (source of
truth, vendor-neutral; every tool's convention file routes to it).

Quadrants are Diátaxis, per spec §8.1.

## Read this when…

| Read | When | Quadrant |
|---|---|---|
| **`../AGENTS.md`** | **Always, first.** The gates, the enforced rules, the traps already paid for | — |
| `../README.md` | Running it for the first time — `standalone`, one command, no Docker | Tutorial |
| **`docker.md`** | **Running the `full` profile: build the image, start the stack, get a token, move money, tear down.** A runbook of verified commands, plus a symptom→cause table for the responses that look like faults and are not (`403` on the health root, a **refused connection on 8080/9090 now that neither is published**, `401` from an issuer mismatch, and a Windows `curl` that will not accept the dev CA) | Tutorial / How-to |
| **`ledger-cli.md`** | **Using the Python CLI** — installing it with `uv`, how it gets a token in `full` (Direct Access Grants, and the fixture users with their roles), worked deposit/withdraw/balance examples, the seven `scenario run` sequences and what each proves, and a symptom→cause table. Also the honest list of what it does *not* do: `--json` parses and is ignored, there is no `ledger-cli` service account, nothing is seeded | Tutorial / How-to |
| **`urls-and-tls.md`** | **Which URLs exist, which are public, which are internal-only, what is encrypted and where the encryption stops — and how to run WITHOUT TLS.** One place for a map that was previously spread across a Compose file, two runbooks and a properties file, which is how five of its facts ended up disagreeing. Also the port variables that are not free knobs | Explanation / Reference |
| **`pitfalls.md`** | **The runtime failures that cost hours, grouped by the symptom you actually see** — every 401 whose real cause is a certificate, the Windows curl that cannot take a private CA, Traefik serving a certificate you did not generate, rate limits that fire for no reason, and the things that look broken and are correct. `AGENTS.md` covers the build-and-test equivalents; this is the runtime half | How-to / Reference |
| **`security-material.md`** | **Adding or looking for any credential, key or certificate.** What exists today and where each is injected, why the Keycloak fixture password is public on purpose, the one key still in git history and why no rotation is owed — and **how TLS actually works here** — Traefik terminating at the edge, a dev CA generated on demand and never committed, the `X-Forwarded-For` trust that keeps §6.1's per-IP backstop from being bypassed, and the hops that are still plaintext | Explanation / Reference |
| `spec.md` (v3.52) | Any question about *contract* — API shape, errors (§6.5), security model (§6.4), idempotency (§6.3), observability and health (§6.6), the pipeline and what actually gates (§12.1), the two run modes (§1), module boundaries (§3/§4) | Explanation |
| `architecture.md` | You need the shape of the system before the detail | Explanation |
| `api/openapi.yaml` | Changing a request/response, a status code, or a validation constraint. **The generated server interfaces come from here** — edit the contract, not the generated code. **Every parameter and property carries an example describing one coherent account, so this file *is* the Postman collection** — import it and set *Parameter generation: **Example*** in the import dialog. On the default, *Schema*, Postman fakes values from types and ignores every example in the file, whatever you do to it. Do not hand-write a collection beside this | Reference |
| `adr/0001-kafka-delivery-path.md` | Touching event publication, the outbox, Kafka, or the transaction boundary around publishing | Explanation |
| `adr/0002-postgres-event-store.md` | Asking why Postgres is the system of record and Kafka only the bus, or why the topic and partition key are what they are | Explanation |
| `adr/0003-test-topology-and-ci-parallelisation.md` | Adding a `@SpringBootTest`, changing CI, or wondering why there is one integration context | Explanation |
| `adr/0004-readiness-does-not-gate-on-lag.md` | Touching the health probes, the readiness group, or the lag gauge — or asking why `E9` was rewritten rather than implemented as specified | Explanation |
| `adr/0005-kubernetes-is-the-production-target.md` | Adding a meter tag, a resource attribute or anything an operator consumes — or asking where this deploys, why Compose is not it, and why there are no manifests | Explanation |
| `agentic-workflow.md` | Understanding how this was built — including §5, where the agents were wrong, and §7, the per-phase gate record | Explanation |
| `_archive/reviews/` | Checking that the review claims in `agentic-workflow.md` are real — the council reports and assessments as they were written | Reference |
| `_archive/` (start at its `README.md`) | Tracing *why* a decision was taken, or auditing what an agent was actually instructed to do. **Working artifacts, not documentation** — the README states which paths inside them are stale | — |
| `superpowers/plans/` and `superpowers/specs/` | The same kind of artifact as `_archive/plans/`, for the **five most recent** pieces of work — step 9's probes and tracing, step 10's e2e runtime, the buildpack image, and the TLS/Traefik pass. **All delivered; none archived yet.** Added to this table 2026-08-08, having been absent while the directory held five files | — |

The Diátaxis quadrants above (spec §8.1) are the ones that have documents. `docs/` still has no
`how-to/` or `tutorial/` tree — both directories held a `.gitkeep` and nothing else, and were removed
rather than left as a promise. The quadrant is not empty any more, though: the README is the
tutorial, and `docker.md` and `ledger-cli.md` are the two operational runbooks, kept as flat files
rather than reinstating a tree for two documents.

## Archived plans, newest first

**These are agent execution scripts, not documentation.** They read "Step 1: write the failing test",
carry commit heredocs, and run to 8,838 lines — over five times the length of `spec.md` — because they were
written to be executed by a subagent, not read by a person. They are archived rather than deleted so
the record of *why* each change was made survives, and so a reviewer can check the account in
`agentic-workflow.md` against the instructions that actually produced the code. **Nothing here is
current contract.** Where a plan and `spec.md` disagree, `spec.md` wins.

Everything under `_archive/` is delivered or superseded, and so is everything under
`superpowers/` — **there is still no "in flight" plan directory**, but that sentence was doing more
work than it could carry until 2026-08-08: `docs/superpowers/plans/` was recreated after the
2026-08-06 move and has held five delivered plans since, unlisted by this table and described by
`_archive/README.md` as a path that no longer exists. Both are now accurate. What is being built next
is §14 of `spec.md`; what is known-open is its *Open issues* section.

The two directories should be merged — five delivered plans in a second location is exactly the
"two sources of truth" shape `AGENTS.md` opens by warning about. That is named here rather than done,
because moving them rewrites paths cited from `agentic-workflow.md` and the commit history, and a
rename is only proven by asserting the old name is gone (`AGENTS.md`, trap 2).

| Plan | State |
|---|---|
| `_archive/plans/2026-08-06-battle-testing.md` | **Delivered** — the concurrency/idempotency pass: N2, N19–N23, P7, P10, E6, E7, E10–E12 covered, four production defects fixed and §6.3's racing-duplicate mechanism corrected against a measurement (spec v3.13–v3.31). **Carries a correction header listing six places the plan itself was wrong** — most usefully "the spec's §12 catalogue", which is §9.3, and a write-budget change that would have thinned the very margin it was protecting |
| `_archive/plans/2026-08-07-admin-on-behalf-of.md` | **Applied through task 5** — `ledger:admin` exists in `src/main`: `CallerPrincipal:55` reads the authority, `RecordMovementService:65` is the one comparison it widens, and the acting principal reaches the audit trail on the event. **Fully applied** — task 6 landed spec v3.12, whose revision-history row carries both this plan and the 2026-08-06 documentation pass (§5, §8–§8.6, §10, §12.1, §14, the glossary and *Traceability*) |
| `_archive/plans/2026-08-06-security-hardening.md` | **Delivered** — closed the four security gaps spec v3.10 recorded open (rate limiter §6.1, `aud` validation, `x-fapi-interaction-id` bound/validated, `/error` path leak excluded) plus CI stage 11 (`gitleaks`); spec v3.11 close-out |
| `_archive/plans/2026-08-06-roles-and-keycloak-realm.md` | **Delivered** — `ledger:reader`/`writer`/`auditor` enforced on the `full` filter chain, a real Keycloak container and realm behind the integration suite; spec v3.10 close-out |
| `_archive/plans/2026-08-05-spec-v3.9-and-proposal-repair.md` | Delivered — spec v3.9 truth alignment folded into `docs/spec.md`; the admin on-behalf-of proposal repaired, not applied |
| `_archive/plans/2026-08-05-plan-3-security-and-authorization.md` | **Delivered (Plan 3)** — council-reviewed three times; the third round's 13 P0s are folded into the task text, so the plan reads as executed rather than as first drafted |
| `_archive/plans/2026-08-05-plan-3-research.md` | Research input to the above |
| `_archive/plans/2026-08-04-error-handling-catalogue-proposal.md` | Approved, folded into Plan 3 |
| `_archive/plans/2026-08-04-spec-admin-on-behalf-of-proposal.md` | Approved; its decisions (D1–D8) were executed by `_archive/plans/2026-08-07-admin-on-behalf-of.md`. Its "targets spec v3.9" is stale — v3.9 was spent on the truth-alignment pass above; the proposal's own target version is `TBD`, assigned by the applying plan |
| `_archive/plans/2026-08-04-open-banking-standards-review.md` | Four items approved. **Contains two known errors** corrected by the Plan 3 research: the DPoP DSL is `@since 7.1` and opt-in, not "6.5+ auto-validated", and Keycloak is 26.7 |
| `_archive/plans/2026-08-04-full-persistence.md` | Delivered (Plan 2) |
| `_archive/plans/2026-08-03-standalone-core.md` | Delivered (Plan 1) |
| `_archive/specs/2026-08-05-spec-v3.9-and-proposal-repair-design.md` | The design doc behind `_archive/plans/2026-08-05-spec-v3.9-and-proposal-repair.md` |

## Keeping this honest

A stale index is worse than none — it sends readers confidently to the wrong place. This file was itself
stale until 2026-08-05, listing five documents while `docs/` held four subdirectories and seven plans.

When you add a document, **add a row here by hand.** Nothing will remind you — **no gate enforces
anything about documentation in this repository.** There was one: a CI stage 6 wrapping a vendored
ISO governance test, which resolved its repository root inside its own skill directory and so
reported `17 known, 0 new` whatever changed under `docs/`. It was deleted on 2026-08-06 rather than
repaired, because repairing it would have made CI demand seventeen ISO compliance artefacts this
project has no business carrying. Spec §8.4 records the decision.

So this table is hand-maintained, and that is the whole of the mechanism. If a document's claims stop
matching the code, fix or retract them the same day — `spec.md` reached v3.8 for exactly that reason
(finding CR14).
