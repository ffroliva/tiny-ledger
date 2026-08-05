# Council round 3 — Plan 3 · the two advisors that never ran

**Status:** `staff-reviewer` and `silent-failure-hunter` died on API 529 during round 2 and were never run
against the **revised** plan (new Task 0, reversed Task 6, new Task 6b, rewritten Task 2). Both ran to
completion on 2026-08-05 against the revised plan, read-only, forbidden from running Maven. This file is
their merged output plus the orchestrator's adjudication and the user's three scope decisions.

Between them: **13 P0-class findings**, of which the orchestrator independently code-verified four.
Round 2's orchestrator pass had found six. The plan was not ready to execute.

---

## User decisions (2026-08-05, taken before execution)

1. **Complete the error catalogue inside Plan 3.** Add `UNAUTHENTICATED` (401) and
   `EVENT_STORE_UNAVAILABLE` (503); register an `AuthenticationEntryPoint` and an `AccessDeniedHandler`
   on `fullChain` that write the catalogued `ProblemDetail`; add a completeness test pinning
   `ErrorCode`'s type set against spec §6.5. **Not** chosen: de-duplicating the three hand-built
   `/errors/` literals — recorded as a follow-up so Task 1 stays a behaviour-preserving refactor.
2. **`full`-profile security tests are ITs.** `SecurityConfigIT extends AbstractIntegrationTest` from
   the start, decoder supplied through the existing `@DynamicPropertySource`, never `@Import`.
   Accepted consequence: `./mvnw -q verify` covers no security; only the integration job does.
3. **Absent ≠ unowned.** Add a port predicate so an unknown account reads 404 and a wrong-owner
   account reads 403, per §6.5. Also removes a full owner-scan from every balance/history read.

---

## P0 — merged, deduplicated

| # | Finding | Lens | Verified by orchestrator |
|---|---|---|---|
| 1 | **Task 0 cannot compile.** `EventStoreContract:17-18` declares `private static final` fields — illegal in an interface. `:15` `protected abstract EventStorePort store()` becomes implicitly public, so `PostgresEventStoreIT:31` and `InMemoryEventStoreTest:10` (both `protected`) fail with "weaker access privileges". `:31,39,47,59` need `@Test default`. **`InMemoryEventStoreTest` also extends the contract and appears in neither Task 0's file list nor its commit pathspec.** | staff P0-B, hunter Nit | — |
| 2 | **Task 2 patches the wrong call site.** `unknownCurrencyCodeIsBadRequest` goes through `LedgerApiMapper:34`'s bare `Currency.getInstance`, not `Money.of` (which serves only the movement path, `LedgerApiMapper:42`). Step 5's removal of the blanket mapping turns the named proof test into a 500 — the exact outcome Step 6 forbids. Then, once fixed, `Money.of`'s new guard is covered by nothing: reverting it stays green while the money path regresses to an opaque 500. | staff P0-E, hunter P0-1 | **yes** — `git grep Currency.getInstance` |
| 3 | **401 and chain-level 403 carry no catalogued body.** `denyAll()` is enforced in `AuthorizationFilter`, before `DispatcherServlet`; `ErrorHandlingAdvice` is a `@RestControllerAdvice` and never runs. Task 6b's `/errors/forbidden` assertion cannot pass — under MockMvc the body is empty, in a container it is `BasicErrorController`'s shape, which echoes `path` (§6.5 forbids internal identifiers). Same hole for Task 3's 401. `ErrorCode` omits §6.5's `unauthenticated` and `event-store-unavailable` rows, and `ErrorCodeTest` (range/prefix/uniqueness) structurally cannot detect a *missing* constant. | staff P0-F+P0-G, hunter P0-4+P0-5 | partial — `openapi.yaml:430-453` |
| 4 | **Task 3's `spring-security-test` flips all 38 `@WebMvcTest` slices to secured.** The experiment measured with `spring-boot-starter-security` only; Boot applies the chain to MockMvc via `MockMvcSecurityConfiguration`, `@ConditionalOnClass(SecurityMockMvcConfigurers)` — a class that ships in `spring-security-test`. `SecurityConfig` is not a slice include type, so Boot's default `authenticated()` chain applies. Step 2's "exactly 21 failures" tripwire fires with no guidance at the point it fires. | staff P0-A | no — needs a build |
| 5 | **Task 4 specifies `CallerPrincipal` three incompatible ways.** Prose says instance bean (`:739`); the Step 1 test calls it statically (`:780,786`); Step 3 declares `current()` instance and `roles()` static (`:824,838`); Step 5 says static again (`:854`). Step 4 predicts PASS on a test that will not compile. The Step 3 code block also imports `config.AuthorizationConfig` — **the exact import the task exists to remove** — reintroducing `config → balance → platform → config`, and references `StandalonePrincipal.NAME`, `@Component` and `Environment` with no imports. | staff P0-C | — |
| 6 | **Task 4's constructor injection breaks all three controller slices.** The plan states the premise at `:739` (a `platform` `@Component` is not in a web slice) and skips the conclusion: constructor injection turns that into `NoSuchBeanDefinitionException` at context start. `LedgerControllerTest:42`, `BalanceControllerTest:43`, `AuditControllerTest:30` — none listed in Task 4's files or pathspec. | staff P0-D | — |
| 7 | **Task 6 turns an unknown account into 403 on the read path.** `requireOwner` refuses any id not in `accountsOwnedBy(caller)`, including ids that do not exist. `spec.md:720` requires 404; `:727` scopes 403 to wrong-owner. Plan `:982` uses this identical objection to reject option (a) for writes — the argument condemns the read design it justifies. `BalanceControllerTest:101` asserts the 404 but is a slice with a mocked port, so the regression ships green. | staff P0-H | partial |
| 8 | **`AuthorizedUseCases.History` has zero coverage in any form.** Both unit tests instantiate `Balances`; both wired proofs hit `/balance`. Omitting the `authorizedHistory` bean leaves exactly one candidate, so the context starts clean and mallory pages alice's full transaction history — the same data class Task 6b exists to protect, via a route Task 6b does not deny. (Forgetting `@Primary` on `authorizedBalance` fails loudly; forgetting `History` entirely fails silently.) | hunter P0-3 | — |

### Adjudicated disagreement — Task 0 Step 2

The two lenses split on whether `-Dspring.test.context.cache.maxSize=1` is falsifiable. **Hunter is
right:** the flag evicts LRU, it does not fail, and the only observable is the exit code, which is 0 in
both worlds; containers start once per JVM, so a surviving fork costs seconds, not a failure. Staff
called it "good instrumentation" but conceded the same mechanism. The user's standing rule settles it —
*prove a gate by violating it and watching the build fail, never by observing green.* **Replaced** with
the AGENTS.md trap-2 shape: `git grep -nE '@SpringBootTest|@DynamicPropertySource' -- 'src/test/java/**/*IT.java'`
must return only `AbstractIntegrationTest`, plus an assertion on the context-cache `missCount`.

---

## P1 — merged

- **Task 3 Step 7 forks a third `full` context and re-arms CR13.** `@Nested` + `@ActiveProfiles` default
  to `inheritProfiles = true`, so the nested class activates **standalone + full**; `FailClosedGuard`
  then refuses to start because `application-full.properties:5` sets `spring.datasource.url`. The plan's
  pre-authorised escape ("move it to an IT") misdiagnoses that as "needs containers", and the IT form as
  written uses `@Import`, which ADR 0003:40-43 explicitly forbids as a context fork — re-creating what
  Task 0 just removed, minus the `auto-startup=false` Task 0 deleted. *Orchestrator verified:
  `FailClosedGuard.java:11-24` + `application-full.properties:5`.* Settled by user decision 2.
- **Task 5's captor test asserts the very literal it exists to rule out.** `isEqualTo("local")` passes if
  the controller hardcodes `"local"`. Use a sentinel via a mocked `CallerPrincipal`, or assert in a
  context where the correct value is `alice`.
- **Task 4's fail-closed branch has no test.** Nothing covers *not standalone, no authentication*.
  Deleting the guard keeps the suite green, restoring fail-open. Compounded by finding 5: the lazy
  static/instance reconciliation deletes the branch outright.
- **Task 6b's "confirm standalone still 501" has no check anywhere.** No feature file mentions `audit`,
  `/events`, `501` or `not-available`; `AuditControllerTest` is a slice with no filter chain. Applying
  the matchers to the wrong chain leaves every test green while `standalone` answers 403 instead of the
  contractual 501.
- **`ErrorCode` is not the single authority it claims.** Hand-built `/errors/` literals shadow it at
  `AuditController:155`, `LedgerApiMapper:76`, `BalanceController:179`; `RATE_LIMIT_EXCEEDED` has no
  producer at all. *Orchestrator verified — and note the grep for `"/errors/` returned **empty** while
  the literals plainly exist: a live instance of AGENTS.md trap 7, where the control term passed and the
  pattern syntax was silently swallowed.* Deferred by user decision 1.
- **Task 7's two assertions are independently satisfiable.** Header and body id are never compared, so
  two different UUIDs pass both. Assert `response header == $.traceId`, with a *supplied* id.
- **`FapiInteractionIdFilter` runs after the security chain.** A `@Component Filter` registers at
  `LOWEST_PRECEDENCE`; `springSecurityFilterChain` at `-100`. Every 401 and every `denyAll()` 403 is
  written before the filter runs — no header, no MDC `traceId` — so Task 7's stated purpose is false for
  exactly the responses FAPI requires the header on. Needs `@Order(HIGHEST_PRECEDENCE)`.
- **`requireOwner` is O(accounts owned) per read.** Same edit as user decision 3.
- **Task 2 Step 1's new test asserts something `ProblemDetail` cannot satisfy.**
  `jsonPath("$.type").doesNotExist()` — `type` defaults to `about:blank` and is never null. The repo's
  own 500 test (`LedgerControllerTest:231-239`) asserts `$.detail`/`$.status` and conspicuously not
  `$.type`.
- **Task 5's file list misses two call sites.** `BalanceControllerTest:74` uses an *exact-argument* stub,
  not `any()`; `LedgerEventsListenerTest:42` is absent from the file list. Both are compile errors met
  right after "no assertion should need altering".
- **Task 0's "keep `auto-startup=false` if still needed" is not an available branch** — its only home
  would be `AbstractIntegrationTest`, which would stop the listener `KafkaAuditModuleIT` awaits. Say
  *delete it*.
- **Expected test counts do not survive the tasks preceding them** (see ledger; baseline is 123, not 122).
- **`ci.yml` replacement drops the stage names and the resolve-drift placeholder** — the only in-repo
  pointer to the stage model and to Plan 4 / spec §12.1.
- **Plan's File Structure header is stale in six places** after the revision.

---

## Verified sound — recorded so the synthesis is not all negative

- **Task 6's reversal is correct on every count.** `RecordMovementService:58-62` is exactly ①②③④ with
  ownership checked against the rehydrated aggregate at `:61` and `findByMovementUid` at `:62`;
  `StrongBalanceService:25-28` matches; `FullAdapterConfig:146,152` do carry `@Primary`, so boundary
  write decorators would have thrown `NoUniqueBeanDefinitionException`; `spec.md:649` constrains
  authorisation relative to the idempotency replay, not to `BEGIN`. The hybrid is justified.
- **Task 6b's route matchers are complete.** `openapi.yaml:296` and `:323` are the only auditor paths;
  the two matchers cover both and over-match neither `/transactions` nor `/balance`. Trailing-slash,
  case and encoded-slash bypasses all fail against Spring MVC 6+ and Tomcat defaults. The denial is
  right; only its wire shape is wrong.
- **Task 1's safety net is real.** `LedgerControllerTest:96,104,111,118,127` assert exact `type`
  **values**, not merely statuses, so a wrong `ErrorCode` on any collapsed handler turns red.
- **Task 2's "`Account`'s guard is unreachable over HTTP" holds.** `openapi.yaml:564-581` gives
  `minorUnits` `minimum: 1`; `LedgerControllerTest:131-141` proves rejection precedes the use case.
- **Task 6 Step 7 genuinely catches a missing `@Primary` on `authorizedBalance`**, and the subtler
  mutation of injecting the concrete service into the controller.
- **Task 3 Step 7's pairing is the right shape** — refusal plus acceptance; a chain that refuses
  everyone fails the second.
- **Task 7's registration proof works at slice level** — `WebMvcTypeExcludeFilter` includes
  `jakarta.servlet.Filter` beans.
- **Modulith and JaCoCo will not object** to `shared.error` (`package-info` declares `Type.OPEN`) or to
  uncovered new classes (`pom.xml:110` scopes the check to `*.domain*`).
- **Read-your-writes is identical in both run modes** — `LedgerEventsListener` is a synchronous
  `@EventListener` in `full` too, so round 2's P1-2 projection-lag window is genuinely absent. Worth
  stating in the plan rather than leaving dropped.

## False positives, kept for auditability

- "`@Profile("standalone")` will not match, so Cucumber stays 401" — `application.properties:1` sets
  `spring.profiles.default=standalone` and `AbstractEnvironment` falls back to defaults.
- "Task 3's `issuer-uri` breaks the 26 ITs at startup" — Boot defers resolution via `SupplierJwtDecoder`,
  and no IT makes an HTTP call. The same fact is why the ITs cannot protect the `full` chain.
- "Task 1 changes `DuplicateMovementException` from 500 to 409" — caught at `RecordMovementService:67`,
  never crosses the boundary.
- "`shared.error` breaks Modulith boundaries" / "uncovered new code trips JaCoCo" — both disproved above.
- "`denyAll()` on `/api/v1/accounts/*/events` also blocks `/transactions`" — the last segment is literal.
- "`unexpectedFailureLeaksNothing` breaks under Task 2" — it never asserts `$.type`.
