# The agentic workflow behind this repository

**Author:** Flávio Oliva
**Status:** Living document — updated as the pipeline runs

---

## Why this document is part of the deliverable

The coding-stage invitation that prompted this exercise says, verbatim:

> *"Feel free to use AI tools (e.g., Copilot, Claude, Cursor) if they're part of your normal
> workflow. We're mainly interested in how you think and approach problems, so just be ready to talk
> through your solution and decisions during the interview."*

And the interview prep pack says the round grades **domain modelling and code clarity**, plus
*"share your assumptions, and discuss potential shortcomings."*

Taken together: the reasoning is the artefact being assessed, and the tooling that produced it is
fair game. So this repository ships the process alongside the code. Every decision below is
traceable to a file in `docs/` or a commit in the history — nothing here is a claim without an
artefact behind it.

The honest framing: **agents did the typing; the engineering judgement is mine, and the record shows
where I overruled them.** §6 is that record, and it is the most useful part of this document.

---

## 1. The stack

| Layer | What | Role here |
|---|---|---|
| Harness | Claude Code (CLI), Opus | Runs the loop, holds context, executes tools |
| Profiles | `.claude-flavio`, `.claude` | Separate skill sets and settings per working context; this project runs under `.claude-flavio` |
| Skills | Markdown procedures loaded on demand | Domain expertise injected at the moment it is needed, instead of one bloated system prompt |
| Subagents | Task-scoped agents with their own context | Parallel execution and independent review; each starts cold, so briefs must be self-contained |
| MCP connectors | Gmail, Google Drive, Chrome | Read the assignment and its context from source rather than by retyping |
| Hooks | `guard-secrets`, pre-commit | Mechanical gates that do not depend on anyone remembering |

**Why skills rather than a large prompt:** a skill is loaded only when its trigger fires, so the
context window holds the *relevant* expertise at full fidelity instead of everything at low fidelity.
It is the same argument as lazy loading, applied to instructions.

---

## 2. The pipeline

Adapted from the **superpowers** SDD workflow (obra/superpowers, v4.3.1), which is the process I use
on `gflow-cli`. Its artefact trail lives in `.superpowers/sdd/`.

```
  intake  ──►  spec  ──►  plan  ──►  task briefs  ──►  execute  ──►  review  ──►  integrate
    │           │          │             │               │            │             │
  sources    docs/     PLAN.md      task-N-brief.md   commits    review-diff    progress.md
  (email,   spec.md   (dated dir)   (self-contained)   + tests   (per task)     (the ledger)
   PDFs,      +ADRs
   docs)
```

### Stage 1 — Intake

Read the primary sources directly; never work from a paraphrase.

For this project: the assignment and prep PDFs were pulled from Gmail, the prior V2 specification
from Google Drive, and the surrounding process state (dates, stages, who said what) reconstructed
from the mailbox by the `pipeline` skill. Both PDFs are committed under `docs/source/` so a reader
can check the spec against the brief without access to my inbox.

**Rule:** the primary source is committed alongside the work derived from it.

### Stage 2 — Spec

`docs/spec.md` is written and agreed **before any code exists**. It carries the domain model, the
module boundaries, the cross-cutting requirements, the test strategy, the non-goals and the
documented assumptions.

Non-obvious decisions are extracted into ADRs under `docs/adr/`, each with context, decision,
consequences, and the alternatives rejected. An ADR exists so that a future reader can tell a
*decision* from an *accident*.

### Stage 3 — Plan

`docs/superpowers/plans/YYYY-MM-DD-<slug>/PLAN.md`. The spec says what the system is; the plan says
in what order it gets built and what "done" looks like at each step. `spec.md` §14 is the coarse
version of this.

### Stage 4 — Task briefs

Each task becomes a `task-N-brief.md` with a fixed shape:

- **Files** — exactly which paths are created or modified.
- **Interfaces** — what this task *consumes* from earlier tasks and *produces* for later ones,
  including any path other tasks depend on and therefore must not be renamed.
- **Steps** — numbered, each with the exact command to run and the expected output.

Briefs are self-contained because **a subagent starts cold**. A brief that assumes conversational
context is a brief that fails. This constraint is a feature: it forces the ambiguity out of the plan
and into the open *before* execution, which is where it is cheap to fix.

### Stage 5 — Execute

One agent per task, working to its brief. Test-first where the logic is non-trivial. Each task ends
at a commit with the suite green.

### Stage 6 — Review

A **separate** agent reviews the diff for the task's commit range — `review-<base>..<head>.diff` —
with no access to the reasoning that produced it. Findings are classified **Critical / Important /
Minor**; Critical and Important block, Minor may be deferred with a stated reason. Fixes are recorded
in `codereview-fix-report.md`.

The independence matters. An agent reviewing its own output rationalises; an agent handed only a diff
has nothing to rationalise from.

### Stage 7 — Integrate

`progress.md` is the ledger: task → commit range → review verdict. The branch gets a final
whole-branch review with an explicit **Ready to merge: YES/NO**, because a series of individually
clean tasks can still integrate badly.

---

## 3. Skills used, and what each one was for

Recorded as they are actually invoked. The point is not the count — it is that each one replaced a
judgement I would otherwise have made from memory, under time pressure, at 22:00.

| Skill | Stage | What it did |
|---|---|---|
| `pipeline` | Intake | Reconstructed application state from the mailbox — stages, dates, who owes whom a reply. Surfaced that a role recorded as "stalled" had actually been rejected four weeks earlier. |
| `ponytail` | Spec | Enforced minimalism: challenged every component against "does this need to exist". Produced the first spec, then was deliberately overruled — see §6. |
| `hexagonal-architecture` | Spec | Ports & adapters: domain boundaries, dependency inversion, testable use-case orchestration. Hardens `spec.md` §3–§4. |
| `springboot-patterns` | Spec | Spring Boot conventions for the application and adapter layers. |
| `api-design` | Spec | REST contract: resource naming, status codes, pagination, error responses, versioning, rate limiting. Feeds `docs/api/openapi.yaml`. |
| `llm-council` | Spec | Five independent advisors pressure-test the contested decisions, peer-review each other anonymously, then synthesise. Aimed at ADR-002 (event store), the §13 non-goals, and the dual-mode delivery. |
| `guard-secrets` | Continuous | Pre-commit gate. Authoritative — it has blocked five commits in one session and been right every time. |
| superpowers SDD | Plan → Integrate | The task-brief / execute / independent-review / ledger loop described in §2. |

### Skills are vendored into this repository

Every skill above is committed under `.claude/skills/`, with provenance and licences in
`.claude/skills/VENDOR.md`:

| Source | Version | Licence | Provides |
|---|---|---|---|
| [obra/superpowers](https://github.com/obra/superpowers) | 6.2.0 | MIT | 14 skills — `brainstorming`, `writing-plans`, `executing-plans`, `subagent-driven-development`, `dispatching-parallel-agents`, `requesting-code-review`, `test-driven-development`, `verification-before-completion`, and others. The SDD pipeline in §2 is these skills in sequence. |
| [jdubois/dr-jskill](https://github.com/jdubois/dr-jskill) | main @ 2026-07-30 | Apache-2.0 | Spring Boot project conventions and pinned versions (`versions.json`) — see `spec.md` §1.5 |
| [ffroliva/iso-compliance](https://github.com/ffroliva/iso-compliance) | 1.0.0 | MIT | ISO 15289 / 27001 A.8 / 25010 assessment and governance enforcement (§10) |

**Vendored, not referenced by URL — deliberately.** An agent skill that changes underneath a
compliance run invalidates the evidence trail: a reviewer six months from now must be able to see
the exact instructions that produced the artefacts, not whatever `main` says today. This is the same
argument as a committed lockfile, applied to instructions. `VENDOR.md` records the upgrade procedure.

---

## 4. Guardrails

Process discipline that does not rely on anyone being careful:

| Guardrail | Enforced by | Catches |
|---|---|---|
| Architecture cannot drift | `ApplicationModules.verify()` + ArchUnit in the build | A domain class importing Spring, JPA, Kafka or Redis |
| API cannot drift | Controllers implement interfaces generated from `openapi.yaml` | An endpoint that no longer matches its contract |
| CLI cannot drift | Pydantic models generated from the same `openapi.yaml` | A client that disagrees with the server |
| Specification is executable | Gherkin features run by Cucumber (JVM) and pytest-bdd (Python) | A requirement everyone agreed to and nobody implemented |
| Performance cannot regress | Gatling assertions as pipeline gates | A p99 that quietly doubles |
| Secrets never land | `guard-secrets` + `gitleaks` + `detect-secrets` | The obvious catastrophe |

Every one of these fails the **build**, not a checklist. The through-line: an agent writing code at
speed needs mechanical boundaries, not good intentions — the same reason the domain layer has no
framework imports.

---

## 5. Where the agents were wrong

Kept deliberately, because a process document that only records successes is marketing.

| What happened | Consequence |
|---|---|
| A prior session recorded a recruiter conversation as arriving "by email" when it was a phone call. Repeated across four files before anyone checked the mailbox. | Corrected. **Lesson: an agent's confident record of a source is not the source.** Verified against Gmail; the compensation figure is now explicitly marked as recollection, not a quoted document. |
| The same session reported a GitHub application as "stalled 32 days". It had been rejected on day six, and a third application to the same company was missing from the tracker entirely. | Corrected. **Lesson: absence of a signal was read as absence of an event.** The rejection was sitting in the inbox the whole time. |
| The minimalism skill argued hard against building this platform at all. | Overruled deliberately — §6. It was right about the brief and wrong about the goal. |

---

## 6. Decision log — human over agent

The section that matters most in an interview, because it is where judgement is visible.

**Decision: build the full platform, not the minimal solution.**

The `ponytail` skill read the brief — *"no more than a few hours"*, *"keep it simple"*, in-memory,
explicitly no auth, no monitoring, no atomicity — and produced a spec for four endpoints, four
domain types and six tests. Against the brief as written, that spec is correct, and I want it on
record that the argument was made and made well.

I overrode it. The reasoning:

1. The brief is a floor, not a ceiling. It grades *approach*, and approach is more visible across a
   system than across four endpoints.
2. The follow-up round is a conversation about the submission. A larger surface produces more to
   talk about — provided the trade-offs are deliberate and stated, which is what `spec.md` is for.
3. The role in question is a backend engineering role at a payments company. Optimistic concurrency,
   idempotency, outbox delivery and cache invalidation are the actual daily work, and demonstrating
   them beats asserting them.

**The hedge that makes it safe:** the repository runs in two modes from one codebase.
`./mvnw spring-boot:run` is in-memory, unauthenticated and dependency-free — the brief, satisfied
exactly. `docker compose up` is the full stack. Same domain code, different adapters. A reviewer who
wants the tiny ledger gets the tiny ledger in one command; a reviewer who wants the depth has it.
That the hedge is *possible at all* is the argument for the hexagonal boundaries in `spec.md` §4 —
the architecture is what buys the option.

**Decision: Postgres as the event store, Kafka as the bus** (ADR-002). My own V2 spec had Kafka as
the log itself. I changed it against my earlier position because Kafka offers no conditional append
on a stream, and therefore cannot enforce the one invariant a ledger exists to protect. Recorded here
because changing your mind on your own prior design is worth more than defending it.

**Decision: Python 3.11–3.13, and gflow-cli's conventions verbatim.** click over Typer, pyright over
mypy, hatchling, PEP 735 dependency groups, ruff pinned exactly. Not because each is uniquely
correct, but because a second house style in the same estate is a maintenance tax with no offsetting
benefit. Consistency is a decision, and this is it.

**Decision: hold the scope; move the deadline.** The submission window is self-imposed — nothing
fixes it except the interview slot chosen after it. When the spec's scope collided with a two-day
window, the instruction was explicit: the scope stands, the schedule bends. Recorded because the
reflex under deadline pressure is to cut quietly, and a cut here would remove exactly the surface
this submission exists to demonstrate. The brief-compliant path is already protected by the dual
run mode; reducing scope would have hedged a hedge.

**Decision: Starling Bank's public API is the reference model for the API surface.** Convention
questions — resource naming, money representation, identifier style, error shape, idempotency
mechanics — are settled by precedent rather than taste: adopt Starling's answer where it fits a
ledger, adapt it where it doesn't, and record the divergence. A public banking API that survives
third-party integration is a stronger authority than in-house preference, and it turns every API
review argument into a citation. The adoption level is decided per convention in the `api-design`
pass over spec §6–§7, not wholesale.

---

## 7. How to audit any of this

| Question | Where to look |
|---|---|
| What was actually asked for? | `docs/source/` — the original PDFs, committed unmodified |
| What was decided, and why? | `docs/spec.md` and `docs/adr/` |
| In what order was it built? | `docs/superpowers/plans/*/PLAN.md` and `.superpowers/sdd/progress.md` |
| Was each step reviewed? | `.superpowers/sdd/review-*.diff` and `task-N-report.md` |
| Does it do what it claims? | `mvn verify` — unit, architecture, BDD, integration, use-case; then `docker compose up` and `ledger-cli scenario run edge-cases` |
| What was left out on purpose? | `docs/spec.md` §13 non-goals and §15 assumptions |
