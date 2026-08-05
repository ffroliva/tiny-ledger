# Plan 3 — Authenticated, Authorized API on a Single Error Catalogue

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `full` an authenticated, role-and-ownership-authorized API whose every error comes from one catalogue, while `standalone` stays contractually open and unchanged.

**Architecture:** Authorization is a use-case concern (spec §6.4), applied by a decorator at the port boundary in the composition root — mirroring the existing `TransactionalUseCases` decorator, because `application` carries no framework annotations (ArchUnit `applicationCarriesNoSpringAnnotations`). Errors converge on one `ErrorCode` enum and one `TinyLedgerException` supertype in `shared`, both framework-free, translated to RFC 7807 at a single point in `platform`. Spring Security is added once and configured **per profile** — one mechanism, two configurations — rather than excluded in `standalone`.

**Tech Stack:** Spring Boot 4.1.0, Spring Security 7.1.0, Java 25, JUnit 5 + MockMvc + Testcontainers, Cucumber (`@standalone` tag), ArchUnit, Liquibase, palantir-java-format via Spotless.

## Global Constraints

- Root package is `com.ffroliva.tinyledger`. Bootstrap class is `TinyLedgerApplication`.
- `..domain..` must not depend on `org.springframework..`, `jakarta.persistence..`, `org.apache.kafka..`, `io.lettuce..` (`HexagonalRulesTest.domainIsFrameworkFree`).
- `..application..` must not carry `@Service`, `@Component` or `@Transactional` (`HexagonalRulesTest.applicationCarriesNoSpringAnnotations`). Spring *types* are permitted there by the rule, but this plan does not add any.
- Only `..config..` and `..adapter.out..` may depend on `..adapter.out..` (`onlyConfigInstantiatesOutboundAdapters`).
- No package cycles across top-level slices (`noCyclicPackages`). **`platform` already imports `ledger.application.error` and `shared`, so nothing in `shared` or `ledger` may import `platform`.**
- `./mvnw -q verify` must exit 0 and start **ZERO** containers. `./mvnw -q verify -Pit` must exit 0 with **at least 26** ITs. **Baseline re-measured at `73890c9`, the commit this plan actually starts from: 123 unit tests, 26 ITs, both green, zero containers.** The figure of 122 quoted in earlier drafts was measured at `cd6336c`, before `d2555af` added the 9th ArchUnit rule; every test count predicted in this plan was one low and has been restated.
- **`full`-profile tests are integration tests.** `application-full.properties` hardcodes `localhost:5432/6379/9092` with Liquibase enabled, so a `full` `@SpringBootTest` cannot start under plain `verify`. Anything needing the `full` profile extends `AbstractIntegrationTest` and runs under `-Pit`, and supplies extra beans **through the existing `@DynamicPropertySource`, never via `@Import`** — ADR 0003 §"one context" forbids the latter because it forks the context by definition. Accepted consequence, decided 2026-08-05: `./mvnw -q verify` covers none of the security posture; the integration job is its only gate.
- Count tests from surefire **XML**, never the `.txt` reports — `.txt` reports `Tests run: 0` for `@Nested` classes and undercounts by 10.
- **The per-task test counts below are derived arithmetic, not measurements.** They chain forward from the 123 baseline assuming each task adds exactly the tests it specifies. Report the **actual** count from XML at every step. A mismatch is a signal to explain, not automatically a failure: if you added a test the plan did not anticipate, say so and carry the new number forward. What is never acceptable is a mismatch you did not notice or did not mention.
- `spotless:check` is bound inside `verify`; run `spotless:apply` before committing.
- Commit per task with explicit pathspecs. Never `git add -A`. Never push.
- `docs/spec.md` is **frozen at v3.8** for this plan. The admin on-behalf-of revision (v3.9) is a separate plan.

## Scope

**In:** the error catalogue, Spring Security added and configured per profile, a real caller principal, caller terms on the query ports, the authorization decorator, and the `x-fapi-interaction-id` filter.

**Deliberately out, each needing its own plan:**
- **DPoP / PAR / PKCE S256 / `private_key_jwt`.** The overnight experiment proved Spring Security 7.1.0 **refuses** a `cnf`-bound token on the bearer path (401) but never proved it **accepts** one on the DPoP path, because no valid proof was ever minted. Until a proof-minting harness exists, "sender-constraining works" and "bound tokens are rejected everywhere" produce an identical green suite. That harness plus the Keycloak FAPI client policies is a plan of its own.
- **Admin on-behalf-of (`ledger:admin`, `actor` on events).** Approved, targets spec **v3.9**, and needs the spec revision pass first. See `2026-08-04-spec-admin-on-behalf-of-proposal.md`.
- **Real Keycloak as the token issuer.** This plan authenticates against a locally-signed test JWT and a `JwtDecoder` built from a test key — the exact approach the overnight experiment verified works on this stack. Keycloak lands in `docker-compose.yml` and is proven by a boot proof, not by ITs, so the suite stays fast and hermetic.

## File Structure

**Created**
- `src/main/java/com/ffroliva/tinyledger/shared/error/ErrorCode.java` — the §6.5 catalogue as an enum. Framework-free. The single authority for status, `type` URI and message key.
- `src/main/java/com/ffroliva/tinyledger/shared/error/TinyLedgerException.java` — abstract supertype carrying an `ErrorCode` and message arguments. Framework-free.
- `src/main/java/com/ffroliva/tinyledger/shared/error/InvalidAmountException.java` — the typed replacement for the blanket `IllegalArgumentException` → 400 mapping.
- `src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java` — two `@Profile`-scoped `SecurityFilterChain` beans.
- `src/main/java/com/ffroliva/tinyledger/platform/CallerPrincipal.java` — resolves the caller: JWT subject in `full`, the fixed principal in `standalone`.
- `src/main/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilter.java` — echo-or-mint `x-fapi-interaction-id`.
- `src/main/java/com/ffroliva/tinyledger/config/AuthorizedUseCases.java` — the authorization decorator, mirroring `TransactionalUseCases`.
- `src/main/java/com/ffroliva/tinyledger/platform/SecurityProblemHandler.java` — the catalogued 401/403 the security chain writes itself, before `ErrorHandlingAdvice` can (Task 3).
- `src/main/java/com/ffroliva/tinyledger/shared/StandalonePrincipal.java` — the fixed principal, in the open kernel so `platform` need not import `config` (Task 4).
- `src/test/java/com/ffroliva/tinyledger/testsupport/TestJwt.java` — signs with the committed test keypair.
- `src/test/resources/test-jwt-private.pem`, `test-jwt-public.pem` — test-only keypair, so the decoder can be a **property** on the shared IT base rather than a per-class bean that forks the context.
- `src/test/java/com/ffroliva/tinyledger/config/SecurityConfigTest.java` (standalone, under `verify`) and `SecurityConfigIT.java` (full, under `-Pit`) — the latter is the only coverage of the production security posture, and Tasks 6, 6b and 7 all add to it.

**Modified**
- The six existing exceptions, to extend `TinyLedgerException`.
- `platform/ErrorHandlingAdvice.java` — six handlers collapse to one.
- `ledger/domain/Account.java` — throw `InvalidAmountException` instead of `IllegalArgumentException` for the amount rule.
- `balance/application/port/in/QueryBalanceUseCase.java`, `QueryHistoryUseCase.java` — gain a caller term.
- `balance/application/usecase/BalanceQueryService.java`, `HistoryQueryService.java` — accept and pass it.
- `config/UseCaseConfig.java` — return types change where the decorator wraps.
- `balance/adapter/in/web/BalanceController.java`, `ledger/adapter/in/web/LedgerController.java` — drop the hardcoded `CALLER`.
- `pom.xml` — add `spring-boot-starter-security`.
- **Task 0's test topology:** `contract/EventStoreContract.java` (class → interface), `contract/InMemoryEventStoreTest.java`, `ledger/adapter/out/postgres/PostgresEventStoreIT.java`, `.github/workflows/ci.yml`.
- `testsupport/AbstractIntegrationTest.java` — Task 3 adds the JWT public-key property to its existing `@DynamicPropertySource`. The one place a shared test property may go without forking the context.
- `shared/Money.java` — Task 2 adds `currencyOf(String)`; `ledger/adapter/in/web/LedgerApiMapper.java` uses it.
- `config/AuthorizationConfig.java` — Task 4 repoints or deletes it.
- `balance/application/port/in/QueryAccountsUseCase.java` and `usecase/AccountsQueryService.java` — Task 6 adds `account(AccountId)` so authorisation can tell absent from unowned.
- The three controller slice tests — `LedgerControllerTest`, `BalanceControllerTest`, `AuditControllerTest` — gain `@AutoConfigureMockMvc(addFilters = false)` in Task 3 and `@MockitoBean CallerPrincipal` in Task 4. They are not incidental: skipping either breaks all 38 slice tests at context startup.

---

### Task 0: One integration context, and a CI that actually runs the integration tests

Do this **first**. Every later task adds tests, and adding them to the wrong test topology gets more
expensive with each one. Rationale in full: `docs/adr/0003-test-topology-and-ci-parallelisation.md`.

**Files:**
- Modify: `src/test/java/com/ffroliva/tinyledger/contract/EventStoreContract.java` (class → interface with default methods)
- Modify: `src/test/java/com/ffroliva/tinyledger/contract/InMemoryEventStoreTest.java` — **it also `extends EventStoreContract`** (line 6) and must change with it. Round 3 caught this missing from both the file list and the commit pathspec: the build would be fixed and the fix left uncommitted.
- Modify: `src/test/java/com/ffroliva/tinyledger/ledger/adapter/out/postgres/PostgresEventStoreIT.java`
- Modify: `.github/workflows/ci.yml`
- `src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java` — **expected to need no change.** Listed because Task 3 adds to its `@DynamicPropertySource`. Do not move `spring.kafka.listener.auto-startup=false` here (Step 1).

**Interfaces:**
- Consumes: nothing.
- Produces: a single `full` Spring context shared by every integration test; a CI workflow with a
  Docker-free unit job and a containerised integration job running in parallel.

- [ ] **Step 1: Collapse the second `full` context**

`PostgresEventStoreIT` cannot extend `AbstractIntegrationTest` because it already extends
`EventStoreContract`, so it re-declares `@DynamicPropertySource` — and that second property source is a
**different context cache key**, giving the `full` profile two contexts. CR13 (a second
`AuditKafkaListener` competing for partitions in the `tiny-ledger-audit` group) was that fork's symptom;
`spring.kafka.listener.auto-startup=false` was the workaround.

Convert `EventStoreContract` from an abstract class to an **interface with default test methods**, then
make `PostgresEventStoreIT extends AbstractIntegrationTest implements EventStoreContract` and delete its
`@SpringBootTest`, `@ActiveProfiles` and `@DynamicPropertySource` entirely.

**`s/abstract class/interface/` does not compile.** Round 3 verified four separate errors; make all of
them, in both subclasses:

1. **`EventStoreContract:17-18`** — `private static final Instant T` and `private static final Currency GBP`.
   Interface fields are implicitly `public static final`; `private` on one is a compile error. Drop the
   modifiers: `Instant T = Instant.parse("2026-08-03T12:00:00Z");` and
   `Currency GBP = Currency.getInstance("GBP");`. (The `private` *methods* at `:20` and `:26` are fine —
   Java 9+ permits private interface methods, static and instance.)
2. **`EventStoreContract:15`** — `protected abstract EventStorePort store()` becomes `EventStorePort store();`
   (implicitly `public abstract`). Both overrides are currently `protected`, so they must widen to
   `public` or the compiler rejects them with *"attempting to assign weaker access privileges"*:
   `PostgresEventStoreIT:30-33` and `InMemoryEventStoreTest:9-12`.
3. **`EventStoreContract:31,39,47,59`** — every `@Test void` becomes `@Test default void`.
4. **`InMemoryEventStoreTest:6`** — `extends EventStoreContract` becomes `implements EventStoreContract`.

**Delete `spring.kafka.listener.auto-startup=false`** (`PostgresEventStoreIT:50`). Do not try to keep it:
its only remaining home would be `AbstractIntegrationTest`, and putting it there stops the listener that
`KafkaAuditModuleIT` awaits at four separate points — so "keep it" is not an available branch. It existed
only to stop the *second* context's listener competing for the `tiny-ledger-audit` partitions (CR13); with
one context there is one listener and nothing to compete with. If instability appears afterwards, the
correct response is to find the surviving second context, not to re-add the property.

- [ ] **Step 2: Verify the context count actually dropped**

**The check this step used to prescribe cannot fail.** It ran `-Dspring.test.context.cache.maxSize=1` and
called a surviving fork "obvious". It is not: that flag evicts least-recently-used, it does not fail, and
nothing in the command reads a context count. With two contexts the suite pays a few extra refreshes —
containers start once per JVM in `AbstractIntegrationTest`'s static block, so a reload costs seconds — and
still **exits 0**. Signing the task off on it would have left the fork intact. Round 3 adjudicated this;
use both checks below instead.

**Check A — the string check, run it BEFORE you edit anything.** This is the red half, and it costs no build:

```bash
git grep -nE '@SpringBootTest|@ActiveProfiles|@DynamicPropertySource' -- 'src/test/java/**/*IT.java'
```

Before your edit this prints three hits in `PostgresEventStoreIT` (lines 15, 16, 35). After it, it must
print **nothing** — every `*IT.java` inherits its context declaration from `AbstractIntegrationTest`, which
is not itself an `*IT.java` file. Per AGENTS.md trap 7, an empty result is not evidence on its own: prove
the search still works by re-running it against a term you know is present, e.g.
`git grep -nE '@BeforeEach' -- 'src/test/java/**/*IT.java'`, and show that it returns hits. Report both
outputs.

**Check B — count the contexts directly.**

```bash
./mvnw -q verify -Pit -Dlogging.level.org.springframework.test.context.cache=DEBUG
```

Spring logs its cache statistics as it goes:
`... size = 1, maxSize = 32, parentContextCount = 0, hitCount = N, missCount = 1`. **`missCount` is the
number of contexts actually built** — one per distinct cache key. Grep the output for `missCount` and
require the final value to be **1**. Two contexts report `missCount = 2`, which is the fork, stated as a
number rather than inferred from a stopwatch. Exit 0 with 26 ITs as well.

Report the `missCount` line verbatim. If it is not 1, the fork survived — find the second cache key rather
than proceeding.

- [ ] **Step 3: Split CI by infrastructure need**

`.github/workflows/ci.yml` today is a single job that runs `spotless:check`, `./mvnw -q verify` and the
docs-governance script — and **never runs `-Pit`**. The 26 integration tests are gated by nothing, so the
standing assumption that "CI covers anything missed locally" is currently false.

Replace it with three jobs. `gate` runs the second-scale checks and both others depend on it, so a
formatting slip never burns container minutes. `unit` needs no Docker — `verify` is asserted to start zero
containers. `integration` is the only job paying for the stack, and runs in parallel with `unit`:

**Keep the stage names and the drift placeholder.** The current file carries `"Stage 1 — lint & format"`,
`"Stages 2-3 …"`, `"Stage 6 — docs governance"` and a `resolve-drift placeholder` naming Plan 4 / spec
§12.1. No gate breaks if they go — `check_docs_governance.py` parses pytest output, not the workflow — but
they are the only in-repo pointer to the stage model, and deleting them loses it silently.

```yaml
jobs:
  gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: corretto, java-version: '25', cache: maven }
      - name: "Stage 1 — lint & format"
        run: ./mvnw -q spotless:check
      - name: "Stage 6 — docs governance"
        # SUBFAILED-line parsing assumes pytest's plain short-summary wording; only verified against
        # the pytest version pinned in this repo, not proven stable across OS/pytest releases.
        run: python scripts/ci/check_docs_governance.py

  unit:
    needs: gate
    # No Docker required: `verify` starts zero containers **by construction** — surefire's *IT.java
    # exclusion (pom.xml) keeps the containerised suites out — not because any gate asserts it.
    # AGENTS.md: if you state a rule, say which gate enforces it, or say plainly that none does.
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: corretto, java-version: '25', cache: maven }
      - name: "Stages 2-3 — compile, unit, JaCoCo gates, architecture"
        # Stages 4-5 (OpenAPI contract generation, @standalone Cucumber) run inside verify — the
        # generator and Cucumber's JUnit Platform engine are Maven-bound, not separate steps.
        run: ./mvnw -q verify

  integration:
    needs: gate
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: corretto, java-version: '25', cache: maven }
      - name: "Stages 7-12 — integration suite against real Postgres/Redis/Kafka"
        run: ./mvnw -q verify -Pit
      - name: "resolve-drift placeholder"
        run: echo "python CLI drift job lands in Plan 4 (spec §12.1)"
```

**Do not shard `integration` further.** CI bills per minute summed across runners, and each shard re-pays
checkout, Maven resolution, code generation, compilation, image pull and container start — for this stack
the container start alone dominates. A second shard only pays for itself if it removes more execution time
than that fixed cost, which the current 26-test suite does not. Revisit against the arithmetic in ADR 0003
when the integration suite grows, not before.

- [ ] **Step 4: Commit**

`InMemoryEventStoreTest` is in this pathspec and `AbstractIntegrationTest` is not — the first changes and
the second does not. `docs/adr/0003-…md` is also dropped: it was committed at `a8475db` and this task does
not modify it, so staging it implied an edit that never happens.

```bash
./mvnw -q spotless:apply
git add src/test/java/com/ffroliva/tinyledger/contract/EventStoreContract.java src/test/java/com/ffroliva/tinyledger/contract/InMemoryEventStoreTest.java src/test/java/com/ffroliva/tinyledger/ledger/adapter/out/postgres/PostgresEventStoreIT.java .github/workflows/ci.yml
git commit -m "test: one full-profile context, and a CI that actually runs the integration suite"
```

---

### Task 1: The error catalogue — `ErrorCode` and `TinyLedgerException`

Pure refactor. **No observable behaviour change**: every status and `type` stays exactly as it is today, which is why the existing controller tests are the safety net. If any of them needs editing, the task is wrong.

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/shared/error/ErrorCode.java`
- Create: `src/main/java/com/ffroliva/tinyledger/shared/error/TinyLedgerException.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/application/error/AccountNotFoundException.java`, `ConcurrencyConflictException.java`, `DuplicateMovementException.java`, `IdempotencyConflictException.java`, `OwnershipException.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/shared/CurrencyMismatchException.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/platform/ErrorHandlingAdvice.java`
- Test: `src/test/java/com/ffroliva/tinyledger/shared/error/ErrorCodeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ErrorCode` — **all eleven** rows of spec §6.5, no fewer: `INVALID_AMOUNT`, `CURRENCY_MISMATCH`, `ACCOUNT_NOT_FOUND`, `FORBIDDEN`, `IDEMPOTENCY_CONFLICT`, `VERSION_CONFLICT`, `INSUFFICIENT_FUNDS`, `NOT_AVAILABLE_IN_STANDALONE`, `RATE_LIMIT_EXCEEDED`, `UNAUTHENTICATED`, `EVENT_STORE_UNAVAILABLE`; accessors `int status()`, `String type()`, `String title()`, `String messageKey()`. Plus `TinyLedgerException` (`ErrorCode code()`, `Object[] args()`).

**Round 3 found the catalogue two rows short.** Earlier drafts omitted `UNAUTHENTICATED` (401) and `EVENT_STORE_UNAVAILABLE` (503), both of which `docs/spec.md:718,721` require. That is not cosmetic: after Task 3 the single most frequent `full` response is a 401, so the plan whose stated goal is "every error comes from one catalogue" would have left its most common error outside the catalogue. `UNAUTHENTICATED` is consumed by Task 3's `AuthenticationEntryPoint` and `FORBIDDEN` by Task 6b's `AccessDeniedHandler`; both are needed here so those tasks have something to reference.

**Known duplication, deliberately left alone.** Three sites hand-build `/errors/` types as string literals rather than reading `ErrorCode`: `AuditController.java:155` (`not-available-in-standalone`), `LedgerApiMapper.java:76` (`"/errors/" + result.rejectionReason()`, producing `insufficient-funds` and `currency-mismatch`) and `BalanceController.java:179` (`account-not-found`). After this task the enum and those literals are two sources of truth for the same wire contract — the CR14 pattern. Converging them was considered and **declined for this plan** so Task 1 stays a behaviour-preserving refactor whose safety net is the untouched controller tests. It is recorded as a follow-up; do not fix it here.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/ffroliva/tinyledger/shared/error/ErrorCodeTest.java`:

```java
package com.ffroliva.tinyledger.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test // §6.5: one catalogue, so every code must carry a complete answer
    void everyCodeHasAStatusATypeAndAMessageKey() {
        assertThat(ErrorCode.values()).isNotEmpty();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status()).as("status of %s", code).isBetween(400, 599);
            assertThat(code.type()).as("type of %s", code).startsWith("/errors/");
            assertThat(code.messageKey()).as("messageKey of %s", code).startsWith("problem.");
        }
    }

    @Test // a duplicated type URI would make the catalogue ambiguous at the wire
    void typesAreUnique() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::type))
                .doesNotHaveDuplicates();
    }

    @Test // §6.5 is the authority; this pins the catalogue AGAINST it, so a dropped or invented row is red
    void theCatalogueIsExactlySpecSection6_5() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::type))
                .containsExactlyInAnyOrder(
                        "/errors/insufficient-funds",
                        "/errors/invalid-amount",
                        "/errors/currency-mismatch",
                        "/errors/version-conflict",
                        "/errors/idempotency-conflict",
                        "/errors/rate-limit-exceeded",
                        "/errors/unauthenticated",
                        "/errors/forbidden",
                        "/errors/account-not-found",
                        "/errors/event-store-unavailable",
                        "/errors/not-available-in-standalone");
    }
}
```

**The third test is the one that matters.** The first two check that every constant is *well-formed* and
*distinct* — neither can detect a **missing** row, which is exactly how the catalogue came to be two short.
Pinning the set against an explicit list transcribed from `docs/spec.md:712-722` makes a dropped row a red
test rather than a silent hole. Transcribe those eleven URIs from the spec table; do not copy them from the
enum you are about to write, or the test asserts the code against itself.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw -q test -Dtest=ErrorCodeTest`
Expected: compilation failure — `package com.ffroliva.tinyledger.shared.error does not exist`.

- [ ] **Step 3: Create `ErrorCode`**

```java
package com.ffroliva.tinyledger.shared.error;

/**
 * Spec §6.5: the error catalogue, as one enum. Framework-free by design — this lives in the open
 * kernel that {@code domain} compiles against, so a Spring type here would put spring-web on the
 * domain's transitive compile path. The HTTP status is an {@code int}, not an {@code HttpStatus}.
 *
 * <p>The message key is resolved against a {@code MessageSource} at the boundary, so the human-readable
 * half can be localised later without touching the machine-readable {@code type}, which is the part
 * clients match on (RFC 7807 says {@code title} should not vary between occurrences).
 */
public enum ErrorCode {
    INVALID_AMOUNT(400, "/errors/invalid-amount", "Invalid amount"),
    UNAUTHENTICATED(401, "/errors/unauthenticated", "Unauthenticated"),
    FORBIDDEN(403, "/errors/forbidden", "Forbidden"),
    ACCOUNT_NOT_FOUND(404, "/errors/account-not-found", "Account not found"),
    IDEMPOTENCY_CONFLICT(409, "/errors/idempotency-conflict", "Idempotency conflict"),
    VERSION_CONFLICT(409, "/errors/version-conflict", "Version conflict"),
    CURRENCY_MISMATCH(422, "/errors/currency-mismatch", "Currency mismatch"),
    INSUFFICIENT_FUNDS(422, "/errors/insufficient-funds", "Insufficient funds"),
    RATE_LIMIT_EXCEEDED(429, "/errors/rate-limit-exceeded", "Rate limit exceeded"),
    NOT_AVAILABLE_IN_STANDALONE(501, "/errors/not-available-in-standalone", "Not available in standalone"),
    EVENT_STORE_UNAVAILABLE(503, "/errors/event-store-unavailable", "Event store unavailable");

    private final int status;
    private final String type;
    private final String title;

    ErrorCode(int status, String type, String title) {
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    /** The stable, developer-facing title. Not localised — see the class javadoc. */
    public String title() {
        return title;
    }

    /** Derived, not declared, so there is no second thing to keep in sync. */
    public String messageKey() {
        return "problem." + type.substring("/errors/".length());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=ErrorCodeTest`
Expected: PASS, **3** tests.

- [ ] **Step 5: Create `TinyLedgerException`**

```java
package com.ffroliva.tinyledger.shared.error;

/**
 * Supertype for every failure this system reports to a caller by a catalogued code (§6.5).
 *
 * <p>Named for the system rather than the {@code ledger} module, because
 * {@code com.ffroliva.tinyledger.ledger} exists and a {@code LedgerException} would read as that
 * module's exception when this is the supertype for {@code audit}, {@code balance} and
 * {@code notification} too.
 *
 * <p>Carries no framework type and no HTTP status of its own — only an {@link ErrorCode}. A CLI or a
 * Kafka consumer driving the same use case can catch this and read the code without inheriting a
 * notion of "404". The single translation to RFC 7807 lives in {@code platform}.
 */
public abstract class TinyLedgerException extends RuntimeException {

    private final ErrorCode code;
    private final Object[] args;

    protected TinyLedgerException(ErrorCode code, String message, Object... args) {
        super(message);
        this.code = code;
        this.args = args.clone();
    }

    public ErrorCode code() {
        return code;
    }

    public Object[] args() {
        return args.clone();
    }
}
```

- [ ] **Step 6: Make the six existing exceptions extend it**

Each keeps its current constructor signature and message so nothing else changes. `OwnershipException` for example:

```java
package com.ffroliva.tinyledger.ledger.application.error;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;

/** §6.4: the caller is not the owner of the stream it addressed. */
public class OwnershipException extends TinyLedgerException {
    public OwnershipException(String caller, AccountId accountId) {
        super(ErrorCode.FORBIDDEN, "caller " + caller + " does not own " + accountId.value(), caller, accountId.value());
    }
}
```

Apply the same shape with `ErrorCode.ACCOUNT_NOT_FOUND` (`AccountNotFoundException`), `ErrorCode.VERSION_CONFLICT` (`ConcurrencyConflictException`), `ErrorCode.IDEMPOTENCY_CONFLICT` (`IdempotencyConflictException` and `DuplicateMovementException`), and `ErrorCode.CURRENCY_MISMATCH` (`CurrencyMismatchException`). Read each file first and preserve its existing constructor parameters and message text verbatim — only the `extends` clause and the `super(...)` call change.

- [ ] **Step 7: Collapse the advice to one handler**

In `ErrorHandlingAdvice`, delete the five handlers `currencyMismatch`, `accountNotFound`, `forbidden`, `idempotencyConflict`, `versionConflict` and their imports, and add:

```java
    /** §6.5: one catalogue, one translation. The code carries status, type and title. */
    @ExceptionHandler(TinyLedgerException.class)
    ResponseEntity<ProblemDetail> catalogued(TinyLedgerException exception) {
        ErrorCode code = exception.code();
        return problem(HttpStatus.valueOf(code.status()), code.type(), code.title());
    }
```

**Leave `malformed()` exactly as it is** (it still maps the blanket `IllegalArgumentException` to 400) and **leave `unexpected(Exception)` exactly as it is**. Both change in Task 2 and never.

- [ ] **Step 8: Run the whole unit suite — the existing tests are the proof**

Run: `./mvnw -q verify`
Expected: exit 0, **126** tests (123 baseline + `ErrorCodeTest`'s 3), **zero** test edits required. `LedgerControllerTest.wrongOwnerIsForbidden` (403), `unknownAccountIsNotFound` (404) and `concurrencyConflictIsVersionConflict` (409) passing unchanged is the evidence that the collapse preserved behaviour — they assert exact `type` **values**, not merely statuses, so a wrong `ErrorCode` on any collapsed handler turns them red. If any existing test needs editing, stop: this task is a pure refactor and an edit means it changed behaviour.

- [ ] **Step 9: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/shared/error src/main/java/com/ffroliva/tinyledger/ledger/application/error src/main/java/com/ffroliva/tinyledger/shared/CurrencyMismatchException.java src/main/java/com/ffroliva/tinyledger/platform/ErrorHandlingAdvice.java src/test/java/com/ffroliva/tinyledger/shared/error
git commit -m "refactor: collapse the error catalogue behind TinyLedgerException + ErrorCode"
```

---

### Task 2: Type the amount rule, then stop mapping bare `IllegalArgumentException`

This one **does** change behaviour, which is why it is separate. Today `ErrorHandlingAdvice` maps every `IllegalArgumentException` to 400 `/errors/invalid-amount`, so a malformed pagination cursor tells the caller their *amount* is wrong (parked finding CR12).

**Council correction — read this before touching anything.** An earlier draft of this task claimed `Account`'s `IllegalArgumentException("amount must be positive")` was the mechanism behind the 400, and told the implementer to convert that throw and then delete the blanket mapping. **That was wrong, and following it would have turned a green test red and sent you to the wrong file.** Verified:

- `docs/api/openapi.yaml` defines a **`MovementAmount`** schema separate from `Money`, with `minimum: 1`. The generator emits `@Min(1)`, so a zero or negative `minorUnits` is rejected by **bean validation** as a `MethodArgumentNotValidException` — which `malformed()` keeps handling. `LedgerControllerTest.negativeMinorUnitsIsBadRequestBeforeAnyServiceCall` asserts `verifyNoInteractions(recordMovement)`, proving the use case never runs. **`Account`'s guard is unreachable over HTTP.**
- The `IllegalArgumentException` that genuinely reaches `malformed()` comes from **`Currency.getInstance(...)`** inside `LedgerApiMapper`. The OpenAPI pattern is `^[A-Z]{3}$`, which `"ZZZ"` satisfies, so bean validation passes it through and the JDK throws on the unknown ISO code. `LedgerControllerTest.unknownCurrencyCodeIsBadRequest` (line ~206) pins exactly this: `{"currency":"ZZZ"}` → **400 `/errors/invalid-amount`**, `verifyNoInteractions(openAccount)`.

So the currency path — not the amount path — is what makes the blanket mapping load-bearing. Type **that** first, or removing the mapping turns a bad currency code into a 500.

**Round 3 correction — the remedy targeted the wrong function.** The diagnosis above is right; Step 4 as
originally written was not. There are **two** `Currency.getInstance` call sites on the request path, and the
named proof test goes through the one the task did not touch:

- `LedgerApiMapper.java:34` — `new OpenAccount(caller, request.getName(), Currency.getInstance(request.getCurrency()))`.
  A bare JDK call. **This is the one `unknownCurrencyCodeIsBadRequest` exercises**, because that test POSTs
  `/api/v1/accounts`.
- `Money.of` (`Money.java:12`) — reached only from `LedgerApiMapper.java:42` `toMoney(MovementAmount)`, i.e.
  the deposit/withdraw body.

Patching only `Money.of` and then deleting the blanket mapping turns `unknownCurrencyCodeIsBadRequest` into
a 500 — precisely the failure Step 6 tells you means "Step 4 was missed". And once that is fixed by hand,
`Money.of`'s guard is covered by nothing: no test posts a *movement* with a well-formed-but-unknown code, so
reverting it stays green while the money path regresses to an opaque 500. Type **both**, and cover both.

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/shared/error/InvalidAmountException.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/shared/Money.java` — add `currencyOf(String)` and use it in `of`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerApiMapper.java` — `toCommand` uses `Money.currencyOf`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/domain/Account.java` (the `amount must be positive` throw — defence in depth for non-HTTP callers such as Plan 4's CLI, not the HTTP path)
- Modify: `src/main/java/com/ffroliva/tinyledger/platform/ErrorHandlingAdvice.java`
- Test: `src/test/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerControllerTest.java`
- Test: `src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java` — **line 115 asserts `isInstanceOf(IllegalArgumentException.class)` on the amount guard** and must become `InvalidAmountException`, which does not extend it. Leave the other assertion (around line 103) alone if it covers the `"empty stream"` guard — read both and change only the amount one.

**Interfaces:**
- Consumes: `ErrorCode`, `TinyLedgerException` from Task 1.
- Produces: `InvalidAmountException(String message)`.

- [ ] **Step 1: Write the failing test**

Add to `LedgerControllerTest`:

```java
    @Test // CR12: an IllegalArgumentException that is not about an amount must not claim to be
    void anUnrelatedIllegalArgumentIsNotReportedAsAnInvalidAmount() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new IllegalArgumentException("Invalid UUID string: nope"));

        deposit()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(not("/errors/invalid-amount")));
    }

    @Test // the movement path's own unknown-code guard — Money.of, the sibling of toCommand's
    void anUnknownCurrencyCodeOnAMovementIsBadRequest() throws Exception {
        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", ACCOUNT, MOVEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":{"currency":"ZZZ","minorUnits":100}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

        verifyNoInteractions(recordMovement);
    }
```

**`doesNotExist()` cannot pass** — `ProblemDetail.type` defaults to `URI.create("about:blank")` and is never
null, and `@JsonInclude(NON_EMPTY)` does not suppress a non-null `URI`. The repo's own 500 test
(`LedgerControllerTest:231-239`) asserts `$.detail` and `$.status` and conspicuously never `$.type` — that is
the evidence. `not(...)` is `org.hamcrest.Matchers.not`; add the static import. It also states what the test
is actually about: the 500 must not *claim to be an invalid amount*.

The second test is the one that keeps `Money.of`'s new guard honest. Match its request shape to the existing
`deposit()` helper at `LedgerControllerTest:255` — reuse the same path variables and HTTP method rather than
inventing new ones.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw -q test -Dtest=LedgerControllerTest#anUnrelatedIllegalArgumentIsNotReportedAsAnInvalidAmount`
Expected: FAIL — actual status 400 with `type=/errors/invalid-amount`.

- [ ] **Step 3: Create `InvalidAmountException`**

```java
package com.ffroliva.tinyledger.shared.error;

/** §6.5: the 400 — a movement amount that is zero, negative, or otherwise not a usable amount. */
public class InvalidAmountException extends TinyLedgerException {
    public InvalidAmountException(String message) {
        super(ErrorCode.INVALID_AMOUNT, message);
    }
}
```

- [ ] **Step 4: Type the currency-code failure — this is the load-bearing one**

Put the guard in **one** place and route both call sites through it. In `Money`:

```java
    /**
     * §6.5: a well-formed but unknown ISO code. The OpenAPI pattern {@code ^[A-Z]{3}$} admits "ZZZ", so bean
     * validation passes it through and the JDK is what refuses it. One guard, because there are two call
     * sites — this one and {@code LedgerApiMapper.toCommand} — and a guard on only one of them leaves the
     * other answering an opaque 500.
     */
    public static Currency currencyOf(String currencyCode) {
        try {
            return Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new InvalidAmountException("unknown currency code: " + currencyCode);
        }
    }

    public static Money of(String currencyCode, long minorUnits) {
        return new Money(currencyOf(currencyCode), minorUnits);
    }
```

Then in `LedgerApiMapper.java:34`, replace the bare JDK call:

```java
    static OpenAccount toCommand(OpenAccountRequest request, String caller) {
        return new OpenAccount(caller, request.getName(), Money.currencyOf(request.getCurrency()));
    }
```

`Money` is in `shared`, and `shared.error` is framework-free, so nothing about the ArchUnit rules changes — verify that in Step 6 rather than assuming it. `LedgerApiMapper` already imports `com.ffroliva.tinyledger.shared.Money` under its fully-qualified name (it has a local `Money` DTO of its own — see `toMoney` at `:42-48`), so use the qualified form there and do not add a clashing import.

- [ ] **Step 5: Convert the domain guard too, then remove the blanket mapping**

In `Account.java`, replace the amount guard's `IllegalArgumentException` with `InvalidAmountException`. This one is **defence in depth**, not the HTTP path — `@Min(1)` on the generated `MovementAmount` already rejects a negative before the use case runs. It matters for Plan 4's CLI and for direct service callers.

Leave `Account.java`'s `"empty stream"` `IllegalArgumentException` (`:30`) and its `IllegalStateException` (`:76`) **alone**: those are bug signals, not catalogued business errors, and they should surface as 500s.

`InvalidAmountException` does **not** extend `IllegalArgumentException`, so `AccountTest:115` —
`.isInstanceOf(IllegalArgumentException.class)`, commented *"defence in depth; the boundary 400s first (§4.6)"* —
turns red the moment you change the guard. Update that one assertion to `InvalidAmountException`. Check the
other assertion around `AccountTest:103` before touching it: if it covers the `"empty stream"` guard it must
stay as it is. This is the one place in Task 2 where editing an existing test is correct rather than a
warning sign, because the guard's type is deliberately changing.

Then in `ErrorHandlingAdvice.malformed()`, delete `IllegalArgumentException.class` from the `@ExceptionHandler({...})` list. Keep every other entry — those are Spring's own request-binding failures and are genuinely 400s.

- [ ] **Step 6: Run the affected suites**

Run: `./mvnw -q test -Dtest='LedgerControllerTest+AccountTest+HexagonalRulesTest+MoneyTest'`
Expected: PASS, including **`unknownCurrencyCodeIsBadRequest` staying 400** — that test is the real proof of this task, because it is the one the blanket mapping was carrying. If it turns 500, Step 4 was missed or `Money.of` is not on that path; do **not** weaken the test.

- [ ] **Step 7: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 with **128** tests (126 after Task 1, plus this task's two new `LedgerControllerTest` cases), and exit 0 with 26 ITs. Malformed-cursor behaviour changes from 400 to 500 here; that is the correct intermediate state and its own error code is out of scope for this plan (recorded as a follow-up).

- [ ] **Step 8: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/shared/error/InvalidAmountException.java src/main/java/com/ffroliva/tinyledger/shared/Money.java src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerApiMapper.java src/main/java/com/ffroliva/tinyledger/ledger/domain/Account.java src/main/java/com/ffroliva/tinyledger/platform/ErrorHandlingAdvice.java src/test/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerControllerTest.java src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java
git commit -m "fix: type the amount and currency rules so a stray IllegalArgumentException stops claiming to be one (CR12)"
```

---

### Task 3: Add Spring Security, configured per profile

Measured facts this task is built on, from `.superpowers/sdd/2026-08-05-overnight/authz-error-mapping-experiment.md`: adding the starter bare fails **21 of 122** tests, all `CucumberTest`, all `expected 201 but was 401`; **all 38 `@WebMvcTest` slices still pass**, so the slice tests structurally cannot detect this and only the full-context suite can. A `permitAll()` chain alone still fails 21/21 with **403** because CSRF is on by default.

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java`
- Create: `src/test/java/com/ffroliva/tinyledger/testsupport/TestJwt.java`
- Test: `src/test/java/com/ffroliva/tinyledger/config/SecurityConfigTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: two `SecurityFilterChain` beans; `TestJwt.token(String subject, String... roles)` returning a signed compact JWT, and `TestJwt.decoder()` returning a `JwtDecoder` that trusts it.

- [ ] **Step 1: Add the dependency**

In `pom.xml`, beside `spring-boot-starter-web`:

```xml
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
```

**Round 3: the measurement above does not cover `spring-security-test`, and that dependency changes the
slices.** The experiment measured `spring-boot-starter-security` alone, and concluded "`@WebMvcTest` does
not wire the security filter chain". That conclusion is conditional, not structural: Boot applies the chain
to MockMvc through `MockMvcSecurityConfiguration`, which is `@ConditionalOnClass(SecurityMockMvcConfigurers)` —
and `SecurityMockMvcConfigurers` ships in `spring-security-test`, which this step adds permanently. Once it
is on the test classpath the slices get Boot's **default** chain (`anyRequest().authenticated()` + CSRF),
because `SecurityConfig` is a `@Configuration` in `config` and is not one of `WebMvcTypeExcludeFilter`'s
include types, so the profile-scoped chains never load in a slice.

**Decide it now rather than at the tripwire.** Add `@AutoConfigureMockMvc(addFilters = false)` to the three
slice classes — `LedgerControllerTest`, `BalanceControllerTest`, `AuditControllerTest` — in this same step.
That preserves exactly what those 38 slice tests exist to check (controller mapping, validation, error
translation) and is consistent with this plan's own finding that a slice is structurally incapable of
testing security anyway: the real security coverage is `SecurityConfigIT` in Step 7. Do **not** instead
`@Import(SecurityConfig.class)` into the slices — that would make 38 controller tests depend on the security
posture and turn every future chain change into a 38-test failure.

Add those three files to this task's Files list and its commit pathspec.

- [ ] **Step 2: Run the suite to see the documented failure**

Run: `./mvnw -q verify`

Expected: FAIL with **21 Cucumber scenario failures**, all `expected: 201 but was: 401`. That is the
documented shape of the failure, and seeing it confirms the environment matches the experiment before any
configuration is written.

**This step is a pre-condition on the starting state, not a check of your work** — no outcome of this task
can make it fail, so do not treat a green Step 2 as progress. Step 4 is the real check.

Two caveats, both from round 3:

- Say *Cucumber* failures. The total test count has moved since the experiment (126 after Tasks 1–2), so
  "21 failures" is only meaningful as a count of Cucumber scenarios.
- **If the slice tests also fail, that is the `spring-security-test` effect from Step 1, not a surprise.**
  With `addFilters = false` applied in Step 1 they should not. If you see slice failures anyway, fix them
  with `addFilters = false` rather than by weakening any assertion, and record it — do not proceed with a
  different number and no explanation.

- [ ] **Step 3: Write the standalone chain**

```java
package com.ffroliva.tinyledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spec §1: one codebase, two run modes — so security is one mechanism with two configurations rather
 * than a dependency that is excluded in one of them. Excluding the autoconfiguration would also work
 * and is two properties, but it leaves no {@code HttpSecurity} at all, so {@code standalone} could
 * never declare a chain — and the {@code x-fapi-interaction-id} filter and every future chain-level
 * concern would have nothing to attach to.
 *
 * <p>CSRF is off in both profiles and sessions are stateless. That is not a shortcut: CSRF defends
 * against the browser attaching <em>ambient</em> credentials (a cookie, Basic auth) to a cross-site
 * request. A token in an {@code Authorization} header is not ambient — script must attach it
 * deliberately — so there is nothing for the token to protect. This system has no cookie, no session,
 * and no browser surface at all (no springdoc, no swagger-ui, no static resources). The stateless
 * declaration is the guard: if anyone later introduces cookie authentication or a UI, it is this line
 * that has to change, which forces the CSRF question back into the open instead of leaving a hole.
 */
@Configuration
public class SecurityConfig {

    /** The brief as written: in-memory, unauthenticated, dependency-free. */
    @Bean
    @Profile("standalone")
    SecurityFilterChain standaloneChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /**
     * The production stack: every API call carries a JWT; ownership is checked at the port boundary (§6.4).
     *
     * <p>The decoder is built from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, which
     * {@link com.ffroliva.tinyledger.platform.FailClosedGuard} already treats as a full-mode marker — if
     * that property is ever present while {@code standalone} is active, the guard refuses to start rather
     * than run an unauthenticated ledger (spec §1). Setting it here is what makes that guard meaningful.
     */
    @Bean
    @Profile("full")
    SecurityFilterChain fullChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {})
                        .authenticationEntryPoint(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
```

**Round 3 / decision: the chain's own errors must be catalogued too.** Without the two handlers above, the
single most frequent `full` response — a 401 on every unauthenticated call — carries **no** problem body at
all. `ErrorHandlingAdvice` is a `@RestControllerAdvice`: it runs inside `DispatcherServlet`, and both the
401 (from `BearerTokenAuthenticationEntryPoint`) and Task 6b's `denyAll()` 403 (from `AuthorizationFilter`
via `ExceptionTranslationFilter`) are written **before** the request ever reaches it. The measured default
is `401, empty body`; the 403 default is `BasicErrorController`'s shape, which echoes the request `path` —
and §6.5 forbids internal identifiers crossing the boundary. `docs/api/openapi.yaml:430-453` already
promises clients `application/problem+json` with a `type` for both.

So create `src/main/java/com/ffroliva/tinyledger/platform/SecurityProblemHandler.java`, one class serving
both interfaces:

```java
package com.ffroliva.tinyledger.platform;

/**
 * §6.5: the two errors the security chain writes itself. Both are produced before {@code DispatcherServlet}
 * runs, so {@link ErrorHandlingAdvice} — a {@code @RestControllerAdvice} — never sees them and cannot
 * translate them. Without this, the plan's "one catalogue" goal would be contradicted by the two most
 * frequent responses in {@code full}.
 *
 * <p>Lives in {@code platform}, not {@code config}: {@code config} already imports the business modules, so
 * {@code config → platform} closes no loop, while {@code platform → config} would.
 */
@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    public SecurityProblemHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, ErrorCode.UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        ProblemDetail body = ProblemDetail.forStatus(code.status());
        body.setType(URI.create(code.type()));
        body.setTitle(code.title());
        // §6.5/§6.6: the same correlating id ErrorHandlingAdvice attaches. Requires the interaction-id
        // filter to run BEFORE the security chain — see Task 7.
        String traceId = MDC.get("traceId");
        if (traceId != null) body.setProperty("traceId", traceId);
        response.setStatus(code.status());
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
```

Note `.authenticationEntryPoint(problems)` is set **both** inside `oauth2ResourceServer` and on
`exceptionHandling`: the resource-server DSL installs its own `BearerTokenAuthenticationEntryPoint` that
otherwise wins for bearer-token failures. Setting only the outer one is a common and silent mistake — the
401 keeps its empty body while the 403 looks fixed.

**Council fix (P0-2).** `.jwt(jwt -> {})` builds nothing on its own: Spring needs an issuer or a `JwtDecoder` bean, and an earlier draft of this task supplied neither. A real `full` boot would have failed at context startup, and **nothing in the suite would have caught it** — `SecurityConfigTest` activates only `standalone`, and it is verified that **no integration test makes an HTTP call** (all 26 are adapter-level). So also add to `src/main/resources/application-full.properties`:

```properties
# §6.4: the resource server's trust anchor. FailClosedGuard treats this key as a full-mode marker —
# its presence under the standalone profile is a refusal to start, not a warning.
spring.security.oauth2.resourceserver.jwt.issuer-uri=${LEDGER_ISSUER_URI:http://localhost:8081/realms/tiny-ledger}
```

That default points at the Keycloak service added to `docker-compose.yml` in the follow-up plan; the env var lets a real deployment override it. Tests never reach it, because Step 6's `full` test supplies a `JwtDecoder` bean directly.

- [ ] **Step 4: Run the suite again**

Run: `./mvnw -q verify`
Expected: exit 0, **128** tests (the count after Tasks 1–2; this step adds none). The 21 Cucumber scenarios pass because `standalone` permits all and CSRF is disabled. **If they fail with 403 rather than 401, the `csrf.disable()` line is missing** — that is the exact failure the experiment recorded.

- [ ] **Step 5: Create the test JWT support**

```java
package com.ffroliva.tinyledger.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Mints locally-signed tokens so the suite needs no Keycloak. Verified working on this stack by the
 * 2026-08-05 authz experiment. Real Keycloak is a compose service proven by a boot proof, not by ITs —
 * keeping the suite hermetic and fast.
 */
**Round 3 / decision: the key must be a committed file, not a freshly generated one.** A key generated at
class-init can only be handed to the context as a `JwtDecoder` **bean**, and the only ways to add a bean to
one IT class are `@Import` or a per-class `@TestConfiguration` — both of which change the context cache key
and fork the `full` context that Task 0 just spent a whole task merging. ADR 0003 forbids `@Import` on a
subclass for exactly this reason.

So commit a fixed test keypair under `src/test/resources/` — `test-jwt-private.pem` (PKCS#8) and
`test-jwt-public.pem` (X.509/SPKI) — and have `AbstractIntegrationTest`'s **existing** `@DynamicPropertySource`
point the resource server at the public half:

```java
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> "classpath:test-jwt-public.pem");
```

Because that lives on the shared base class, every IT gets an identical cache key and the suite keeps one
context. These are test-only keys for a repository with no remote; label them as such in a
`src/test/resources/README` line so nobody mistakes them for a secret.

**One thing to settle empirically, not by assumption:** `application-full.properties` also sets
`jwt.issuer-uri`, and Boot's decoder auto-configuration keys off both properties. Whether
`public-key-location` cleanly wins, or the issuer condition also fires and yields two candidate decoders, is
a framework detail this plan does **not** assert. Establish it by running the suite; if they conflict, resolve
it in whatever way keeps the property on `AbstractIntegrationTest` and the context count at one — overriding
`issuer-uri` to empty in the same `@DynamicPropertySource` is the obvious first try. **Record which you did
and why.** Then re-run Task 0 Step 2's `missCount` check and confirm it still reports **1**: this task is the
most likely place in the plan to silently re-fork the context.

```java
public final class TestJwt {

    private static final RSAKey KEY = load();

    private TestJwt() {}

    /** Reads the committed PKCS#8 private key and its public half, so the decoder can be a property. */
    private static RSAKey load() {
        // parse src/test/resources/test-jwt-private.pem + test-jwt-public.pem into an RSAKey with
        // keyID "test"; throw IllegalStateException with a clear message if either is unreadable
    }

    public static String token(String subject, String... roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("roles", List.of(roles))
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not mint the test token", e);
        }
    }
}
```

- [ ] **Step 6: Write the chain test**

`src/test/java/com/ffroliva/tinyledger/config/SecurityConfigTest.java`:

```java
package com.ffroliva.tinyledger.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import com.ffroliva.tinyledger.testsupport.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = TinyLedgerApplication.class)
@ActiveProfiles("standalone")
class SecurityConfigTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test // the brief: standalone is unauthenticated, and stays so once Security is on the classpath
    void standalonePermitsAnUnauthenticatedRead() throws Exception {
        mvc().perform(get("/api/v1/accounts")).andExpect(status().isOk());
    }

}
```

`standalone` boots without infrastructure, so this one stays a plain `@SpringBootTest` under `verify`. It
needs no `JwtDecoder` at all — the standalone chain permits everything and never builds one. The
`TrustTheTestKey` `@TestConfiguration` an earlier draft had here is **deleted**: the `full` cases move to
Step 7's IT, and supplying a decoder bean per test class is the context fork ADR 0003 forbids.

- [ ] **Step 7: Prove the `full` chain boots and actually refuses (council fix P0-2)**

**Round 3 rewrote this step. The nested-class form could not have worked**, for a reason that would have
been misdiagnosed: `@Nested` classes inherit `@NestedTestConfiguration(INHERIT)` and `@ActiveProfiles`
defaults to `inheritProfiles = true`, so a nested `@ActiveProfiles("full")` activates **`standalone` + `full`**
together. `FailClosedGuard` then refuses to start, because `application-full.properties:5` sets
`spring.datasource.url` while `standalone` is active — a deliberate `IllegalStateException`, not a missing
container. The old step pre-authorised reading that as "cannot start without containers" and moving on.

Write it as an integration test from the start, per this plan's global constraint that `full`-profile tests
are ITs:

`src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java`, `extends AbstractIntegrationTest`, with
**no** `@SpringBootTest`, `@ActiveProfiles`, `@Import` or `@TestPropertySource` of its own — it inherits all
of them, which is what keeps the context count at one. Build its `MockMvc` from the injected
`WebApplicationContext` with `SecurityMockMvcConfigurers.springSecurity()` applied, exactly as below.

Without this class the profile that carries the entire security posture has **no test at all**:

```java
```java
class SecurityConfigIT extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test // the context starting at all is half the assertion — .jwt(...) needs a decoder to exist
    void anUnauthenticatedRequestIsRefused() throws Exception {
        mvc().perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    @Test // §6.5: and the refusal is catalogued, not an empty body
    void theRefusalCarriesTheCataloguedProblem() throws Exception {
        mvc().perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/unauthenticated"));
    }

    @Test // and a valid token gets through, so the refusal above is not just "everything 401s"
    void aValidTokenIsAccepted() throws Exception {
        mvc().perform(get("/api/v1/accounts").header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isOk());
    }
}
```

Three tests, and each closes a hole the others leave:

- `aValidTokenIsAccepted` matters as much as the refusal: a chain that rejects *everything* satisfies the
  401 assertion while being entirely broken.
- `theRefusalCarriesTheCataloguedProblem` is the one that makes `SecurityProblemHandler` load-bearing.
  Asserting only `status().isUnauthorized()` passes against Spring's default empty body, which is what the
  experiment actually measured — so a status-only test would have shipped the uncatalogued 401 green. Assert
  the content type as well as the `type`: the status is right in both worlds and only the body distinguishes
  them.

**Do not downgrade any of this to a `@WebMvcTest` slice.** A slice was proven in this repo to be structurally
incapable of detecting security misconfiguration, and after Step 1 the slices run with `addFilters = false`,
so they have no chain at all.

- [ ] **Step 8: Run it**

Run `./mvnw -q verify` (which covers `SecurityConfigTest`, the `standalone` case), then
`./mvnw -q verify -Pit` (which covers `SecurityConfigIT`'s three `full` cases). **One build at a time.**

Expected: exit 0 and exit 0; **29** ITs, up from 26. Then re-run Task 0 Step 2's Check B and confirm
`missCount` is still **1** — this task adds a property to `AbstractIntegrationTest` and a new IT class, and
is the likeliest point in the plan to fork the context again. Report the `missCount` line verbatim.

- [ ] **Step 9: Commit**

```bash
./mvnw -q spotless:apply
git add pom.xml src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java src/main/java/com/ffroliva/tinyledger/platform/SecurityProblemHandler.java src/main/resources/application-full.properties src/test/java/com/ffroliva/tinyledger/testsupport/TestJwt.java src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java src/test/java/com/ffroliva/tinyledger/config/SecurityConfigTest.java src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java src/test/resources/test-jwt-private.pem src/test/resources/test-jwt-public.pem src/test/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerControllerTest.java src/test/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceControllerTest.java src/test/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditControllerTest.java
git commit -m "feat: add Spring Security, configured per profile rather than excluded in standalone"
```

---

### Task 4: A real caller principal

Both controllers currently hardcode `private static final String CALLER = AuthorizationConfig.STANDALONE_PRINCIPAL;`. In `full` the caller must be the JWT subject.

**Council fixes folded here — two, and one refutes a verification I reported as sound.**

**A package cycle.** I checked the current tree, found that nothing outside `platform` imports `platform`, and concluded this task was cycle-free. That was the wrong state to check: **after** this task the controllers — which live in the `balance` and `ledger` slices — import `platform.CallerPrincipal`, and `CallerPrincipal` imports `config.AuthorizationConfig`. Since `config` already imports `balance`, the result is `config → balance → platform → config`, and `noCyclicPackages` fails at Step 6 where this plan predicts exit 0.

Fix: **move the constant to `shared`**, the open kernel every slice may depend on. Create `shared/StandalonePrincipal.java` with `public static final String NAME = "local";`, repoint `AuthorizationConfig`'s existing users at it (or delete `AuthorizationConfig` if that constant was its only content — check), and have `CallerPrincipal` reference `StandalonePrincipal.NAME`. `platform → shared` closes no loop.

**A static flag that cannot work.** An earlier revision made `standalone` a `static volatile` set from the constructor. Under `@WebMvcTest` the bean is never built — a plain `@Component` in `platform` is not in a web slice — so the flag stays `false`, `current()` throws, and **all three controller slice tests 500**. It also leaks across contexts within a JVM. So it is an **instance** bean, injected into the controllers by constructor, as written below.

**Round 3: this task previously specified that class three incompatible ways, and none of them compiled together.** Fix all of it before writing a line:

- It is an **instance** bean throughout. `current()` and `roles()` are both **instance** methods — the old draft had `roles()` `static` and `current()` not, which forces a reconciliation, and the lazy one (make both static) deletes the `standalone` field and with it the fail-closed guard this task exists to add.
- Every call site is `callerPrincipal.current()` on an injected field, **never** `CallerPrincipal.current()`. That includes the Step 1 test and the Step 5 controller edits, both of which used the static form.
- The Step 3 code block's imports were left over from the pre-fix draft. It imported `config.AuthorizationConfig` — **the exact import this task exists to remove** — which reintroduces `config → balance → platform → config` and fails `noCyclicPackages` at Step 6 where the plan predicts exit 0. It also referenced `StandalonePrincipal.NAME`, `@Component` and `Environment` with no imports for any of them. The corrected block is below.

**And constructor injection has a consequence this task stated but did not follow through.** The note above is right that a `platform` `@Component` is not in a web slice — so constructor-injecting it into the controllers turns that from a wrong value into `NoSuchBeanDefinitionException` at context start, breaking `LedgerControllerTest`, `BalanceControllerTest` and `AuditControllerTest`. Add `@MockitoBean CallerPrincipal` to each of those three classes, stubbed to return `"local"`, and list them in the Files block and the commit pathspec. (`BalanceControllerTest` loads both balance controllers; `AuditControllerTest` is included because `AuditController` is in the same slice configuration — verify each by running them, not by assuming which need it.)

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/platform/CallerPrincipal.java`
- Create: `src/main/java/com/ffroliva/tinyledger/shared/StandalonePrincipal.java` — `public static final String NAME = "local";`
- Modify or delete: `src/main/java/com/ffroliva/tinyledger/config/AuthorizationConfig.java` — repoint its users at `StandalonePrincipal.NAME`, and delete the file if that constant was its only content. Check before deleting.
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerController.java`, `src/main/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceController.java`
- Test: `src/test/java/com/ffroliva/tinyledger/platform/CallerPrincipalTest.java`
- Test: `src/test/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerControllerTest.java`, `src/test/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceControllerTest.java`, `src/test/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditControllerTest.java` — `@MockitoBean CallerPrincipal`, see above

**Interfaces:**
- Consumes: `TestJwt` from Task 3.
- Produces: `CallerPrincipal.current()` → `String`; `CallerPrincipal.roles()` → `Set<String>`.

- [ ] **Step 1: Write the failing test**

```java
package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CallerPrincipalTest {

    private static CallerPrincipal under(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new CallerPrincipal(environment);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test // §6.4: the caller is the JWT subject when there is one
    void theSubjectOfTheAuthenticatedJwtIsTheCaller() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .claim("roles", List.of("ledger:writer"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThat(under("full").current()).isEqualTo("alice");
        assertThat(under("full").roles()).containsExactly("ledger:writer");
    }

    @Test // standalone has no authentication at all, and the fixed principal is the contract
    void withNoAuthenticationTheCallerIsTheStandalonePrincipal() {
        assertThat(under("standalone").current()).isEqualTo("local");
        assertThat(under("standalone").roles()).isEmpty();
    }

    @Test // the fail-closed half: outside standalone, a missing principal is a refusal, not a default
    void withNoAuthenticationOutsideStandaloneItRefuses() {
        assertThatThrownBy(() -> under("full").current()).isInstanceOf(IllegalStateException.class);
    }
}
```

**The third test is the point of the council fix, and the old draft had no equivalent.** Without it, deleting
the `if (!standalone) throw ...` branch keeps the whole suite green and silently restores fail-open behaviour
— in a codebase whose `FailClosedGuard` asserts the opposite principle. Prove it the way AGENTS.md requires:
comment out that branch, watch this test go red, restore it, and report the red→green.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw -q test -Dtest=CallerPrincipalTest`
Expected: compilation failure — `CallerPrincipal` does not exist.

- [ ] **Step 3: Implement it**

```java
package com.ffroliva.tinyledger.platform;

import com.ffroliva.tinyledger.shared.StandalonePrincipal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * §6.4: the caller principal, read once at the web edge and passed down as a plain {@code String} so
 * no use case ever sees a framework type. In {@code standalone} there is no authentication, and the
 * fixed principal is the documented contract rather than a fallback.
 */
@Component
public class CallerPrincipal {

    private final boolean standalone;

    public CallerPrincipal(Environment environment) {
        this.standalone = environment.matchesProfiles("standalone") || environment.getActiveProfiles().length == 0;
    }

    public String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) return jwt.getToken().getSubject();
        // Council fix (P1-1): fail CLOSED outside standalone. Returning the fixed principal whenever
        // authentication is absent would turn a security misconfiguration into a *wrong answer* — the
        // ownership check would compare against whatever "local" happens to own — instead of a refusal.
        // FailClosedGuard asserts the same principle for profile configuration; this is its per-request
        // counterpart.
        if (!standalone) {
            throw new IllegalStateException("no authenticated principal outside the standalone profile");
        }
        return StandalonePrincipal.NAME;
    }

    /** Instance, not static — see the shape note above; a static sibling forces the guard's deletion. */
    public Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) return Set.of();
        List<String> roles = jwt.getToken().getClaimAsStringList("roles");
        return roles == null ? Set.of() : new LinkedHashSet<>(roles);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=CallerPrincipalTest`
Expected: PASS, **3** tests.

- [ ] **Step 5: Replace the hardcoded constant in both controllers**

In `LedgerController` and `BalanceController`, delete the `private static final String CALLER = ...` field, add `CallerPrincipal` as a **constructor parameter** stored in a private final field, and replace every use of `CALLER` with `callerPrincipal.current()` — the instance form, not the static one the old draft wrote. There are 5 uses in `LedgerController` (lines around 67, 70, 77, 89, 103) and 2 in `BalanceController` (around 81, 90) — locate them by content, and remove the now-unused `AuthorizationConfig` import from each.

Then add `@MockitoBean CallerPrincipal` to `LedgerControllerTest`, `BalanceControllerTest` and `AuditControllerTest`, stubbed `given(callerPrincipal.current()).willReturn("local")`, so the slices still start. Without it they fail at context startup with `NoSuchBeanDefinitionException`, not with a wrong value — see the note at the top of this task.

- [ ] **Step 6: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 with **131** tests (128 after Task 3, plus `CallerPrincipalTest`'s 3), and exit 0 with 29 ITs. Existing tests keep passing because in `standalone` — with no authentication present — `current()` returns `"local"`, exactly the old constant, and the three slices get it from the mock.

**`noCyclicPackages` is the check that matters here**, not the test count. This task's whole reason for moving the constant to `shared` is that `platform → config` would close a loop through `config → balance → platform`. If `HexagonalRulesTest` goes red, the `AuthorizationConfig` import survived somewhere — find it rather than relaxing the rule.

- [ ] **Step 7: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/platform/CallerPrincipal.java src/main/java/com/ffroliva/tinyledger/shared/StandalonePrincipal.java src/main/java/com/ffroliva/tinyledger/config src/test/java/com/ffroliva/tinyledger/platform/CallerPrincipalTest.java src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerController.java src/main/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceController.java src/test/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerControllerTest.java src/test/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceControllerTest.java src/test/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditControllerTest.java
git commit -m "feat: resolve the caller from the JWT subject, keeping the fixed principal in standalone"
```

---

### Task 5: Give the query ports a caller term

Verified asymmetry: `QueryStrongBalanceUseCase.strongBalance(String caller, AccountId)` and every write command already carry a caller, and `QueryAccountsUseCase.accountsOwnedBy(String owner)` is owner-scoped — but `QueryBalanceUseCase.balance(AccountId)` and `QueryHistoryUseCase.history(AccountId, HistoryQuery)` do not. Task 6's decorator has nothing to filter on until they do.

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/balance/application/port/in/QueryBalanceUseCase.java`, `QueryHistoryUseCase.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/balance/application/usecase/BalanceQueryService.java`, `HistoryQueryService.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceController.java`
- Test: `src/test/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceControllerTest.java`
- Test: `src/test/java/com/ffroliva/tinyledger/balance/adapter/in/events/LedgerEventsListenerTest.java` — calls `queryBalance.balance(account)` at line 42 and breaks on the signature change. Round 3 found it missing from this list (the commit pathspec does cover it, so only the map was wrong).

**Interfaces:**
- Consumes: `CallerPrincipal.current()` from Task 4.
- Produces: `QueryBalanceUseCase.balance(String caller, AccountId accountId)` → `Optional<BalanceView>`; `QueryHistoryUseCase.history(String caller, AccountId accountId, HistoryQuery query)` → `HistoryPage`.

- [ ] **Step 1: Change the two port signatures**

```java
public interface QueryBalanceUseCase {
    /** §6.4: the caller is part of the query — a read the caller may not make is not a read. */
    Optional<BalanceView> balance(String caller, AccountId accountId);
}
```

```java
public interface QueryHistoryUseCase {
    /** §6.4: the caller is part of the query. */
    HistoryPage history(String caller, AccountId accountId, HistoryQuery query);
}
```

- [ ] **Step 2: Thread it through the two services**

`BalanceQueryService.balance` and `HistoryQueryService.history` accept the new first parameter and **ignore it** — the authorization decision belongs to Task 6's decorator, not to the service. Add a one-line comment saying so on each, so the unused parameter does not read as an oversight:

```java
    @Override
    public Optional<BalanceView> balance(String caller, AccountId accountId) {
        // The caller is checked by the authorisation decorator at the port boundary (§6.4), not here —
        // this service answers the question, it does not decide who may ask it.
        Optional<BalanceView> cached = cache.get(accountId);
        if (cached.isPresent()) return cached;
        Optional<BalanceView> projected = projection.balance(accountId);
        projected.ifPresent(view -> cache.put(accountId, view));
        return projected;
    }
```

- [ ] **Step 3: Update the controller call sites**

In `BalanceController`, pass `CallerPrincipal.current()` as the first argument to `queryBalance.balance(...)` and `queryHistory.history(...)`.

- [ ] **Step 4: Update the existing tests' stubs, and prove the caller actually arrives (council fix P1-3)**

`BalanceControllerTest` stubs these ports with `any()` matchers in several places. Each `given(queryBalance.balance(any()))` becomes `given(queryBalance.balance(any(), any()))`, and likewise for `history`. This is a signature change, not a behaviour change — no assertion should need altering.

**Not every stub uses `any()`.** `BalanceControllerTest:74` stubs with an **exact argument** — `given(queryBalance.balance(new AccountId(ACCOUNT)))` — which needs a first argument, not a second `any()`: use `given(queryBalance.balance(anyString(), eq(new AccountId(ACCOUNT))))` or add the literal caller. And `LedgerEventsListenerTest:42` calls `queryBalance.balance(account)` directly. Both are compile errors, and both arrive immediately after the sentence above tells you nothing should need altering — so read the compiler, not the sentence.

But `any()` matchers mean **nothing in the suite would notice if the controller passed a literal `"local"`, an empty string, or `null` instead of `CallerPrincipal.current()`** — the services ignore the parameter by design, and `standalone`'s principal is always `"local"`, so every test would still pass while Task 6's decorator later authorises against the wrong value. Add one captor test:

```java
    @Test // the caller must be the resolved principal, not a literal — Task 6 authorises on this value
    void theResolvedCallerIsPassedToTheQuery() throws Exception {
        given(callerPrincipal.current()).willReturn("captain-nemo");
        ArgumentCaptor<String> caller = ArgumentCaptor.forClass(String.class);
        given(queryBalance.balance(any(), any())).willReturn(Optional.of(balanceView()));

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT)).andExpect(status().isOk());

        verify(queryBalance).balance(caller.capture(), any());
        assertThat(caller.getValue()).isEqualTo("captain-nemo");
    }
```

**Round 3: asserting `"local"` here would have defeated the test's own purpose.** `"local"` is exactly what a
hardcoded literal in the controller produces under `standalone`, so the original assertion passed against the
defect it was written to catch. Stub the `@MockitoBean CallerPrincipal` that Task 4 added to this class to
return a value nothing else in the system can produce, and assert **that**. Then the only way the test passes
is if the controller really asked `CallerPrincipal`.

Reuse whatever `balanceView()` fixture the class already has; if there is none, build a `BalanceView(new AccountId(ACCOUNT), Money.of("GBP", 5000), NOW, 3)` inline.

- [ ] **Step 5: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 with **132** tests (131 after Task 4, plus this task's captor test), and exit 0 with 29 ITs.

- [ ] **Step 6: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/balance src/test/java/com/ffroliva/tinyledger/balance
git commit -m "feat: carry the caller into the balance and history queries (m2 sibling)"
```

---

### Task 6: The authorization decorator

**Council fix (P0-1), REVISED BY EVIDENCE — reads authorise at the boundary, writes keep their in-service check.**

The history matters, because two earlier answers were wrong. The first draft decorated only the read ports, which would have left one §6.4 rule enforced in two places permanently — the CR14 drift. The correction was to move *everything* to the boundary (option (a)). Reading the code settles it against **both**:

```java
// RecordMovementService — the numbered steps are the service's own comments
List<LedgerEvent> history = store.read(accountId);                    // ①
if (history.isEmpty()) throw new AccountNotFoundException(accountId);  // ①b — the 404, before any authz
Account account = Account.rehydrate(history);                         // ②
if (!account.owner().equals(caller)) throw new OwnershipException(…); // ③
Optional<LedgerEvent> existing = store.findByMovementUid(movementUid);// ④ (after authz)
```

Line ①b is what actually earns the "no 403-instead-of-404 on writes" claim below — the write path answers
404 for an unknown account *before* it can answer 403, because an empty stream cannot be rehydrated.

Two facts follow, neither of which survives option (a):

1. **The write-side check authorises against the rehydrated aggregate — the event stream, the system of record.** A decorator runs *before* ①, so it cannot see the aggregate and must fall back to the read model. That is a change of **authority**, not merely of location: ownership would be decided by a projection rather than by the events. For a money movement that is a downgrade, and no test would show it.
2. **§6.3's authorise-before-idempotency is ③ before ④** — an ordering *inside* the service, which the code already satisfies and even comments. An earlier version of this task claimed the decorator must therefore sit outside the transactional one. **That was wrong**: §6.3 constrains authorisation relative to the idempotency lookup, not relative to `BEGIN`.

So: **do not touch `RecordMovementService` or `StrongBalanceService`.** Leave ③ where it is; it is authoritative and correctly ordered. Decorate only `QueryBalanceUseCase` and `QueryHistoryUseCase`, which have no aggregate to consult and for which the projection *is* the right source.

This also dissolves two other council P0s rather than patching them:

- **No `@Primary` collision.** `FullAdapterConfig:146,152` already declare `@Primary` for `OpenAccountUseCase` and `RecordMovementUseCase`; adding write decorators for those same types would have thrown `NoUniqueBeanDefinitionException` and **`full` would never have started**. The read decorators are different types, so there is no clash.
- **No 403-instead-of-404 on writes.** A boundary check cannot distinguish "absent" from "unowned", so option (a) would have turned an unknown account on the write path into a 403, contradicting §6.5. The in-service check keeps the correct 404.

**What this costs, stated honestly:** §6.4 currently reads as though a single decorator enforces ownership everywhere. It does not, and after this plan it still will not. **Amend §6.4 in the Plan 3 spec revision** to describe the split and why: writes and strong reads authorise in-service against the rehydrated aggregate because that is the authority and because §6.3 orders it there; projection-backed reads authorise at the port boundary because they have no aggregate. Recording that is what stops this becoming the next CR14 — the drift is only a defect while the document disagrees with the code.

Mirrors `TransactionalUseCases` exactly: package-private decorator classes in `config`, wired in the composition root, so `application` gains no framework annotation. **Throws `OwnershipException`, never Spring's `AccessDeniedException`** — measured on 2026-08-05, a Spring `AccessDeniedException` thrown from inside a controller invocation is claimed by `ErrorHandlingAdvice`'s catch-all and returned as an **opaque 500**, in all three observation modes, because `@ExceptionHandler` resolves it before `ExceptionTranslationFilter` can. `OwnershipException` returns 403 `/errors/forbidden` and was verified to keep doing so with Security on the classpath.

**Round 3 / decision: absent must not read as unowned.** The original `requireOwner` scanned
`accountsOwnedBy(caller)` and refused anything not in the list — including ids that **do not exist**, making
an unknown account a 403 where `spec.md:720` requires 404. That is the identical objection this task uses
below to reject option (a) for writes, so the argument condemned the read design it was justifying. And
`BalanceControllerTest:101` (`unknownAccountBalanceIsNotFound`) would not have caught it: it is a slice with
a mocked port, so the decorator is not in the path and the regression ships green.

The fix needs **no new adapter code**: `BalanceProjectionPort` already declares
`Optional<AccountView> account(AccountId)`, and `AccountView` already carries `owner`. Surface it on the
in-port so the decorator can reach it:

```java
public interface QueryAccountsUseCase {
    List<AccountView> accountsOwnedBy(String owner);

    /** §6.4/§6.5: one account, so authorisation can tell "not yours" from "not there". */
    Optional<AccountView> account(AccountId accountId);
}
```

`AccountsQueryService` implements it by delegating to `projection.account(accountId)` — one line. Confirm
both adapters' `account(...)` really is a single-row lookup rather than a filtered scan before relying on the
performance half of this argument.

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/config/AuthorizedUseCases.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/config/UseCaseConfig.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/balance/application/port/in/QueryAccountsUseCase.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/balance/application/usecase/AccountsQueryService.java`
- Test: `src/test/java/com/ffroliva/tinyledger/config/AuthorizedUseCasesTest.java`
- Test: `src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java` (Task 3's) — the wiring proofs in Step 7 go here

**Interfaces:**
- Consumes: `QueryBalanceUseCase.balance(String, AccountId)` and `QueryHistoryUseCase.history(String, AccountId, HistoryQuery)` from Task 5; `QueryAccountsUseCase.accountsOwnedBy(String)`; `OwnershipException(String, AccountId)`.
- Produces: `AuthorizedUseCases.Balances` and `AuthorizedUseCases.History`, package-private decorators.

- [ ] **Step 1: Write the failing test**

```java
package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.balance.application.port.in.AccountView;
import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.in.QueryAccountsUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthorizedUseCasesTest {

    private static final AccountId ACCOUNT = AccountId.random();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final Currency GBP = Currency.getInstance("GBP");

    private final QueryBalanceUseCase balances =
            (caller, id) -> Optional.of(new BalanceView(id, Money.of("GBP", 5000), NOW, 3));

    private final QueryHistoryUseCase histories = (caller, id, query) -> HistoryPage.empty();

    /** `account` answers for ACCOUNT only, so an unknown id exercises the absent branch. */
    private QueryAccountsUseCase ownedBy(String owner) {
        return new QueryAccountsUseCase() {
            @Override
            public List<AccountView> accountsOwnedBy(String o) {
                return owner.equals(o) ? List.of(new AccountView(ACCOUNT, "acc", owner, GBP, NOW)) : List.of();
            }

            @Override
            public Optional<AccountView> account(AccountId id) {
                return ACCOUNT.equals(id)
                        ? Optional.of(new AccountView(ACCOUNT, "acc", owner, GBP, NOW))
                        : Optional.empty();
            }
        };
    }

    @Test // §6.4: the owner reads their own balance
    void theOwnerIsAllowedThrough() {
        QueryBalanceUseCase authorized = new AuthorizedUseCases.Balances(balances, ownedBy("alice"));

        assertThat(authorized.balance("alice", ACCOUNT)).isPresent();
    }

    @Test // §6.4: and a stranger is refused with the catalogued 403, not a 500
    void aCallerWhoDoesNotOwnTheAccountIsRefused() {
        QueryBalanceUseCase authorized = new AuthorizedUseCases.Balances(balances, ownedBy("alice"));

        assertThatThrownBy(() -> authorized.balance("mallory", ACCOUNT))
                .isInstanceOf(OwnershipException.class);
    }

    @Test // §6.5: an account that does not exist is a 404 from the delegate, NOT a 403 from here
    void anUnknownAccountIsNotRefusedAsUnowned() {
        QueryBalanceUseCase authorized = new AuthorizedUseCases.Balances(balances, ownedBy("alice"));

        assertThatCode(() -> authorized.balance("alice", AccountId.random())).doesNotThrowAnyException();
    }

    @Test // History is a separate decorator and needs its own proof — Balances passing says nothing about it
    void historyAllowsTheOwnerAndRefusesAStranger() {
        QueryHistoryUseCase authorized = new AuthorizedUseCases.History(histories, ownedBy("alice"));

        assertThat(authorized.history("alice", ACCOUNT, HistoryQuery.first(10))).isNotNull();
        assertThatThrownBy(() -> authorized.history("mallory", ACCOUNT, HistoryQuery.first(10)))
                .isInstanceOf(OwnershipException.class);
    }
}
```

**The last two tests exist because round 3 found `History` had zero coverage in any form** — both original
unit tests instantiated `Balances`, and both wired proofs hit `/balance`. That matters more than it sounds:
forgetting `@Primary` on `authorizedBalance` fails *loudly* with `NoUniqueBeanDefinitionException`, but
omitting the `authorizedHistory` bean entirely leaves exactly one candidate, so the context starts clean and
`mallory` can page `alice`'s whole transaction history — the same data Task 6b exists to protect, down a
route Task 6b does not deny.

Adapt `HistoryPage.empty()` and `HistoryQuery.first(10)` to whatever factory methods those types actually
provide; read them rather than assuming these exist.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw -q test -Dtest=AuthorizedUseCasesTest`
Expected: compilation failure — `AuthorizedUseCases` does not exist.

- [ ] **Step 3: Implement the decorator**

```java
package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryPage;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryQuery;
import com.ffroliva.tinyledger.balance.application.port.in.QueryAccountsUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryHistoryUseCase;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.Optional;

/**
 * §6.4: authorisation is a use-case concern, applied at the port boundary in the composition root —
 * the same shape as {@link TransactionalUseCases}, and for the same reason: {@code application} carries
 * no framework annotations (ArchUnit), and a controller cannot make this decision because the ownership
 * half of the rule needs the read model.
 *
 * <p>It throws {@link OwnershipException}, never Spring's {@code AccessDeniedException}. Measured
 * 2026-08-05: a Spring denial thrown from inside a controller invocation is claimed by
 * {@code ErrorHandlingAdvice}'s catch-all and returned as an opaque 500, because {@code @ExceptionHandler}
 * resolves it before {@code ExceptionTranslationFilter} sees it — a correctly-refused request would look
 * like a server fault. {@code OwnershipException} carries {@code ErrorCode.FORBIDDEN} and answers 403.
 */
final class AuthorizedUseCases {

    private AuthorizedUseCases() {}

    /**
     * §6.5: absent and unowned are different answers. An account that does not exist is a 404, produced by
     * the delegate returning empty — not a 403. Only a real account with a different owner is refused.
     *
     * <p>This is also why it asks for one account rather than listing everything the caller owns: a
     * membership scan would both lose that distinction and put an owner-wide query in front of every single
     * balance and history read.
     */
    private static void requireOwner(QueryAccountsUseCase accounts, String caller, AccountId accountId) {
        Optional<AccountView> account = accounts.account(accountId);
        if (account.isEmpty()) return; // let the delegate answer 404
        if (!account.get().owner().equals(caller)) throw new OwnershipException(caller, accountId);
    }

    static class Balances implements QueryBalanceUseCase {
        private final QueryBalanceUseCase delegate;
        private final QueryAccountsUseCase accounts;

        Balances(QueryBalanceUseCase delegate, QueryAccountsUseCase accounts) {
            this.delegate = delegate;
            this.accounts = accounts;
        }

        @Override
        public Optional<BalanceView> balance(String caller, AccountId accountId) {
            requireOwner(accounts, caller, accountId);
            return delegate.balance(caller, accountId);
        }
    }

    static class History implements QueryHistoryUseCase {
        private final QueryHistoryUseCase delegate;
        private final QueryAccountsUseCase accounts;

        History(QueryHistoryUseCase delegate, QueryAccountsUseCase accounts) {
            this.delegate = delegate;
            this.accounts = accounts;
        }

        @Override
        public HistoryPage history(String caller, AccountId accountId, HistoryQuery query) {
            requireOwner(accounts, caller, accountId);
            return delegate.history(caller, accountId, query);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=AuthorizedUseCasesTest`
Expected: PASS, **5** tests.

- [ ] **Step 5: Wire it in the composition root**

In `UseCaseConfig`, the `queryBalance` and `queryHistory` beans must return the **concrete services** so the decorator can be injected unambiguously — the same trick the file already documents for `OpenAccountService` and `RecordMovementService`. Rename those two beans to return `BalanceQueryService` and `HistoryQueryService`, then add the decorating beans:

```java
    @Bean
    BalanceQueryService balanceQueries(BalanceProjectionPort projection, BalanceCachePort cache) {
        return new BalanceQueryService(projection, cache);
    }

    @Bean
    HistoryQueryService historyQueries(BalanceProjectionPort projection) {
        return new HistoryQueryService(projection);
    }

    @Bean
    @Primary
    QueryBalanceUseCase authorizedBalance(BalanceQueryService delegate, QueryAccountsUseCase accounts) {
        return new AuthorizedUseCases.Balances(delegate, accounts);
    }

    @Bean
    @Primary
    QueryHistoryUseCase authorizedHistory(HistoryQueryService delegate, QueryAccountsUseCase accounts) {
        return new AuthorizedUseCases.History(delegate, accounts);
    }
```

Add the `org.springframework.context.annotation.Primary` import. Note this is the `UseCaseConfig` return-type change the overnight queue predicted, and it is why finding **P9** (moving the shared beans into this file) was left for supervision.

- [ ] **Step 6: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 with **137** tests (132 after Task 5, plus this task's 5), and exit 0 with 29 ITs. Existing tests keep passing because `standalone`'s fixed principal `"local"` owns the accounts those tests create.

**Do not read this step as evidence the task worked.** Its own justification explains why it cannot fail: the
existing tests pass whether the decorator is wired, half-wired, or entirely absent, because `standalone` has
one principal and it owns everything. Step 7 is the only proof in this task. If you are short of time, do
Step 7 first and this second.

- [ ] **Step 7: Prove the decorator is actually IN THE REQUEST PATH (council fix P0-3)**

`AuthorizedUseCasesTest` constructs the decorator directly, so it passes whether or not the decorator is wired. **If the `@Primary` wiring is wrong — annotation forgotten, wrong bean injected — the controller calls the undecorated service, no authorization happens at all, and that unit test still goes green.** An earlier draft's only wiring proof was a Cucumber scenario carrying an escape hatch reading "if `standalone` cannot express two different callers, convert this to a `BalanceControllerTest` case instead" — and `standalone` has exactly **one** fixed principal (`AuthorizationConfig.STANDALONE_PRINCIPAL`), so that hatch was certain to trigger, and a controller-slice test stubs the port and exercises the mock rather than the wiring. The proof would have deleted itself.

Add to **`SecurityConfigIT`** (Task 3 Step 7) — that context has the real object graph *and* the real filter
chain, so these tests close both gaps at once:

```java
    @Test // §6.4: the decorator is wired, not merely written — mallory holds a valid token and is
    // still refused, which no unit test on AuthorizedUseCases could establish
    void aValidTokenForTheWrongOwnerIsForbidden() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc().perform(get("/api/v1/accounts/{a}/balance", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("mallory")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // the positive twin: a system that refuses everyone would satisfy the test above
    void theOwnerReadsHerOwnBalance() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc().perform(get("/api/v1/accounts/{a}/balance", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isOk());
    }

    @Test // History is a different bean; forgetting it fails SILENTLY, unlike Balances
    void aValidTokenForTheWrongOwnerCannotPageTheHistory() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc().perform(get("/api/v1/accounts/{a}/transactions", alicesAccount)
                        .header("Authorization", "Bearer " + TestJwt.token("mallory")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // §6.5: and an account nobody owns is still a 404, not a 403
    void anUnknownAccountIsNotFoundRatherThanForbidden() throws Exception {
        mvc().perform(get("/api/v1/accounts/{a}/balance", UUID.randomUUID())
                        .header("Authorization", "Bearer " + TestJwt.token("alice")))
                .andExpect(status().isNotFound());
    }
```

Implement `openAnAccountAs(String owner)` as a helper that POSTs `/api/v1/accounts` with that owner's token
and returns the created `accountUid`. Confirm the route in the third test against `openapi.yaml` — it is the
history/transactions path, not `/balance`.

**Two red→greens to run and report explicitly**, because these are the specific defects these tests exist to
catch and a passing test proves nothing about whether it *could* fail:

1. Remove `@Primary` from `authorizedBalance` — `aValidTokenForTheWrongOwnerIsForbidden` must fail.
2. Delete the `authorizedHistory` bean entirely — `aValidTokenForTheWrongOwnerCannotPageTheHistory` must
   fail. This is the one that fails silently in production, so it is the one most worth proving.

- [ ] **Step 8: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/config src/test/java/com/ffroliva/tinyledger/config src/main/java/com/ffroliva/tinyledger/balance/application/port/in/QueryAccountsUseCase.java src/main/java/com/ffroliva/tinyledger/balance/application/usecase/AccountsQueryService.java
git commit -m "feat: authorise reads at the port boundary, refusing with the catalogued 403"
```

---

### Task 6b: Deny the auditor endpoints in `full` until roles exist

**Council fix (security P0-B).** Verified in `PostgresAuditTrail`:

```java
StringBuilder sql = new StringBuilder(SELECT).append(" WHERE true");
if (query.accountId() != null) { sql.append(" AND account_id = ?"); … }
```

`accountUid` is **optional** on `GET /api/v1/audit/entries`. Omit it and the query is `WHERE true`, paging the **entire cross-account trail** — every account id, every amount, every reference. `GET /api/v1/accounts/{id}/events` then returns verbatim event payloads for any id so obtained. Spec §7 says these two operations are `ledger:auditor`-only, but this plan defers role checks to a follow-up. So after Task 3 makes `full` authenticated, **any** valid token — `alice`'s — has full auditor power over every customer's data. It also voids §6.5's "account UUIDs are unguessable" justification, because the trail hands them out.

Ownership decoration cannot fix this: an audit trail is deliberately not owner-scoped, and `AuditController` calls the out-port directly with no use-case seam to wrap (parked finding `m2`).

Until roles land, `full` must **refuse** these two operations rather than serve them:

- [ ] **Step 1: Write the failing test** — in **`SecurityConfigIT`**, assert `GET /api/v1/audit/entries` with a valid `alice` token returns **403** with `type` `/errors/forbidden` and content type `application/problem+json`, and the same for `GET /api/v1/accounts/{id}/events`.

**Assert the body, not just the status.** A chain-level `denyAll()` is enforced by `AuthorizationFilter`,
before `DispatcherServlet` — so `ErrorHandlingAdvice` never sees it, and Spring's default answer is an empty
body under MockMvc and `BasicErrorController`'s shape in a container, which echoes the request `path` that
§6.5 forbids crossing the boundary. **This assertion only passes because Task 3 registers
`SecurityProblemHandler` as the chain's `accessDeniedHandler`.** If you assert status alone, the test goes
green against an uncatalogued 403 and the plan's headline goal quietly fails here. If the body assertion
fails, the handler is not wired — fix that rather than weakening the test.

- [ ] **Step 2: Run it** — expect FAIL (currently 200 with the whole trail).
- [ ] **Step 3: Deny them in the chain.** In `SecurityConfig.fullChain`, before `anyRequest().authenticated()`:

```java
                .authorizeHttpRequests(auth -> auth
                        // §7: auditor operations are ledger:auditor-only. Roles arrive in the follow-up
                        // plan; until then `full` refuses rather than serving every customer's trail to
                        // any authenticated caller. standalone already answers these 501.
                        .requestMatchers("/api/v1/audit/**", "/api/v1/accounts/*/events").denyAll()
                        .anyRequest().authenticated())
```

- [ ] **Step 4: Run it** — expect PASS, and **prove the `standalone` half rather than asserting it in prose.**

"Confirm the 501 is unchanged" had nothing behind it: no feature file mentions `audit`, `/events`, `501` or
`not-available`, and `AuditControllerTest` is a `@WebMvcTest` slice with no filter chain at all. So applying
the two matchers to the shared builder — or to `standaloneChain` by mistake — leaves **every test in the
repository green** while `standalone` answers 403 instead of the contractual 501 (`openapi.yaml:296-321`).

Add a test to `SecurityConfigTest` (the `standalone` one, which runs under `verify`) asserting that
`GET /api/v1/audit/entries` unauthenticated returns **501** with `type` `/errors/not-available-in-standalone`.
Then flip it: temporarily move the `denyAll()` matchers into `standaloneChain`, watch that test go red, and
put them back. Report the red→green — that is what makes the two chains provably different rather than
assumed to be.
- [ ] **Step 5: Commit** with an explicit note that this is a **temporary denial**, to be replaced by a `ledger:auditor` role check in the follow-up plan — and add it to that plan's opening scope so it is not forgotten. A denial that outlives its reason becomes a mystery.

---

### Task 7: The `x-fapi-interaction-id` filter

Approved Open Banking item: echo the caller's `x-fapi-interaction-id` when present, mint one when absent, and put it in the MDC so `ErrorHandlingAdvice.traced()` can attach it to problem responses — that method already reads `traceId` from the MDC and the code comments say "Plan 3 wires the tracer".

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilter.java`
- Test: `src/test/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilterTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: a `Filter` bean; response header `x-fapi-interaction-id`; MDC key `traceId`.

- [ ] **Step 1: Write the failing test**

```java
package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FapiInteractionIdFilterTest {

    private final FapiInteractionIdFilter filter = new FapiInteractionIdFilter();

    @Test // OB: a caller-supplied correlation id is echoed unchanged
    void aSuppliedInteractionIdIsEchoed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-fapi-interaction-id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {};

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("x-fapi-interaction-id")).isEqualTo("abc-123");
    }

    @Test // and one is minted when the caller supplies none, so every response is correlatable
    void anAbsentInteractionIdIsMinted() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});

        String minted = response.getHeader("x-fapi-interaction-id");
        assertThat(minted).isNotBlank();
        assertThat(UUID.fromString(minted)).isNotNull();
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw -q test -Dtest=FapiInteractionIdFilterTest`
Expected: compilation failure — `FapiInteractionIdFilter` does not exist.

- [ ] **Step 3: Implement it**

```java
package com.ffroliva.tinyledger.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Open Banking: {@code x-fapi-interaction-id} correlates a call across the ASPSP. Echo the caller's
 * value when they supply one so their logs and ours agree; mint one when they do not, so every response
 * is correlatable either way. Also placed in the MDC as {@code traceId}, which is the key
 * {@link ErrorHandlingAdvice} already reads when decorating a problem response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FapiInteractionIdFilter extends OncePerRequestFilter {

    static final String HEADER = "x-fapi-interaction-id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String interactionId = supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
        response.setHeader(HEADER, interactionId);
        MDC.put("traceId", interactionId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Container threads are pooled: leaving this set would attribute the next request's logs
            // to this one.
            MDC.remove("traceId");
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=FapiInteractionIdFilterTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Prove the filter is registered and that it does its job (council fix P1-4)**

The two tests above call `filter.doFilter(...)` directly, so **removing `@Component` — and with it the filter from the chain entirely — would break neither.** And the filter's stated purpose is to populate the MDC `traceId` that `ErrorHandlingAdvice.traced()` attaches to problem responses; nothing yet asserts a `traceId` ever reaches a response. The feature could be wholly inert and green.

**`@Order(Ordered.HIGHEST_PRECEDENCE)` is load-bearing, not tidiness.** A `@Component Filter` registers at
`Ordered.LOWEST_PRECEDENCE`, while `springSecurityFilterChain` registers at
`SecurityProperties.DEFAULT_FILTER_ORDER = -100`. Without the annotation, every Task 3 401 and every Task 6b
403 is written by the security chain **before** this filter runs — no `x-fapi-interaction-id` header, and an
empty MDC, so `SecurityProblemHandler` has no `traceId` to attach. The filter's stated purpose ("every
response is correlatable either way") would be false for precisely the error responses FAPI requires the
header on.

Add to `BalanceControllerTest` (a slice is sufficient for registration — unlike security, filter registration
is visible at that level, because `WebMvcTypeExcludeFilter`'s default includes contain `jakarta.servlet.Filter`):

```java
    @Test // the filter is in the chain, not merely written — deleting @Component turns this red
    void everyResponseCarriesAnInteractionId() throws Exception {
        given(queryAccounts.accountsOwnedBy(any())).willReturn(List.of());

        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists("x-fapi-interaction-id"));
    }

    @Test // §6.5/§6.6: the body's traceId must BE the header, or the correlation is imaginary
    void theProblemBodyTraceIdIsTheInteractionId() throws Exception {
        given(queryBalance.balance(any(), any())).willThrow(new RuntimeException("boom"));

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT)
                        .header("x-fapi-interaction-id", "abc-123"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("x-fapi-interaction-id", "abc-123"))
                .andExpect(jsonPath("$.traceId").value("abc-123"));
    }
```

**Why the second test supplies an id and compares the two values:** the original pair asserted
`header().exists(...)` in one test and `jsonPath("$.traceId").exists()` in another. Those are independently
satisfiable — an implementation that minted *two different* UUIDs, one for the header and one for the MDC,
passes both while the correlation the filter exists for is silently broken. Supplying `abc-123` also proves
the echo path through the real chain, which the direct-`doFilter` unit test cannot.

Add one more to `SecurityConfigIT`, because ordering is only observable where there is a security chain:

```java
    @Test // the ordering fix: a 401 is written by the security chain, and must still be correlatable
    void anUnauthenticatedRefusalStillCarriesTheInteractionId() throws Exception {
        mvc().perform(get("/api/v1/accounts").header("x-fapi-interaction-id", "abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("x-fapi-interaction-id", "abc-123"))
                .andExpect(jsonPath("$.traceId").value("abc-123"));
    }
```

Prove the ordering the way AGENTS.md requires: remove `@Order`, watch that last test go red, restore it, and
report the red→green. Nothing else in the suite can detect the ordering.

- [ ] **Step 6: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 and exit 0. This task adds **4** unit tests (2 in `FapiInteractionIdFilterTest`, 2 in `BalanceControllerTest`) and **1** IT. Report both absolute counts from XML.

- [ ] **Step 7: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilter.java src/test/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilterTest.java src/test/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceControllerTest.java src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java
git commit -m "feat: echo-or-mint x-fapi-interaction-id and expose it as the MDC traceId"
```

---

## Council audit — lineage

This plan was revised on 2026-08-05 after a council review. All P0 and P1 findings are **folded into the
tasks above rather than listed separately** — they are the spec now, not an appendix. Findings, method and
the false positives kept for auditability: `.claude/audits/2026-08-05/council-plan3-orchestrator.md`.

What the review changed, so a reader can see which parts of this plan are load-bearing corrections:

| Finding | Task | What was wrong |
|---|---|---|
| **P0-1** | 6 | Decorating only the read ports would have left §6.4 enforced in two places permanently. Option (a) — move all five to the boundary — was chosen, then **reversed by evidence**: the write check authorises against the rehydrated aggregate, and `FullAdapterConfig:146,152` already `@Primary` those types, so boundary write decorators would have thrown `NoUniqueBeanDefinitionException` and `full` would never have started. The plan implements the **hybrid** Task 6 describes: reads at the boundary, writes and strong reads in-service. Round 3 re-verified the reversal on every count. |
| **P0-2** | 3 | `.jwt(jwt -> {})` with no issuer and no decoder — `full` would not have booted, and neither suite could see it, since no IT makes an HTTP call. |
| **P0-3** | 6 | The only proof the decorator was *wired* was a test the plan pre-authorised deleting, and `standalone`'s single principal guaranteed that hatch would trigger. |
| **P1-1** | 4 | `CallerPrincipal` failed **open**, returning the fixed principal whenever authentication was absent — in a codebase with a `FailClosedGuard` asserting the opposite. |
| **P1-3** | 5 | `any()` stubs meant a hardcoded caller would have passed every test, and Task 6 authorises on that value. |
| **P1-4** | 7 | Both filter tests called `doFilter` directly, so deleting `@Component` broke nothing, and no test asserted `traceId` ever reached a response. |
| **Task 2 rewrite** | 2 | The original rationale was factually wrong: `@Min(1)` on `MovementAmount` makes `Account`'s guard unreachable over HTTP, and the `IllegalArgumentException` the blanket mapping actually carries comes from `Currency.getInstance` on a well-formed-but-unknown code. |

**Caveat on the review itself:** the four independent advisors (security-auditor, staff-reviewer,
code-architect, silent-failure-hunter) all terminated on API 529 before reporting. The findings above are
an orchestrator pass across the same four lenses — weaker, because one reviewer has correlated blind
spots, which is the reason the multi-agent pattern exists. The advisors are to be re-run against **this
revised plan**; anything they add lands here as a further revision.

## Follow-up plans, named so they are not forgotten

1. **DPoP and FAPI 2 hardening** — a proof-minting harness first, then a *positive* end-to-end DPoP test, then Keycloak FAPI client policies, PAR, PKCE S256, `private_key_jwt`. Blocking fact: the platform is proven to refuse `cnf`-bound tokens on the bearer path but never proven to accept them on the DPoP path.
2. **Admin on-behalf-of** — spec revision to v3.9 first, then `ledger:admin`, `actor` on every event, the `audit_entries.actor` column, and scenarios P9/N13.
3. **Role checks (`ledger:reader`/`ledger:writer`/`ledger:auditor`)** — Task 6 enforces the ownership half of §6.4's rule; the role half needs the Keycloak realm and `full`-profile integration tests, so it belongs with the realm work.
4. **The audit module's missing seam** — parked finding `m2`: `AuditController` calls the out-port directly, so there is no `QueryAuditTrailUseCase` for an authorization decorator to wrap.
5. **Malformed-cursor error code** — Task 2 leaves a bad cursor as a 500. It needs its own `ErrorCode` and to be thrown where the cursor is parsed.
6. **Ponytail P1, P8, P9** — money-path SQL, parked CR9, and the profile bean composition Task 6 already disturbs.
