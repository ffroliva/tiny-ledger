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
| `spec.md` (v3.8) | Any question about *contract* — API shape, errors (§6.5), security model (§6.4), idempotency (§6.3), the two run modes (§1), module boundaries (§3/§4) | Explanation |
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
| `superpowers/plans/2026-08-05-plan-3-security-and-authorization.md` | Planned, council-reviewed, not started |
| `superpowers/plans/2026-08-05-plan-3-research.md` | Research input to the above |
| `superpowers/plans/2026-08-04-error-handling-catalogue-proposal.md` | Approved, folded into Plan 3 |
| `superpowers/plans/2026-08-04-spec-admin-on-behalf-of-proposal.md` | Approved, targets spec v3.9, not yet applied |
| `superpowers/plans/2026-08-04-open-banking-standards-review.md` | Four items approved. **Contains two known errors** corrected by the Plan 3 research: the DPoP DSL is `@since 7.1` and opt-in, not "6.5+ auto-validated", and Keycloak is 26.7 |
| `superpowers/plans/2026-08-04-full-persistence.md` | Delivered (Plan 2) |
| `superpowers/plans/2026-08-03-standalone-core.md` | Delivered (Plan 1) |

## Keeping this honest

A stale index is worse than none — it sends readers confidently to the wrong place. This file was itself
stale until 2026-08-05, listing five documents while `docs/` held four subdirectories and seven plans.

When you add a document: add a row here **and** run `python scripts/ci/check_docs_governance.py`, which
CI stage 6 gates on. If a document's claims stop matching the code, fix or retract them the same day —
`spec.md` v3.8 exists because a whole class of such drift accumulated unnoticed (finding CR14).
