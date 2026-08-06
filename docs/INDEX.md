# Documentation index

Routing table. **Read on need, not on principle** — this exists so an agent can find the one authority
that answers the question in front of it, rather than reading 200KB of specification to change a filter.

The rules you need *before* touching anything are not here — they are in **`../AGENTS.md`** (source of
truth, vendor-neutral; every tool's convention file routes to it).

Quadrants are Diátaxis, per spec §8.5.

## Read this when…

| Read | When | Quadrant |
|---|---|---|
| **`../AGENTS.md`** | **Always, first.** The gates, the enforced rules, the traps already paid for | — |
| `../README.md` | Running it for the first time | Tutorial |
| `spec.md` (v3.11) | Any question about *contract* — API shape, errors (§6.5), security model (§6.4), idempotency (§6.3), the two run modes (§1), module boundaries (§3/§4) | Explanation |
| `architecture.md` | You need the shape of the system before the detail | Explanation |
| `api/openapi.yaml` | Changing a request/response, a status code, or a validation constraint. **The generated server interfaces come from here** — edit the contract, not the generated code | Reference |
| `adr/0001-kafka-delivery-path.md` | Touching event publication, the outbox, Kafka, or the transaction boundary around publishing | Explanation |
| `adr/0003-test-topology-and-ci-parallelisation.md` | Adding a `@SpringBootTest`, changing CI, or wondering why there is one integration context | Explanation |
| `agentic-workflow.md` | Understanding how this was built — including §5, where the agents were wrong, and §7, the per-phase gate record | Explanation |
| `governance-baseline.md` | Adding or moving a document; CI stage 6 enforces it | Reference |
| `how-to/` | A specific operational task | How-to |
| `tutorial/` | Learning the system end to end | Tutorial |
| `superpowers/plans/` | What is being built now, and the decisions behind it. Newest first; each plan states its own scope and what it deliberately defers | Explanation |
| `source/` | The original brief, committed unmodified | Reference |
| `_archive/` | Superseded material. **Do not treat as current** | — |

## Plans, newest first

| Plan | State |
|---|---|
| `superpowers/plans/2026-08-07-admin-on-behalf-of.md` | Plan only, **not yet applied** — turns the repaired `2026-08-04-spec-admin-on-behalf-of-proposal.md` (decisions D1–D8) into executable tasks against `phase-4-plan3-hardening`; `ledger:admin` does not exist in `src/main` yet |
| `superpowers/plans/2026-08-06-security-hardening.md` | **Delivered** — closed the four security gaps spec v3.10 recorded open (rate limiter §6.1, `aud` validation, `x-fapi-interaction-id` bound/validated, `/error` path leak excluded) plus CI stage 11 (`gitleaks`); spec v3.11 close-out |
| `superpowers/plans/2026-08-06-roles-and-keycloak-realm.md` | **Delivered** — `ledger:reader`/`writer`/`auditor` enforced on the `full` filter chain, a real Keycloak container and realm behind the integration suite; spec v3.10 close-out |
| `superpowers/plans/2026-08-05-spec-v3.9-and-proposal-repair.md` | Delivered — spec v3.9 truth alignment folded into `docs/spec.md`; the admin on-behalf-of proposal repaired, not applied |
| `superpowers/plans/2026-08-05-plan-3-security-and-authorization.md` | **Delivered (Plan 3)** — council-reviewed three times; the third round's 13 P0s are folded into the task text, so the plan reads as executed rather than as first drafted |
| `superpowers/plans/2026-08-05-plan-3-research.md` | Research input to the above |
| `superpowers/plans/2026-08-04-error-handling-catalogue-proposal.md` | Approved, folded into Plan 3 |
| `superpowers/plans/2026-08-04-spec-admin-on-behalf-of-proposal.md` | Approved; its decisions (D1–D8) are now executable tasks in `superpowers/plans/2026-08-07-admin-on-behalf-of.md`, not yet applied. Its "targets spec v3.9" is stale — v3.9 was spent on the truth-alignment pass above; the proposal's own target version is `TBD`, assigned by the applying plan |
| `superpowers/plans/2026-08-04-open-banking-standards-review.md` | Four items approved. **Contains two known errors** corrected by the Plan 3 research: the DPoP DSL is `@since 7.1` and opt-in, not "6.5+ auto-validated", and Keycloak is 26.7 |
| `superpowers/plans/2026-08-04-full-persistence.md` | Delivered (Plan 2) |
| `superpowers/plans/2026-08-03-standalone-core.md` | Delivered (Plan 1) |

## Keeping this honest

A stale index is worse than none — it sends readers confidently to the wrong place. This file was itself
stale until 2026-08-05, listing five documents while `docs/` held four subdirectories and seven plans.

When you add a document, **add a row here by hand.** Nothing will remind you:
`scripts/ci/check_docs_governance.py` reads a curated list in `governance-baseline.md` and does **not**
discover new files — it reported `17 known, 0 new` both before and after four documents were added on
2026-08-05, including an ADR. Its name and its green result both imply otherwise, so do not rely on it
as a safety net for this. Making it discover additions is an open task.

If a document's claims stop matching the code, fix or retract them the same day. `spec.md` reached v3.8
for exactly this reason (finding CR14), and is v3.11 now.
