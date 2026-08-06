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

Adapted from the **superpowers** SDD workflow (obra/superpowers, v4.3.1), already proven on prior
projects. Its artefact trail lives in `.superpowers/sdd/`.

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
| A Plan 2 implementation agent was still running when its session was closed. It kept working, finished all eleven assigned fixes, ran its own test suites — and then had no orchestrator left to report to. The work sat committed but unreviewed and unrecorded until the next session went looking for it. | **Lesson: an agent's output is only as durable as the process that collects it.** A replacement agent dispatched into the same repository detected the collision from file timestamps, refused to edit anything, and aborted — which is the behaviour you want, and it happened because the dispatch brief named the hazard. The recovery was to review the orphan's diff on its own merits, since its reasoning was gone and only its diff could be audited. |
| That same orphaned wave wrote a dead-letter test that subscribed to `ledger.events.DLT` while Spring Kafka 4 publishes to `<topic>-dlt` by default. Its own test would have failed on a 60-second timeout. | Caught twice independently — by the orphan itself on a later run, and by a reviewer that decompiled `spring-kafka-4.1.0.jar` to confirm the constant. **Lesson: "the framework's default is X" is a claim about a jar, not a memory.** The destination is now named explicitly rather than inherited. |
| Plan 3: **a reviewer rewrote git history while an implementer held the same tree.** It ran a cherry-pick chain and an amend off a `git status` reading ten minutes stale, overwriting a commit message. It then diagnosed the damage as message-only, and recovered with `cherry-pick --quit` + `reset --soft` — deliberately **not** `--abort`, which hard-resets and would have destroyed the implementer's uncommitted work. | Nothing was lost; verified independently. **The cause was the orchestrator's prompt, not the reviewer's judgement**: the council advisor briefs said "report only", the per-task reviewer briefs said "you may run `git`" — which reads as licensing git *writes*. Two agents then shared one tree with only a Maven-level exclusion between them. **Lesson: a reviewer that cannot write also cannot waste effort undoing or redoing.** It happened a second time for a narrower reason: a constraint added to *new* dispatches does not reach an agent resumed under its *old* prompt. |
| Plan 3: two commit subjects arrived as `@ feat: …`. A PowerShell here-string (`@'…'@`) was used in the Bash tool, which parses it as a literal `@`, a quoted body, and a trailing `@` — git folded the bare `@` line into the subject. | Root cause established by reproduction, not inference; hooks and git config ruled out. Repaired by cherry-pick with `git diff` against a backup proving the trees byte-identical, so only metadata changed. **The orchestrator had this in its own `git log --oneline` output and did not flag it** — a reviewer did. |
| Plan 3: a build failed early, and the **stale `failsafe-reports` XML from the previous green run reported 7/7 passing.** | Counting tests from XML is what AGENTS.md trap 3 requires — but it is only sound **paired with that run's exit code**. On its own it can report a green that never happened. |

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

**Decision: Python 3.11–3.13, click over Typer, pyright over mypy, hatchling, PEP 735 dependency
groups, ruff pinned exactly.** Not because each is uniquely correct, but because a second house style
in the same estate is a maintenance tax with no offsetting benefit. Consistency is a decision, and
this is it.

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

**Decision (Plan 3): complete the error catalogue rather than ship the plan's headline goal half-true.**

The plan's stated aim was "every error comes from one catalogue". The council found that the two most
frequent `full` responses — a 401 on every unauthenticated call, and the 403 on the denied auditor
routes — are written by the security chain *before* `DispatcherServlet`, so `ErrorHandlingAdvice`
never sees them and both carried Spring's default shape. `ErrorCode` was also two rows short of spec
§6.5. The cheap option was to assert status only and record the divergence.

Overruled: the catalogue was completed to all eleven rows and a `SecurityProblemHandler` was
registered on the chain. The decision paid for itself immediately — with that handler unwired, the
status-only test **still passes** while the body test fails. Asserting status alone would have shipped
an uncatalogued 401 green, on the branch whose entire purpose was the opposite.

**Decision (Plan 3): ship a temporary denial rather than an unenforced role.** Spec §7 makes the two
auditor operations `ledger:auditor`-only, but roles need the Keycloak realm. Once Task 3 made `full`
authenticated, *any* valid token had full auditor power over every customer's trail — which also voids
§6.5's "account UUIDs are unguessable" premise, since the trail hands them out. Rather than fake a
role check or leave the hole, `full` now refuses both operations outright. The exposure was confirmed
live before it was closed: both new tests failed `expected:<403> but was:<200>` on an ordinary token.
The stopgap is named in the code, in both profiles' tests, in the OpenAPI descriptions, and in the
follow-up's opening scope — five places, because a denial that outlives its reason becomes a mystery.
**Closed on `phase-4-plan2-roles-keycloak`: `full` now enforces `ledger:auditor`.**

**Decision (Plan 3): absent must not read as unowned.** The authorization decorator originally scanned
the caller's accounts and refused anything absent from the list — making an unknown account a 403 where
§6.5 requires 404, and contradicting the plan's own argument for keeping writes in-service. Corrected
to a single-account lookup: absent returns and lets the delegate answer 404, only a real account with a
different owner is refused. It cost nothing — the port method already existed and `AccountView` already
carried the owner — and it removed an owner-wide scan from every balance and history read.

---

## 7. Phase record — Plan 2 (full persistence)

What the pipeline in §2 actually produced on the second plan, gates and all. Numbers are from the
session ledger, not from recollection.

| Stage | Outcome |
|---|---|
| Plan | 9 tasks, brainstormed then written before any code (§2 stages 1–4) |
| Implementation | 9/9 by subagents, one task brief each, one commit per task after review acceptance |
| Per-task reviews | 9 accepted. **Zero** required a fix loop; 8 minors deferred with written rulings rather than silently dropped |
| Whole-branch review #1 (independent model) | READY WITH FIXES — 0 critical, 3 important. One fix wave, then a scoped re-review closed it clean |
| Whole-branch review #2 (`/code-review`, high effort) | 15 findings, 13 confirmed. 11 fixed, 3 parked with rulings, 1 routed to this docs pass |
| Whole-branch review #3 (re-review of that wave) | 10/11 addressed, 1 partially — the gap became a fourth wave of 4 fixes, each with a red→green proof |
| Complexity gate | Ports with a single implementation audited against ADR 0001 and the ArchUnit fence before being judged earned rather than speculative |
| Verification | `verify` green with **zero** containers started (the integration suite is `-Pit` only); `verify -Pit` green at 24 integration tests, 0 failures, 0 flakes |
| Real-boot proof | Both modes booted for real and curled by hand: `full` against Compose with a live Kafka round-trip into the audit trail (~1s), `standalone` returning 501 on auditor operations behind its AUTH-DISABLED banner |

**The honest part.** Three independent review passes over the same branch each found things the
previous one missed, and the third found a defect *inside the second's own fix*. The lesson is not
that the reviewers were bad — it is that "reviewed" is not a binary, and a single pass over money
code is optimism. The cost was roughly four fix waves; the alternative was shipping a ledger where
the two run modes disagreed about which transactions fall inside a filter.

---

## 7b. Phase record — Plan 3 (security and authorization)

Same pipeline, third plan. Numbers are from the session ledger and from build runs the orchestrator
executed itself with `clean`, not from implementer reports.

| Stage | Outcome |
|---|---|
| Council | Two of four advisors had died on API 529 during Plan 3's review and never saw the **revised** plan. Re-running them was the session's first decision. They returned **13 P0-class findings** |
| Plan revision | Every P0/P1 folded into the task text before a line of code — the plan is the spec, not an appendix. Seven commits on this branch are documentation recording *why* the plan changed |
| Implementation | 8 tasks (0, 1, 2, 3, 4, 5, 6, 6b, 7), one commit per task, explicit pathspecs |
| Per-task reviews | 8 accepted. **4 required a fix loop**, all closed in one round each |
| Whole-branch review | MERGE WITH FIXES — **0 critical**, 4 important. One fix wave closed them |
| Verification | `clean verify` exit 0, **148** tests, **zero** containers; `clean verify -Pit` exit 0, **36** ITs; `missCount = 1` |
| Test growth | 123 → 148 unit, 26 → 36 integration |

**What the council bought, concretely.** Three of its findings would have shipped: Task 0 did not
compile as written, Task 2 patched a function its own proof test never reaches, and the 401/403 would
have sat outside the error catalogue the plan existed to build. The sharpest finding — that the
history decorator had no coverage in any form — was later reproduced exactly: deleting the
`authorizedHistory` bean left the unit suite at **140/0/0 green with a clean Spring context** while an
unprivileged caller was served another customer's entire transaction feed. Both original unit tests
and both original wiring proofs exercised the *balance* decorator only.

**The pattern that dominated this phase: reasoned framework claims were wrong; measured ones were
right.** Twelve times, without exception in either direction.

| Claim, reasoned | What measurement found |
|---|---|
| `ProblemDetail.type` defaults to `about:blank`, so `doesNotExist()` cannot pass | True in Spring 6, **false in Spring 7** — the field has no initialiser, so the node is absent entirely |
| `public-key-location` beats `issuer-uri` | It does not, and it fails **lazily** — the context starts clean and only a real token discovers it |
| Blanking the issuer is enough to disable its validator | It is not — the validator is added on `!= null`, not `hasText` |
| `spring-security-test` secures the `@WebMvcTest` slices | Right mechanism, wrong artifact — the class lives in `spring-boot-security-test`, which is not on the classpath |
| `-q` suppresses the forked test JVM's log body | It suppresses Maven's own `[INFO]` lines only |

Twice the wrong answer was the orchestrator's, correcting a right one. The discipline that emerged:
**where a brief states a framework behaviour, trust the measurement over the claim, and say so.**

**Four evidence methods that proved worthless, and the rule that replaced them.** This phase kept
producing checks that could not fail:

1. Grepping surefire **XML** for container evidence — it embeds the classpath, so testcontainers jar
   names always match and the grep can never come back empty.
2. Grepping surefire **`.txt`** instead — `pom.xml` declares no `maven-surefire-plugin`, so
   `redirectTestOutputToFile` is false and those files hold no log output at all.
3. A glob for `MockMvcSecurity*` returning nothing while `SecurityMockMvcAutoConfiguration` existed —
   Boot 4 renamed it. The control term validated the *search*, not the *pattern*.
4. `-Dtest='A+B+C'` exiting **0** having run nothing — `+` is not a surefire separator, and
   `failIfNoSpecifiedTests=false` turns "nothing matched" into BUILD SUCCESS. **A red→green proof can
   pass its red step having executed no tests**, which is the most dangerous shape of all, because the
   red step is the half nobody re-checks.

`AGENTS.md` trap 7 says to prove a search works by running it against a term you know is present.
That is necessary and **not sufficient**. The rule that actually holds: *a control term must be the
same kind of content as the thing you are hunting, in the same search space* — and the strongest form
is **differential**: run the identical pattern over a case that must score hits and one that must
score zero. Zero containers is now evidenced that way (0 on `verify`, 18 on `-Pit`, control non-zero
in both), which proves both that the pattern matches and that the zero is an absence.

**What the whole-branch review caught that eight per-task reviews could not.** The best of the four
was `/logout`: an inbound route in **both** run modes, authorised by nothing, contributed by the
framework rather than written by anyone. `HttpSecurityConfiguration` applies `logout(withDefaults())`
unconditionally, and because CSRF is disabled, `LogoutConfigurer` adds `GET`/`PUT`/`DELETE` beside
`POST`. `LogoutFilter` precedes `AuthorizationFilter`, so `full` answered an unauthenticated
`GET /logout` with a 302 to a page this API does not serve, instead of the catalogued 401. Task 3
added Spring Security; Task 6b audited the matchers *it wrote*; **nobody audited what the framework
contributed for free.** That is a defect no task-scoped review can see, and the argument for keeping a
whole-branch pass even when every task is green.

---

## 8. How to audit any of this

| Question | Where to look |
|---|---|
| What was actually asked for? | `docs/source/` — the original PDFs, committed unmodified |
| What was decided, and why? | `docs/spec.md` and `docs/adr/` |
| In what order was it built? | `docs/superpowers/plans/*.md`, and the commit history — one commit per task, each landing only after its review was accepted |
| Was each step reviewed? | The per-task ledger, review packages and reviewer reports live under `.superpowers/sdd/<plan>/`, which is **session-local and gitignored** — deliberately, it is working state. What survives in the repo is §7 above, the commit messages, and this document |
| Does it do what it claims? | `./mvnw verify` — unit, architecture, BDD, use-case (starts no containers); `./mvnw verify -Pit` — the 36 integration tests against real Postgres, Redis and Kafka; then `docker compose up` for the `full` stack |
| What was left out on purpose? | `docs/spec.md` §13 non-goals and §15 assumptions |
