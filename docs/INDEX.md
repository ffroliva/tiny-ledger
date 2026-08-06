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
| `../README.md` | Running it for the first time | Tutorial |
| `spec.md` (v3.12) | Any question about *contract* — API shape, errors (§6.5), security model (§6.4), idempotency (§6.3), the two run modes (§1), module boundaries (§3/§4) | Explanation |
| `architecture.md` | You need the shape of the system before the detail | Explanation |
| `api/openapi.yaml` | Changing a request/response, a status code, or a validation constraint. **The generated server interfaces come from here** — edit the contract, not the generated code | Reference |
| `adr/0001-kafka-delivery-path.md` | Touching event publication, the outbox, Kafka, or the transaction boundary around publishing | Explanation |
| `adr/0002-postgres-event-store.md` | Asking why Postgres is the system of record and Kafka only the bus, or why the topic and partition key are what they are | Explanation |
| `adr/0003-test-topology-and-ci-parallelisation.md` | Adding a `@SpringBootTest`, changing CI, or wondering why there is one integration context | Explanation |
| `agentic-workflow.md` | Understanding how this was built — including §5, where the agents were wrong, and §7, the per-phase gate record | Explanation |
| `_archive/reviews/` | Checking that the review claims in `agentic-workflow.md` are real — the council reports and assessments as they were written | Reference |
| `_archive/` (start at its `README.md`) | Tracing *why* a decision was taken, or auditing what an agent was actually instructed to do. **Working artifacts, not documentation** — the README states which paths inside them are stale | — |

The Diátaxis quadrants above (spec §8.1) are the ones that have documents. `docs/` has no `how-to/`
or `tutorial/` tree: the README is the tutorial, and the operational how-tos are not written. Both
directories held a `.gitkeep` and nothing else, and were removed rather than left as a promise.

## Archived plans, newest first

**These are agent execution scripts, not documentation.** They read "Step 1: write the failing test",
carry commit heredocs, and run to 8,838 lines — over five times the length of `spec.md` — because they were
written to be executed by a subagent, not read by a person. They are archived rather than deleted so
the record of *why* each change was made survives, and so a reviewer can check the account in
`agentic-workflow.md` against the instructions that actually produced the code. **Nothing here is
current contract.** Where a plan and `spec.md` disagree, `spec.md` wins.

Everything under `_archive/` is delivered or superseded. There is no "in flight" plan directory; what
is being built next is §14 of `spec.md`, and what is known-open is its *Open issues* section.

| Plan | State |
|---|---|
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
