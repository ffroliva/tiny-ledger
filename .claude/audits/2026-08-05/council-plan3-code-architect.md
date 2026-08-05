# Council review — Plan 3 (revised), code-architect lens

Target: `docs/superpowers/plans/2026-08-05-plan-3-security-and-authorization.md`
Tree: `main` @ `0d9123e`. Read-only pass; no build was run.

Scope: the consequences of the revision — moving **all** ownership checks to the port boundary.
Findings already folded in (decoder/issuer, decorator wiring proof, `CallerPrincipal` failing open,
`any()` stubs, filter registration, Task 2 rationale) are not repeated.

---

## P0

### P0-A — Task 6: two `@Primary` beans of the same type cannot coexist; `full` will not start

Task 6's preamble orders `Movements` and `StrongBalance` decorators, but Step 3's code and Step 5's
wiring still produce only `Balances` and `History`. The obvious completion — a `@Bean @Primary
RecordMovementUseCase` in the profile-independent `UseCaseConfig` — collides with
`FullAdapterConfig:145-155`, which already declares `@Primary` `OpenAccountUseCase` and
`@Primary` `RecordMovementUseCase`. Spring's `determinePrimaryCandidate` throws
`NoUniqueBeanDefinitionException: more than one 'primary' bean found among candidates` when
`LedgerController` is injected. `standalone` starts (one `@Primary`), `full` does not — an asymmetry
visible only to Task 3 Step 7's `FullProfile` test, which carries a documented escape hatch to
become an IT.

Dropping `@Primary` from the transactional bean does not rescue it: the authorization bean would then
take a `RecordMovementUseCase` parameter with two non-`@Primary` candidates in `full`
(`recordMovement` → `RecordMovementService`, `transactionalRecordMovement` → the decorator) and no
primary → the same exception. So the answer to "is the resulting order authorise → transaction →
idempotency?" is: **there is no order, because there is no context.**

Also, the premise forcing this shape is wrong. §6.3 says *"Replays are answered only after ownership
of the path account passes"* — the constraint is authorise-before-the-**idempotency lookup**
(`store.findByMovementUid`, step ④ of `RecordMovementService.record`, line 62), not
authorise-before-`BEGIN`. Both nestings satisfy §6.3.

**Concrete alternative — wrap inward, exactly one `@Primary` `RecordMovementUseCase` per profile.**
The authorization decorator must **not** be published as a `RecordMovementUseCase` bean at all; only
as its concrete type. Each profile config then publishes the single `@Primary` for the interface:

```java
// UseCaseConfig (profile-independent) — concrete type only, no @Primary, no interface bean
@Bean AuthorizedUseCases.Movements authorizedMovements(RecordMovementService s, QueryAccountsUseCase a) { … }

// StandaloneAdapterConfig
@Bean @Primary RecordMovementUseCase recordMovementUseCase(AuthorizedUseCases.Movements m) { return m; }

// FullAdapterConfig — replaces the existing transactionalRecordMovement, @Primary stays put
@Bean @Primary RecordMovementUseCase transactionalRecordMovement(AuthorizedUseCases.Movements delegate) {
    return new TransactionalUseCases.Movements(delegate);
}
```

`full` = Transaction(Authorize(Service)), `standalone` = Authorize(Service); §6.3's
authorise-before-idempotency holds in both, because the idempotency lookup is inside the service.
`TransactionalUseCases.Movements` must widen its field type from `RecordMovementService` to
`RecordMovementUseCase`. `OpenAccountUseCase` needs no authorization decorator at all — the caller
*is* the owner on open — so it keeps its current wiring untouched.

Whichever shape is chosen, the plan must **state it explicitly and show the code**; today Task 6
tells the implementer what to do in prose and shows them something else.

### P0-B — Task 4: introduces a package cycle, `HexagonalRulesTest.noCyclicPackages` fails

Step 3 puts `CallerPrincipal` in `platform` importing `com.ffroliva.tinyledger.config.AuthorizationConfig`.
Step 5 then makes `LedgerController` (`ledger.adapter.in.web`) and `BalanceController`
(`balance.adapter.in.web`) import `platform.CallerPrincipal`. `config` imports `ledger` and `balance`
(`UseCaseConfig:3-11`). Slices are `com.ffroliva.tinyledger.(*)..`, so:

```
ledger  → platform → config → ledger      ✗
balance → platform → config → balance     ✗
```

The prior clearance ("nothing outside `platform` imports `platform`") is exactly what Task 4
invalidates — it was true of the tree, not of the plan. This surfaces at Task 4 Step 6, where the
plan predicts exit 0, with no guidance for the implementer who hits it.

**Concrete alternative:** kill the `platform → config` edge. Move the one constant —
`STANDALONE_PRINCIPAL = "local"` — into `shared` (it is a caller identity, not wiring) and delete
`config/AuthorizationConfig.java`. Remaining edges `ledger/balance → platform → shared` are acyclic.
One-line change, no new file.

### P0-C — Task 4: static `standalone` flag defaults `false`; breaks its own test and all three controller slices

```java
private static volatile boolean standalone;      // defaults to false
CallerPrincipal(Environment environment) { standalone = …; }
```

Three consequences the plan asserts away:

1. **Its own test cannot pass.** `CallerPrincipalTest.withNoAuthenticationTheCallerIsTheStandalonePrincipal`
   is a plain JUnit test with no Spring context, so the constructor never runs, `standalone` is
   `false`, and `current()` throws `IllegalStateException`. Step 4's "Expected: PASS, 2 tests" is
   unattainable as written.
2. **`@WebMvcTest` does not register plain `@Component`s.** The three slice classes
   (`LedgerControllerTest`, `BalanceControllerTest`, `AuditControllerTest` — the 38 slice tests the
   plan leans on in Task 3) never build the bean, so the flag stays `false` and every request 500s.
   Step 6's "existing tests keep passing because … `current()` returns `local`" is false.
3. **One JVM, one static.** `SecurityConfigTest` (standalone) and its nested `FullProfile`
   (Task 3 Step 7) write the same field from two contexts. The last context built decides the answer
   for every subsequent test in the fork — order-dependent, and the fail-closed guarantee becomes a
   function of Surefire ordering.

**Concrete alternative:** drop the static. `CallerPrincipal` becomes an ordinary bean with instance
methods `String current()` / `Set<String> roles()`, constructor-injected into the two controllers;
add `@Import(CallerPrincipal.class)` to `LedgerControllerTest` and `BalanceControllerTest` (two
lines, not 38). The fail-closed branch then reads per-instance state and cannot leak across contexts.
`CallerPrincipalTest` constructs it with a `MockEnvironment`, which is also a better test.

### P0-D — Task 6: unknown account silently becomes 403 where §6.5 says 404, on the money path

`requireOwner` runs before the delegate. `QueryAccountsUseCase` exposes only
`accountsOwnedBy(String owner)` — it **cannot distinguish "account does not exist" from "exists,
owned by someone else"**, so both produce `OwnershipException` → 403.

Today:
- `RecordMovementService.record` line 59: empty stream → `AccountNotFoundException` → 404, *before*
  the ownership check.
- `StrongBalanceService:26`: same.
- `BalanceController` / `queryBalance`: `Optional.empty()` → 404.

Spec §6.5 pins `Unknown account | 404 | /errors/account-not-found`, and the 403-not-404 trade-off is
documented **only for wrong-owner** ("Wrong-owner access returns 403, not 404 … recorded here so the
trade-off is a decision, not an accident"). Collapsing unknown→403 is a second, undecided trade-off.

Nothing catches it: the three controller tests are `@WebMvcTest` slices with the port mocked
(`LedgerControllerTest.unknownAccountIsNotFound` stubs the use case and stays green), no Cucumber
feature asserts 404 (grepped: zero `404` occurrences under `src/test/resources/features`), and all
26 ITs are adapter-level.

**Concrete alternative:** add `Optional<AccountView> account(AccountId)` to `QueryAccountsUseCase`
(the projection already backs §4.4's `GET /accounts/{accountUid}`), and have `requireOwner` throw
`AccountNotFoundException` when absent and `OwnershipException` when `view.owner()` mismatches — one
lookup, both statuses preserved. Pin it with a `full`-profile test in Task 3 Step 7's class: alice
`PUT`s a movement to a random UUID → 404, mallory `PUT`s to alice's account → 403.

---

## P1

### P1-A — Task 6: the write guard's authority moves from the event stream to a rebuildable projection

Answering the question directly: **the substitution is sound today, but only by accident, and it is
one config change from being unsound.**

- It is sound today because `LedgerEventsListener` is a plain synchronous `@EventListener` that
  writes the projection inside the write transaction in *both* modes (its javadoc pins this as
  ratified spec v3.8). So read-your-writes holds and §4.4's `asOf` staleness does not bite. The plan
  never says this, so the reader cannot tell whether it was reasoned or assumed.
- It is fragile because that same javadoc says *"Moving the projection off-thread is a Plan 3
  question, not a difference between the modes."* The day the projection goes async, ownership on
  the **money-movement** path silently becomes eventually consistent, and there is no test that
  would fail — a freshly opened account's first deposit would 403 (fail-closed, so a correctness bug
  rather than a hole), and ownership would be decided from a table that can lag or be mid-rebuild.
- It is worse on authority and cost. `RecordMovementService.record` rehydrates the aggregate at line
  60 and has `account.owner()` in hand for free at line 61 — the immutable, append-only system of
  record. The decorator replaces that with `accountsOwnedBy(caller)`, which loads and streams
  **every account the caller owns** from a derived, mutable, rebuildable table, once per movement,
  immediately before the line that already knew the answer.

**Concrete alternative that keeps the user's decision (boundary enforcement, one mechanism):** source
ownership for the write and strong-read decorators from the stream, not the projection — a two-method
`AccountOwnershipPort { Optional<String> ownerOf(AccountId); }` in `ledger.application.port.out`,
implemented over `EventStorePort` in `config`. The boundary stays the enforcement point; the oracle
stays the event log. If that is judged too much machinery, the minimum is an ADR line recording that
`LedgerEventsListener`'s synchrony is now a **security** invariant, not a performance choice.

### P1-B — Tasks 1, 3: the "single catalogue" omits §6.5's 401 and 503, including the one status this plan introduces

`ErrorCode` declares nine constants. §6.5's table also carries `Unauthenticated | 401 |
/errors/unauthenticated` and `Event store unreachable | 503 | /errors/event-store-unavailable`. The
401 is the status **Task 3 adds to the system**, and it is the one the catalogue cannot express:
`full`'s 401 comes from Spring's default `BearerTokenAuthenticationEntryPoint` with a
`WWW-Authenticate` header and no problem body — no `type`, no `traceId`. Task 3 Step 7 asserts only
`status().isUnauthorized()`, so it cannot see this.

`ErrorCodeTest` cannot catch omissions either: both tests iterate `values()`, so they validate the
constants that exist and are silent about the ones that do not.

**Fix:** add `UNAUTHENTICATED(401, …)` and `EVENT_STORE_UNAVAILABLE(503, …)`; add
`.exceptionHandling(e -> e.authenticationEntryPoint(…))` to `fullChain` writing the catalogued
problem body; assert `jsonPath("$.type").value("/errors/unauthenticated")` in
`anUnauthenticatedRequestIsRefused`; and add one test pinning `ErrorCode`'s `type()` set against
§6.5's table as a literal set, which is the only test shape that detects a missing constant.

### P1-C — Tasks 5/6: `accountsOwnedBy` becomes the authorization oracle, and it has no §9.2b contract suite

§9.2b requires a shared contract suite for every outbound port with more than one implementation, and
names `BalanceProjectionPort` explicitly. Only `EventStoreContract` exists
(`src/test/java/com/ffroliva/tinyledger/contract/EventStoreContract.java`). `accountsOwnedBy`
semantics are pinned separately and differently: `BalanceProjectorTest` for `InMemoryBalanceProjection`,
`PostgresBalanceProjectionIT.accountsOwnedByReturnsOnlyMatchingOwner` for Postgres.

Until this revision that asymmetry was cosmetic — a listing endpoint. Now the two implementations
decide **authorization**, and they can diverge on exact-match vs. trim, case sensitivity, null/blank
owner, and empty-result semantics. `standalone` and `full` could authorize differently, which is the
precise failure §9.2b exists to forbid.

**Fix:** add `BalanceProjectionContract` with at minimum
`accountsOwnedByMatchesTheOwnerExactly`, `anUnknownOwnerReturnsEmpty`,
`neverReturnsAnotherOwnersAccount`, `aBlankOwnerReturnsEmpty`; run it from both adapters. This is the
task that makes §9.2b's "the two modes agree" claim cover the security path.

### P1-D — Task 7: the filter registers *after* the security chain, so 401/403 responses carry neither header nor `traceId`

A bare `@Component` `OncePerRequestFilter` is registered by Boot at `LOWEST_PRECEDENCE`, i.e. after
`springSecurityFilterChain` (`SecurityProperties.DEFAULT_FILTER_ORDER` = `HIGHEST_PRECEDENCE + 100`).
In `full`, every response the security chain produces — the 401s this plan introduces — returns
before `FapiInteractionIdFilter` runs: no `x-fapi-interaction-id`, no MDC `traceId`. The responses
most in need of correlation are exactly the ones the filter misses, contradicting its own javadoc
("every response is correlatable either way").

The Task 7 tests cannot see it: they run in a `standalone` `@WebMvcTest` slice where `permitAll()`
means the security chain never short-circuits.

**Fix:** `@Order(Ordered.HIGHEST_PRECEDENCE)` on the filter (or a `FilterRegistrationBean` at
`SecurityProperties.DEFAULT_FILTER_ORDER - 1`), and add
`.andExpect(header().exists("x-fapi-interaction-id"))` to
`FullProfile.anUnauthenticatedRequestIsRefused` — the one assertion that proves the ordering.

### P1-E — Task 2 Step 1: the new test's assertion cannot pass

```java
.andExpect(status().isInternalServerError())
.andExpect(jsonPath("$.type").doesNotExist());
```

`unexpected()` returns `ProblemDetail.forStatus(500)`, whose `type` defaults to `about:blank`;
`ProblemDetailJacksonMixin` is `@JsonInclude(NON_EMPTY)` and a non-null `URI` is not empty, so `type`
is always serialized. Step 2's "run it to make sure it fails" and Step 6's green are both
unreachable — the implementer will get a red test at Step 6 with no diagnosis.

**Fix:** `jsonPath("$.type").value("about:blank")`, which asserts the same thing (it is not
`/errors/invalid-amount`) and is true of the intended end state. Confirm against
`ProblemDetailJacksonMixin` before editing.

---

## Answer to Q5 — is `shared/error/ErrorCode` the right home for an `int` HTTP status?

The catalogue belongs in `shared`. The **status** does not.

`shared` is the open kernel `domain` compiles against, and Task 2 makes that concrete: `Account`
(in `ledger.domain`) throws `InvalidAmountException` from `shared.error`. So every consumer of the
kernel — the Plan 4 CLI, a Kafka consumer, the domain itself — now transitively knows about HTTP
status codes. That is the exact leak `TinyLedgerException`'s javadoc congratulates itself on avoiding
one line earlier ("carries no framework type and no HTTP status of its own"): `e.code().status()` is
a single hop, from the domain, to a transport concept. ArchUnit does not catch it —
`domainIsFrameworkFree` bans `org.springframework..`, not the idea of a status — which is why it is
worth deciding rather than defaulting.

**Alternative that keeps one catalogue and zero transport in the kernel:** leave `ErrorCode` in
`shared` carrying identity only (`type()`, `title()`), and move the status into `platform` as an
exhaustive `switch` **expression** over the enum inside `ErrorHandlingAdvice`:

```java
private static HttpStatus statusOf(ErrorCode code) {
    return switch (code) {                       // no default — the compiler enforces completeness
        case INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
        case FORBIDDEN      -> HttpStatus.FORBIDDEN;
        …
    };
}
```

There is still exactly one catalogue and exactly one translation point, but the thing that stops a
new `ErrorCode` shipping without a status becomes the **compiler** rather than
`ErrorCodeTest.everyCodeHasAStatusATypeAndAMessageKey` — strictly stronger — and the transport
concern lives with the transport. `type()` stays in `shared`: it is the machine-readable identity
clients match on, and it is catalogue-level, not transport-level.

---

## Nits

- **Task 4.** The `CallerPrincipal` listing is missing `org.springframework.stereotype.Component` and
  `org.springframework.core.env.Environment` imports — it does not compile as printed.
- **Task 1.** `ErrorCode.messageKey()` has no consumer: the collapsed handler uses `title()`, and no
  `MessageSource` is wired anywhere in the plan. `ErrorCodeTest` pins the prefix of a dead accessor.
  Delete both, or wire the `MessageSource` — but not "add the API now, use it later".
- **Task 6.** "Interfaces → Produces" still says *"`AuthorizedUseCases.Balances` and
  `AuthorizedUseCases.History`"*, contradicting the task's own P0-1 preamble. Step 3 and Step 5 agree
  with the stale list. An implementer reading code rather than prose ships exactly the half the
  preamble calls wrong.
- **Task 6 Step 8.** `git add … src/test/resources/features` — no feature file is touched by this
  task. Drop the pathspec, or the commit picks up unrelated work in a repo where "never `git add -A`"
  is a stated constraint.
- **Task 3 Step 3.** Putting `spring.security.oauth2.resourceserver.jwt.issuer-uri` in
  `application-full.properties` means it is never present under `standalone`, so `FailClosedGuard`'s
  issuer-uri branch stays permanently unexercised. One test
  (`new MockEnvironment().setActiveProfiles("standalone").setProperty(issuerUri, …)` → expect
  `IllegalStateException`) makes the guard's most important key real.
- **Task 6.** `AuthorizedUseCasesTest` lives in `com.ffroliva.tinyledger.config`, so the
  package-private `Balances`/`History` are reachable — correct, worth stating so nobody "helpfully"
  makes them public.

---

## Well done

- **Task 6's shape is right and it is the spec's own words.** §6.4: *"an authorisation decorator
  applied in the composition root (§4.5), the same pattern as transactions, because `@PreAuthorize`
  on an application service would put a framework annotation exactly where §9.2 forbids one."*
  Mirroring `TransactionalUseCases` is not an analogy the plan invented; it is the specified design.
- **Refusing Spring's `AccessDeniedException`, with the measurement behind it.** That
  `@ExceptionHandler` resolves before `ExceptionTranslationFilter`, turning a correct 403 into an
  opaque 500, is genuinely non-obvious and exactly the kind of thing that ships broken. Recording the
  measurement date in the javadoc is right.
- **Task 2's council correction is verified-correct.** `MovementAmount`'s `@Min(1)` does make
  `Account`'s guard unreachable over HTTP, and `Currency.getInstance("ZZZ")` is what the blanket
  mapping was carrying. Naming the wrong file explicitly ("that was wrong, and following it would
  have turned a green test red") is the right way to fold a correction in.
- **Making the wiring proof a `full`-profile end-to-end test** rather than a unit test on the
  decorator, and demanding the red→green be reported when `@Primary` is removed. That is the only
  test shape that can detect the class of bug P0-A describes.
- **Keeping `TinyLedgerException` free of framework types** and translating at exactly one point.
  The instinct is right even where P1-B and Q5 say the boundary is drawn one class too far in.
