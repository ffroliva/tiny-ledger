# AGENTS.md — the rules an agent must know before touching this repository

**This file is the source of truth for working here, and it is deliberately vendor-neutral.** Any agent
must be able to pick this work up with the same visibility and the same level of understanding — Claude,
Codex, Cursor, Aider, Gemini, Hermes, or a human.

Every tool-specific convention file is a **router to this one**, never a second copy: `CLAUDE.md`,
`.cursorrules`, `GEMINI.md`, `.github/copilot-instructions.md`. If you add one, make it a pointer. Two
sources of truth means one of them is wrong and nobody knows which — this repository has already spent a
documentation pass fixing exactly that (spec v3.8, finding CR14).

It is deliberately short: a long file gets skimmed, and a rule that is not read is worth nothing. It
carries only what you must know **unprompted**. The *why* lives in the authorities at the bottom.

## What this project is

An event-sourced banking ledger that runs in **two modes from one codebase** — `standalone` (in-memory,
unauthenticated, JDK-only) and `full` (Postgres + Redis + Kafka). That duality is the point of the
design, not an accident: spec §1. If a change makes the two modes behave differently, it is a defect
unless the spec says otherwise (§9.2b).

## The gates

```bash
./mvnw -q verify          # unit, architecture, BDD — MUST start ZERO containers
./mvnw -q verify -Pit     # the integration suite against real Postgres/Redis/Kafka/Keycloak
```

Both must be green before any commit. `spotless:check` runs inside `verify`.

**Run `-Pit` in CI, not locally.** The integration suite starts real containers and is the slowest
thing in this repository, and only one Maven build may run in a tree at a time — so a local `-Pit` run
blocks all other work for its duration. Push the branch and read the result instead:
`gh run watch` / `gh run view --log-failed`. Run it locally only to debug a failure CI has already
found. Keep running `./mvnw -q verify` locally: it is fast and starts no containers.

- **`verify` starting a container is a bug**, not a slow test. The split is load-bearing: it is what
  lets CI run the unit job on a runner with no Docker (ADR 0003).
- **Never run two Maven builds in the same tree.** They corrupt `jacoco.exec` and produce a failure
  that looks like a test failure. If another agent is working, wait.

## Enforced rules — these fail the build, not a review

`src/test/java/.../architecture/HexagonalRulesTest.java` is the authority. Read it; do not infer it.
Note what it does **not** say: the `application` rule bans three *annotations*, not Spring dependencies.
"The test passes" and "the design is right" are different claims.

- `..domain..` **and `..shared..`** depend on no framework. `shared` is in the rule because ArchUnit checks
  *direct* dependencies: the domain imports `shared`, so a Spring import there would reach the domain's
  compile path while a domain-only rule stayed green.
- `..application..` carries no `@Service` / `@Component` / `@Transactional`.
- Only `..config..` and `..adapter.out..` may touch outbound adapters.
- `@Configuration` lives only in `config` (the composition root) and `platform` (framework guards).
- No package cycles across top-level slices. **`config` imports the business modules**, so anything
  those modules import must not import `config` back.

## Traps this repository has already paid for

Each of these shipped, or nearly shipped, and cost real time. They are here because they are invisible
until they bite.

1. **A green ArchUnit run can mean nothing was checked.** A rule whose `should` sees zero classes used
   to pass. `archunit.properties` now sets `failOnEmptyShould=true`; do not flip it back.
2. **A rename is proven by asserting the old name is gone, never by a green build.** Five package
   references here are *string literals* an IDE rename does not touch: `@AnalyzeClasses`, the
   `slices().matching(...)` cycle rule, two `api.generated..` fence strings, and `CucumberTest`'s glue
   package. Run `git grep -nE '\bOldName\b' -- ':!target'` and require empty output.
   **Measured 2026-08-07:** all five now fail loudly if left stale — the four ArchUnit literals via
   trap 1's setting (9 rules fail "failed to check any classes"), the glue package via Cucumber
   itself (27 scenarios error). The grep is still the right habit; it is no longer the only net.
3. **Count tests from surefire XML, not the `.txt` reports** — and only ever **paired with that run's
   exit code**. `.txt` reports `Tests run: 0` for `@Nested` classes and undercounts. But a build that
   fails early leaves the *previous* run's XML on disk, where it reports passing: a count without its
   exit code can describe a green that never happened.
4. **A test that would pass with its fix reverted is not coverage.** Before adding one, revert the
   production change and watch it fail. Several tests here were written that could not fail. **Check
   the red run actually executed the test it names** — `-Dtest` takes commas, and a pattern that matches
   nothing exits **0**, because `failIfNoSpecifiedTests` defaults to false. A red→green proof can pass
   its red step having run nothing at all.
5. **`@SpringBootTest` caches by merged configuration.** An extra `@Import`, `@TestPropertySource` or
   `@DynamicPropertySource` forks a whole new context — new beans, new Kafka consumers. Extend
   `AbstractIntegrationTest`; forking needs a written reason (ADR 0003, CR13).
6. **`spring.autoconfigure.exclude` replaces, it does not append.** `application-standalone.properties`
   already sets three entries; a second declaration silently drops them.
7. **Do not trust a tool's empty output as evidence of absence.** Some environments wrap shell commands
   through a proxy that silently rejects certain syntax and returns nothing instead of an error — a
   `find` with compound predicates returned "no results" here, and the file it was looking for existed.
   Before concluding something is absent, prove the search itself works: run it against a term you know
   is present.

   **That is necessary and not sufficient, and this repository has now paid for the difference three
   more times.** A control term must be the **same kind of content as the thing you are hunting, in the
   same search space** — otherwise it validates your tooling and licenses a false negative:
   - A glob for `MockMvcSecurity*` came back empty while `SecurityMockMvcAutoConfiguration` existed;
     Boot 4 renamed it. The control proved the *search ran*, not that the *pattern matched*.
   - A container grep over surefire `.txt` "passed" its control because `cucumber` appears in a **class
     name** on the summary line — while those files contain no log output at all, so the hunt could
     never have found anything.
   - Grepping surefire **XML** for container evidence can never come back empty: it embeds the
     classpath, so testcontainers jar names always match.

   **The strongest form is differential**: run the identical pattern over a case that must score hits
   and one that must score zero. Zero containers under `verify` is evidenced that way — 0 there, 18
   under `-Pit` — which proves both that the pattern matches and that the zero is an absence.

## Configuration

`application.properties` is the base and holds **only what is true in both run modes**;
`application-{profile}.properties` overlays it; env vars and `--args` override both. `FailClosedGuard`
**refuses to boot** if full-shaped config appears while `standalone` is active — config crossing the
profile boundary is a startup failure here by design, not a style issue.

## Working agreements

- Commit per logical change, with explicit pathspecs. **Never `git add -A`** — another agent may have
  uncommitted work in the tree.
- **Push freely to `origin`; that is how the heavy suite runs.** The remote is
  `github.com/ffroliva/tiny-ledger` and it is **PUBLIC** — corrected 2026-08-07, having said "private"
  since the first revision. Pushing a working branch is still the normal workflow, but it *is* a
  publication event: every commit message, comment and log excerpt is world-readable the moment it
  lands, and force-pushing does not unpublish what was fetched or indexed. Treat anything you write
  into a commit as public. **Never merge without being asked** — integration is still the user's
  decision.
- Business refusals are **return values** (`MovementResult`), not exceptions. Exceptions are for
  catalogued errors (§6.5) and for bugs.
- Errors are RFC 7807 problem details. `type` is the machine-readable contract; keep it stable.

## Authorities

**[`docs/INDEX.md`](docs/INDEX.md) is the routing table** — it says which document answers which
question, so you read the one authority you need rather than all of them. The essentials:

| Question | Where |
|---|---|
| What is the contract? | `docs/spec.md` — its own header carries the current version |
| Why is delivery shaped this way? | `docs/adr/0001-kafka-delivery-path.md` |
| Why Postgres and not Kafka as the record? | `docs/adr/0002-postgres-event-store.md` |
| Why one test context, and how does CI split? | `docs/adr/0003-test-topology-and-ci-parallelisation.md` |
| What is being built next? | `docs/spec.md` §14 (order) and its *Open issues* section (what is known-open) |
| What was an agent actually told to do? | `docs/_archive/plans/` — delivered execution scripts, listed in the index. **Not contract**; `spec.md` wins on any disagreement |
| How was this built, and where did agents go wrong? | `docs/agentic-workflow.md` |
| Session state, if present | `HANDOFF.md` (root, gitignored, session-local) |

**If you state a rule that this file does not enforce, say which gate enforces it — or say plainly that
none does.** An unenforced rule is not a rule; it is a hope, and the difference should be visible.
