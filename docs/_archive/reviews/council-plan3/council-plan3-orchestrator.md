# Council findings — orchestrator pass · Plan 3 · 2026-08-05

**Status of the council:** all four independent advisors (security-auditor, staff-reviewer,
code-architect, silent-failure-hunter) terminated on API 529 Overloaded before reporting. This file is
the **orchestrator's own pass**, not a substitute for them — a single reviewer applying four lenses has
correlated blind spots, which is the entire reason the 4-agent pattern exists. The advisors are to be
resumed when capacity returns and their findings merged here.

Every finding below is **verified against the code**, not reasoned from the plan text.

---

## P0 — must fix before executing

### P0-1 · Task 6 entrenches TWO authorization mechanisms for one rule

Verified: the write and strong-read paths **already authorize inside the service**.

```
ledger/application/usecase/RecordMovementService.java:61
    if (!account.owner().equals(caller)) throw new OwnershipException(caller, accountId); // ③
ledger/application/usecase/StrongBalanceService.java:28
    if (!account.owner().equals(caller)) throw new OwnershipException(caller, accountId);
```

Task 6 adds an authorization **decorator** for `QueryBalanceUseCase` and `QueryHistoryUseCase`. The
result after this plan: `deposit`, `withdraw` and the strong read authorize *in-service*, while
`balance` and `history` authorize *in a decorator outside the service*. One rule (§6.4 ownership), two
places, permanently.

The Plan 3 research named this exact hazard as open decision #3 — "the code already checks ownership,
in the right order, inside three services; the spec says a decorator does it; landing both, or leaving
spec-vs-code drift, repeats exactly the CR14 staleness the Plan 2 close-out just spent a docs pass
fixing." The plan does not resolve that decision. It implements one side and leaves the other standing,
which is the worst of the three available outcomes.

**Fix, one of:** (a) move the in-service checks into decorators too, so all five use cases authorize at
the port boundary and §6.4 has one mechanism; or (b) put the read authorization *in the read services*
alongside the existing three and drop the decorator, then correct §6.4 to describe what the code does.
Either is defensible; shipping both is not. This is a decision for the user, not for the implementer,
and Task 6 must not be executed until it is made.

### P0-2 · Task 3's `full` chain is never tested and would not boot

`SecurityConfig.fullChain` calls `.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))` with no
decoder and no `issuer-uri`. Nothing in `application-full.properties` sets
`spring.security.oauth2.resourceserver.jwt.issuer-uri` — and Spring cannot build a `JwtDecoder` from
nothing, so a real `full` boot fails at context startup.

`SecurityConfigTest` only activates `standalone`, so the suite cannot see this. And separately verified:
**no integration test makes an HTTP call** (all 26 are adapter-level), so the IT suite cannot see it
either. The plan would therefore go green while `full` is unbootable — precisely the class of defect the
silent-failure lens exists to catch.

Compounding it, `platform/FailClosedGuard.java` already names that exact property:

```java
String[] fullShaped = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri", ...
};
// standalone active + any full-shaped property present => refuse to start
```

So the codebase anticipated this work and the plan neither references the guard nor satisfies it.

**Fix:** Task 3 must set the `full` issuer/decoder configuration and add a `full`-profile test that
proves the chain boots and rejects an unauthenticated request. The existing `TestJwt.decoder()` is the
hermetic way to do it without Keycloak. `FailClosedGuard` should be cited in `SecurityConfig`'s javadoc
so the relationship is discoverable.

---

## P1 — fix during execution

### P1-1 · Task 4's `CallerPrincipal` fails **open**, in a codebase that fails closed by design

`current()` returns `AuthorizationConfig.STANDALONE_PRINCIPAL` whenever the authentication is not a
`JwtAuthenticationToken`. In `standalone` that is the documented contract. In `full` it is a silent
downgrade: any request that reaches a controller without a JWT is treated as the caller `"local"`, and
the ownership check then compares against accounts owned by `"local"`. It is not exploitable while the
`full` chain requires authentication — but it is a fallback that turns a security misconfiguration into
a *wrong answer* instead of a refusal, in a codebase that has a dedicated `FailClosedGuard` asserting
the opposite principle.

**Fix:** make the fallback profile-aware — return the fixed principal only when `standalone` is active,
and throw otherwise. Add a test that an unauthenticated call under `full` refuses rather than resolving
to `"local"`.

### P1-2 · `requireOwner` cannot distinguish "not yours" from "does not exist yet"

`accountsOwnedBy(caller)` reads the **eventually consistent** projection (§4.4 carries explicit `as_of`
staleness markers). Immediately after `openAccount`, the projection may not yet list the account, so the
owner's own first read can be refused with 403 rather than answered. Under the `full` profile the
projection is fed synchronously inside the append transaction, so this is narrow — but it is real for
any path where it is not, and it is untested either way.

**Fix:** add a test that the owner can read the balance of an account they just opened, in both run
modes (§9.2b), so the window is either proven absent or made visible.

---

## Nits

- Task 3 Step 2 predicts "exactly 21 failures". Tasks 1 and 2 add only unit tests, so the Cucumber count
  is genuinely unchanged and the prediction holds — but the step should say *Cucumber* failures
  explicitly, since the total test count will have moved by then.
- Task 1 Step 6 says "apply the same shape" across five exception files. It names the exact `ErrorCode`
  for each, so it is actionable, but a subagent reading tasks out of order would prefer each file spelled
  out.

---

## Well-done — verified sound, recorded so the synthesis is not all negative

- **No package cycle.** `platform/CallerPrincipal` importing `config.AuthorizationConfig` creates
  `platform → config`, and I verified nothing outside `platform` imports `platform`, while `config`
  imports only `audit`, `balance`, `ledger`, `notification`. So `noCyclicPackages` holds, directly and
  indirectly. This was my own leading P0 candidate and it is a non-issue.
- **Task 1 is genuinely behaviour-preserving.** `OwnershipException` currently `extends RuntimeException`
  and the advice maps it to 403; re-parenting it to `TinyLedgerException` with `ErrorCode.FORBIDDEN`
  preserves that, and the existing `wrongOwnerIsForbidden` / `unknownAccountIsNotFound` /
  `concurrencyConflictIsVersionConflict` tests are a real safety net rather than a nominal one.
- **Task 2's sequencing is right.** `Account` does use `IllegalArgumentException` as the amount-validation
  signal, so introducing `InvalidAmountException` before removing the blanket mapping is necessary, not
  ceremony.

---

## Silent-failure lens (orchestrator, second pass — all four advisors still down on 529)

### P0-3 · Task 6's only proof that the decorator is *wired* is the test the plan authorises dropping

`AuthorizedUseCasesTest` constructs `AuthorizedUseCases.Balances` directly and asserts it refuses a
stranger. It is a pure unit test on the class. **If the `@Primary` wiring in `UseCaseConfig` is wrong —
annotation forgotten, wrong bean injected, `standalone` resolving the undecorated
`BalanceQueryService` — the controller performs no authorization at all and that test still passes.**
Nothing in the plan proves the decorator sits in the request path.

The single step that would have caught it is Task 6 Step 7's Cucumber scenario — and it carries an
escape hatch: "If `standalone` cannot express two different callers, convert this to a
`BalanceControllerTest` case instead." Verified: `standalone` has exactly **one** fixed principal
(`AuthorizationConfig.STANDALONE_PRINCIPAL = "local"`), so that hatch will certainly trigger. A
`BalanceControllerTest` slice stubs the use-case port, so it exercises the mock rather than the wiring.
The wiring proof therefore evaporates by design.

**Fix:** Task 6 needs a test that goes through the real object graph with two distinct callers — a
`@SpringBootTest` under `full` with two `TestJwt` tokens is the honest shape, which also gives Task 3's
`full` chain the coverage P0-2 says it lacks. One test closes both gaps.

### P1-3 · Task 5 never proves the caller actually arrives

The services ignore their new `caller` parameter (by design — the decorator decides), `standalone`'s
principal is always `"local"`, and the controller tests stub the ports. So an implementer who passed a
literal `"local"`, an empty string, or `null` instead of `CallerPrincipal.current()` would see every test
pass. Task 6's decorator then authorises against whatever that wrong value is.

**Fix:** assert in a controller test that the value reaching the port is the authenticated subject, using
a Mockito captor rather than an `any()` matcher.

### P1-4 · Task 7 proves the filter's logic but not its registration or its purpose

Both tests call `filter.doFilter(...)` directly, so removing `@Component` — and with it the filter from
the chain entirely — breaks neither. And the filter's stated reason for existing is to populate the MDC
`traceId` that `ErrorHandlingAdvice.traced()` attaches to problem responses; **no test asserts a
`traceId` ever appears in a response body.** The feature could be wholly inert and green.

**Fix:** one MockMvc test through the real chain asserting the `x-fapi-interaction-id` response header,
and one asserting `traceId` is present in a 4xx problem body.

### Correction to my own P0-2 reasoning, and a new question it raises

I checked whether Task 2 could regress silently and it cannot: **seven** unit assertions plus **two**
Cucumber scenarios assert `400 /errors/invalid-amount`. The safety net is real.

But reading them exposes something the plan gets wrong. Several of those assertions —
`BalanceControllerTest:154,163` and `AuditControllerTest:170` — are **`limit` validation**, not amounts;
they arrive as `ConstraintViolationException` / `HandlerMethodValidationException`, which Task 2 leaves
in `malformed()` untouched. So they are unaffected either way. The question the plan never asks is
whether the *generated request DTO's* bean validation shadows `Account`'s amount guard entirely — if a
negative `minorUnits` is rejected by `@Min` before any use case runs, then `Account`'s
`IllegalArgumentException` is **unreachable from HTTP**, and Task 2's stated justification ("removing the
blanket mapping would turn every malformed amount into a 500") is false. The change would still be
correct for non-HTTP callers such as Plan 4's CLI, but the reasoning in the task — and its ordering
argument — would need rewriting.

**Action:** before executing Task 2, establish which layer actually rejects a negative `minorUnits` over
HTTP. Read `docs/api/openapi.yaml`'s `Amount`/`minorUnits` schema and the generated model.

---

## False positives, kept for auditability

- **"Task 3 will break the 26 integration tests with 401s."** It will not. Verified: no IT makes an HTTP
  call — every one is adapter-level (Kafka, Postgres, Redis, Liquibase). `anyRequest().authenticated()`
  in `full` therefore has nothing to reject in the IT suite. Worth recording because the same reasoning
  *would* have been correct in most Spring codebases, and because it is the flip side of P0-2: the
  reason the ITs cannot break is the same reason they cannot protect.
