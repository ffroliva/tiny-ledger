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
- `./mvnw -q verify` must exit 0 and start **ZERO** containers. `./mvnw -q verify -Pit` must exit 0 with **at least 26** ITs. Baseline before this plan: 122 unit tests, 26 ITs, both green at `cd6336c`.
- Count tests from surefire **XML**, never the `.txt` reports — `.txt` reports `Tests run: 0` for `@Nested` classes and undercounts by 10.
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
- `src/test/java/com/ffroliva/tinyledger/testsupport/TestJwt.java` — mints a locally-signed JWT and exposes the matching `JwtDecoder`.

**Modified**
- The six existing exceptions, to extend `TinyLedgerException`.
- `platform/ErrorHandlingAdvice.java` — six handlers collapse to one.
- `ledger/domain/Account.java` — throw `InvalidAmountException` instead of `IllegalArgumentException` for the amount rule.
- `balance/application/port/in/QueryBalanceUseCase.java`, `QueryHistoryUseCase.java` — gain a caller term.
- `balance/application/usecase/BalanceQueryService.java`, `HistoryQueryService.java` — accept and pass it.
- `config/UseCaseConfig.java` — return types change where the decorator wraps.
- `balance/adapter/in/web/BalanceController.java`, `ledger/adapter/in/web/LedgerController.java` — drop the hardcoded `CALLER`.
- `pom.xml` — add `spring-boot-starter-security`.

---

### Task 0: One integration context, and a CI that actually runs the integration tests

Do this **first**. Every later task adds tests, and adding them to the wrong test topology gets more
expensive with each one. Rationale in full: `docs/adr/0003-test-topology-and-ci-parallelisation.md`.

**Files:**
- Modify: `src/test/java/com/ffroliva/tinyledger/contract/EventStoreContract.java` (class → interface with default methods)
- Modify: `src/test/java/com/ffroliva/tinyledger/ledger/adapter/out/postgres/PostgresEventStoreIT.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java`
- Modify: `.github/workflows/ci.yml`

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
`@SpringBootTest`, `@ActiveProfiles` and `@DynamicPropertySource` entirely. Keep the `auto-startup=false`
property **only if** it is still needed once there is one context — with a single context there is a
single listener, so it should become unnecessary. If you delete it, run the full IT suite three times
and confirm `KafkaAuditModuleIT` is stable before concluding it was.

- [ ] **Step 2: Verify the context count actually dropped**

Run: `./mvnw -q verify -Pit -Dspring.test.context.cache.maxSize=1`

Expected: exit 0, 26 ITs. Forcing the cache to hold one context makes a surviving fork obvious — with two
contexts the suite thrashes, reloading on every class switch, and the run gets dramatically slower or
fails. This is the falsifiable check; a plain green run proves nothing about the count.

- [ ] **Step 3: Split CI by infrastructure need**

`.github/workflows/ci.yml` today is a single job that runs `spotless:check`, `./mvnw -q verify` and the
docs-governance script — and **never runs `-Pit`**. The 26 integration tests are gated by nothing, so the
standing assumption that "CI covers anything missed locally" is currently false.

Replace it with three jobs. `gate` runs the second-scale checks and both others depend on it, so a
formatting slip never burns container minutes. `unit` needs no Docker — `verify` is asserted to start zero
containers. `integration` is the only job paying for the stack, and runs in parallel with `unit`:

```yaml
jobs:
  gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: corretto, java-version: '25', cache: maven }
      - run: ./mvnw -q spotless:check
      - run: python scripts/ci/check_docs_governance.py

  unit:
    needs: gate
    runs-on: ubuntu-latest   # no Docker required — `verify` starts zero containers by design
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: corretto, java-version: '25', cache: maven }
      - run: ./mvnw -q verify

  integration:
    needs: gate
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: corretto, java-version: '25', cache: maven }
      - run: ./mvnw -q verify -Pit
```

**Do not shard `integration` further.** CI bills per minute summed across runners, and each shard re-pays
checkout, Maven resolution, code generation, compilation, image pull and container start — for this stack
the container start alone dominates. A second shard only pays for itself if it removes more execution time
than that fixed cost, which the current 26-test suite does not. Revisit against the arithmetic in ADR 0003
when the integration suite grows, not before.

- [ ] **Step 4: Commit**

```bash
./mvnw -q spotless:apply
git add src/test/java/com/ffroliva/tinyledger/contract/EventStoreContract.java src/test/java/com/ffroliva/tinyledger/ledger/adapter/out/postgres/PostgresEventStoreIT.java src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java .github/workflows/ci.yml docs/adr/0003-test-topology-and-ci-parallelisation.md
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
- Produces: `ErrorCode` (enum constants `INVALID_AMOUNT`, `CURRENCY_MISMATCH`, `ACCOUNT_NOT_FOUND`, `FORBIDDEN`, `IDEMPOTENCY_CONFLICT`, `VERSION_CONFLICT`, `INSUFFICIENT_FUNDS`, `NOT_AVAILABLE_IN_STANDALONE`, `RATE_LIMIT_EXCEEDED`; accessors `int status()`, `String type()`, `String messageKey()`); `TinyLedgerException` (`ErrorCode code()`, `Object[] args()`).

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
}
```

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
    CURRENCY_MISMATCH(422, "/errors/currency-mismatch", "Currency mismatch"),
    INSUFFICIENT_FUNDS(422, "/errors/insufficient-funds", "Insufficient funds"),
    ACCOUNT_NOT_FOUND(404, "/errors/account-not-found", "Account not found"),
    FORBIDDEN(403, "/errors/forbidden", "Forbidden"),
    IDEMPOTENCY_CONFLICT(409, "/errors/idempotency-conflict", "Idempotency conflict"),
    VERSION_CONFLICT(409, "/errors/version-conflict", "Version conflict"),
    RATE_LIMIT_EXCEEDED(429, "/errors/rate-limit-exceeded", "Rate limit exceeded"),
    NOT_AVAILABLE_IN_STANDALONE(501, "/errors/not-available-in-standalone", "Not available in standalone");

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
Expected: PASS, 2 tests.

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
Expected: exit 0, 122+2 tests, **zero** test edits required. `LedgerControllerTest.wrongOwnerIsForbidden` (403), `unknownAccountIsNotFound` (404) and `concurrencyConflictIsVersionConflict` (409) passing unchanged is the evidence that the collapse preserved behaviour.

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

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/shared/error/InvalidAmountException.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/shared/Money.java` (validate the currency code in `of`)
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/domain/Account.java` (the `amount must be positive` throw — defence in depth for non-HTTP callers such as Plan 4's CLI, not the HTTP path)
- Modify: `src/main/java/com/ffroliva/tinyledger/platform/ErrorHandlingAdvice.java`
- Test: `src/test/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerControllerTest.java`

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
                .andExpect(jsonPath("$.type").doesNotExist());
    }
```

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

In `Money.of`, wrap the JDK lookup so an unknown ISO code becomes a catalogued failure instead of a bare `IllegalArgumentException`:

```java
    public static Money of(String currencyCode, long minorUnits) {
        try {
            return new Money(Currency.getInstance(currencyCode), minorUnits);
        } catch (IllegalArgumentException e) {
            // §6.5: a well-formed but unknown ISO code (the OpenAPI pattern ^[A-Z]{3}$ admits "ZZZ",
            // so bean validation passes it through and the JDK is what refuses it). Typed here rather
            // than in the mapper so every caller — web, CLI, tests — gets the same catalogued answer.
            throw new InvalidAmountException("unknown currency code: " + currencyCode);
        }
    }
```

`Money` is in `shared`, and `shared.error` is framework-free, so nothing about the ArchUnit rules changes — verify that in Step 6 rather than assuming it.

- [ ] **Step 5: Convert the domain guard too, then remove the blanket mapping**

In `Account.java`, replace the amount guard's `IllegalArgumentException` with `InvalidAmountException`. This one is **defence in depth**, not the HTTP path — `@Min(1)` on the generated `MovementAmount` already rejects a negative before the use case runs. It matters for Plan 4's CLI and for direct service callers.

Leave `Account.java`'s `"empty stream"` `IllegalArgumentException` and its `IllegalStateException` **alone**: those are bug signals, not catalogued business errors, and they should surface as 500s.

Then in `ErrorHandlingAdvice.malformed()`, delete `IllegalArgumentException.class` from the `@ExceptionHandler({...})` list. Keep every other entry — those are Spring's own request-binding failures and are genuinely 400s.

- [ ] **Step 6: Run the affected suites**

Run: `./mvnw -q test -Dtest='LedgerControllerTest+AccountTest+HexagonalRulesTest+MoneyTest'`
Expected: PASS, including **`unknownCurrencyCodeIsBadRequest` staying 400** — that test is the real proof of this task, because it is the one the blanket mapping was carrying. If it turns 500, Step 4 was missed or `Money.of` is not on that path; do **not** weaken the test.

- [ ] **Step 7: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 and exit 0, 26 ITs. Malformed-cursor behaviour changes from 400 to 500 here; that is the correct intermediate state and Task 5's own error code is out of scope for this plan (recorded as a follow-up).

- [ ] **Step 8: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/shared/error/InvalidAmountException.java src/main/java/com/ffroliva/tinyledger/ledger/domain/Account.java src/main/java/com/ffroliva/tinyledger/platform/ErrorHandlingAdvice.java src/test/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerControllerTest.java
git commit -m "fix: type the amount rule so a stray IllegalArgumentException stops claiming to be one (CR12)"
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

- [ ] **Step 2: Run the suite to see the documented failure**

Run: `./mvnw -q verify`
Expected: FAIL, **21 failures**, all `CucumberTest`, all `expected: 201 but was: 401`. Seeing exactly this confirms the environment matches the experiment before any configuration is written. If the number differs, stop and investigate rather than proceeding.

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
    SecurityFilterChain fullChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }
}
```

**Council fix (P0-2).** `.jwt(jwt -> {})` builds nothing on its own: Spring needs an issuer or a `JwtDecoder` bean, and an earlier draft of this task supplied neither. A real `full` boot would have failed at context startup, and **nothing in the suite would have caught it** — `SecurityConfigTest` activates only `standalone`, and it is verified that **no integration test makes an HTTP call** (all 26 are adapter-level). So also add to `src/main/resources/application-full.properties`:

```properties
# §6.4: the resource server's trust anchor. FailClosedGuard treats this key as a full-mode marker —
# its presence under the standalone profile is a refusal to start, not a warning.
spring.security.oauth2.resourceserver.jwt.issuer-uri=${LEDGER_ISSUER_URI:http://localhost:8081/realms/tiny-ledger}
```

That default points at the Keycloak service added to `docker-compose.yml` in the follow-up plan; the env var lets a real deployment override it. Tests never reach it, because Step 6's `full` test supplies a `JwtDecoder` bean directly.

- [ ] **Step 4: Run the suite again**

Run: `./mvnw -q verify`
Expected: exit 0, 124 tests (122 + Task 1's 2). The 21 Cucumber scenarios pass because `standalone` permits all and CSRF is disabled. **If they fail with 403 rather than 401, the `csrf.disable()` line is missing** — that is the exact failure the experiment recorded.

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
public final class TestJwt {

    private static final RSAKey KEY = generate();

    private TestJwt() {}

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (Exception e) {
            throw new IllegalStateException("could not generate the test key", e);
        }
    }

    public static JwtDecoder decoder() {
        try {
            return NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("could not build the test decoder", e);
        }
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

    @TestConfiguration
    static class TrustTheTestKey {
        @Bean
        JwtDecoder jwtDecoder() {
            return TestJwt.decoder();
        }
    }
}
```

- [ ] **Step 7: Prove the `full` chain boots and actually refuses (council fix P0-2)**

Add to `SecurityConfigTest` a nested class activating `full`. Without this, the profile that carries the entire security posture has **no test at all**:

```java
    @Nested
    @SpringBootTest(classes = TinyLedgerApplication.class)
    @ActiveProfiles("full")
    @Import(TrustTheTestKey.class)
    class FullProfile {

        @Autowired
        private WebApplicationContext fullContext;

        @Test // the context starting at all is half the assertion — .jwt(...) needs a decoder to exist
        void anUnauthenticatedRequestIsRefused() throws Exception {
            MockMvcBuilders.webAppContextSetup(fullContext)
                    .apply(SecurityMockMvcConfigurers.springSecurity())
                    .build()
                    .perform(get("/api/v1/accounts"))
                    .andExpect(status().isUnauthorized());
        }

        @Test // and a valid token gets through, so the refusal above is not just "everything 401s"
        void aValidTokenIsAccepted() throws Exception {
            MockMvcBuilders.webAppContextSetup(fullContext)
                    .apply(SecurityMockMvcConfigurers.springSecurity())
                    .build()
                    .perform(get("/api/v1/accounts").header("Authorization", "Bearer " + TestJwt.token("alice")))
                    .andExpect(status().isOk());
        }
    }
```

The second test matters as much as the first: a chain that rejects *everything* would satisfy the 401 assertion while being entirely broken.

This `full` context needs the infrastructure beans, so it will pull in Postgres/Redis/Kafka wiring. If it cannot start without containers, move this nested class into a new `SecurityConfigIT` extending `AbstractIntegrationTest` and run it under `-Pit` instead — **do not delete it and do not downgrade it to a slice test**, because a `@WebMvcTest` slice was proven unable to detect security misconfiguration in this repo. Record which of the two you used and why.

- [ ] **Step 8: Run it**

Run: `./mvnw -q test -Dtest=SecurityConfigTest` (or `./mvnw -q verify -Pit -Dit.test=SecurityConfigIT`)
Expected: PASS, including both `full` cases.

- [ ] **Step 9: Commit**

```bash
./mvnw -q spotless:apply
git add pom.xml src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java src/main/resources/application-full.properties src/test/java/com/ffroliva/tinyledger/testsupport/TestJwt.java src/test/java/com/ffroliva/tinyledger/config/SecurityConfigTest.java
git commit -m "feat: add Spring Security, configured per profile rather than excluded in standalone"
```

---

### Task 4: A real caller principal

Both controllers currently hardcode `private static final String CALLER = AuthorizationConfig.STANDALONE_PRINCIPAL;`. In `full` the caller must be the JWT subject.

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/platform/CallerPrincipal.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerController.java`, `src/main/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceController.java`
- Test: `src/test/java/com/ffroliva/tinyledger/platform/CallerPrincipalTest.java`

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

        assertThat(CallerPrincipal.current()).isEqualTo("alice");
        assertThat(CallerPrincipal.roles()).containsExactly("ledger:writer");
    }

    @Test // standalone has no authentication at all, and the fixed principal is the contract
    void withNoAuthenticationTheCallerIsTheStandalonePrincipal() {
        assertThat(CallerPrincipal.current()).isEqualTo("local");
        assertThat(CallerPrincipal.roles()).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./mvnw -q test -Dtest=CallerPrincipalTest`
Expected: compilation failure — `CallerPrincipal` does not exist.

- [ ] **Step 3: Implement it**

```java
package com.ffroliva.tinyledger.platform;

import com.ffroliva.tinyledger.config.AuthorizationConfig;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * §6.4: the caller principal, read once at the web edge and passed down as a plain {@code String} so
 * no use case ever sees a framework type. In {@code standalone} there is no authentication, and the
 * fixed principal is the documented contract rather than a fallback.
 */
@Component
public class CallerPrincipal {

    private static volatile boolean standalone;

    CallerPrincipal(Environment environment) {
        // Read once at startup rather than per request; the profile cannot change at runtime.
        standalone = environment.matchesProfiles("standalone") || environment.getActiveProfiles().length == 0;
    }

    public static String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) return jwt.getToken().getSubject();
        // Council fix (P1-1): fail CLOSED outside standalone. Returning the fixed principal whenever
        // authentication is absent would turn a security misconfiguration into a *wrong answer* — the
        // ownership check would then compare against whatever "local" happens to own — instead of a
        // refusal. FailClosedGuard asserts the same principle for profile configuration; this is its
        // per-request counterpart.
        if (!standalone) {
            throw new IllegalStateException("no authenticated principal outside the standalone profile");
        }
        return AuthorizationConfig.STANDALONE_PRINCIPAL;
    }

    public static Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) return Set.of();
        List<String> roles = jwt.getToken().getClaimAsStringList("roles");
        return roles == null ? Set.of() : new LinkedHashSet<>(roles);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=CallerPrincipalTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Replace the hardcoded constant in both controllers**

In `LedgerController` and `BalanceController`, delete the `private static final String CALLER = ...` field and replace every use of `CALLER` with `CallerPrincipal.current()`. There are 5 uses in `LedgerController` (lines around 67, 70, 77, 89, 103) and 2 in `BalanceController` (around 81, 90) — locate them by content, and remove the now-unused `AuthorizationConfig` import from each.

- [ ] **Step 6: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 and exit 0. Existing tests keep passing because with no authentication present `current()` returns `"local"`, exactly the old constant.

- [ ] **Step 7: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/platform/CallerPrincipal.java src/test/java/com/ffroliva/tinyledger/platform/CallerPrincipalTest.java src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerController.java src/main/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceController.java
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

But `any()` matchers mean **nothing in the suite would notice if the controller passed a literal `"local"`, an empty string, or `null` instead of `CallerPrincipal.current()`** — the services ignore the parameter by design, and `standalone`'s principal is always `"local"`, so every test would still pass while Task 6's decorator later authorises against the wrong value. Add one captor test:

```java
    @Test // the caller must be the resolved principal, not a literal — Task 6 authorises on this value
    void theResolvedCallerIsPassedToTheQuery() throws Exception {
        ArgumentCaptor<String> caller = ArgumentCaptor.forClass(String.class);
        given(queryBalance.balance(any(), any())).willReturn(Optional.of(balanceView()));

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT)).andExpect(status().isOk());

        verify(queryBalance).balance(caller.capture(), any());
        assertThat(caller.getValue()).isEqualTo("local");
    }
```

Reuse whatever `balanceView()` fixture the class already has; if there is none, build a `BalanceView(new AccountId(ACCOUNT), Money.of("GBP", 5000), NOW, 3)` inline.

- [ ] **Step 5: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 and exit 0.

- [ ] **Step 6: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/balance src/test/java/com/ffroliva/tinyledger/balance
git commit -m "feat: carry the caller into the balance and history queries (m2 sibling)"
```

---

### Task 6: The authorization decorator

**Council fix (P0-1) — this task moves ALL ownership checks to the boundary, not just the read ones.** An earlier draft decorated only `QueryBalanceUseCase` and `QueryHistoryUseCase`. But `RecordMovementService.java:61` and `StrongBalanceService.java:28` **already check ownership in-service**:

```java
if (!account.owner().equals(caller)) throw new OwnershipException(caller, accountId);
```

Shipping the draft would have left one §6.4 rule enforced in two different places permanently — writes inside the service, reads outside it — which is precisely the spec-versus-code drift the Plan 2 close-out spent a docs pass correcting (CR14). The user chose **option (a)**: one mechanism at the port boundary, matching what §6.4 already promises.

So this task also **moves** the two in-service checks into decorators. Concretely: delete the `if (!account.owner().equals(caller))` line from `RecordMovementService` and `StrongBalanceService`, add `Movements` and `StrongBalance` decorators alongside the `Balances` and `History` ones below, and update `RecordMovementServiceTest` and `StrongBalanceServiceTest` — those currently assert the service throws `OwnershipException`, and after this it does not. **Move those assertions into `AuthorizedUseCasesTest` rather than deleting them**; losing them would drop real coverage. Note §6.3 ordering while you do it: the ownership check must still run *before* the idempotency lookup, so the authorization decorator must sit **outside** the transactional one in the wiring order.

Mirrors `TransactionalUseCases` exactly: package-private decorator classes in `config`, wired in the composition root, so `application` gains no framework annotation. **Throws `OwnershipException`, never Spring's `AccessDeniedException`** — measured on 2026-08-05, a Spring `AccessDeniedException` thrown from inside a controller invocation is claimed by `ErrorHandlingAdvice`'s catch-all and returned as an **opaque 500**, in all three observation modes, because `@ExceptionHandler` resolves it before `ExceptionTranslationFilter` can. `OwnershipException` returns 403 `/errors/forbidden` and was verified to keep doing so with Security on the classpath.

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/config/AuthorizedUseCases.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/config/UseCaseConfig.java`
- Test: `src/test/java/com/ffroliva/tinyledger/config/AuthorizedUseCasesTest.java`

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

    private final QueryBalanceUseCase delegate =
            (caller, id) -> Optional.of(new BalanceView(id, Money.of("GBP", 5000), NOW, 3));

    private QueryAccountsUseCase ownedBy(String owner) {
        return o -> owner.equals(o) ? List.of(new AccountView(ACCOUNT, "acc", owner, GBP, NOW)) : List.of();
    }

    @Test // §6.4: the owner reads their own balance
    void theOwnerIsAllowedThrough() {
        QueryBalanceUseCase authorized = new AuthorizedUseCases.Balances(delegate, ownedBy("alice"));

        assertThat(authorized.balance("alice", ACCOUNT)).isPresent();
    }

    @Test // §6.4: and a stranger is refused with the catalogued 403, not a 500
    void aCallerWhoDoesNotOwnTheAccountIsRefused() {
        QueryBalanceUseCase authorized = new AuthorizedUseCases.Balances(delegate, ownedBy("alice"));

        assertThatThrownBy(() -> authorized.balance("mallory", ACCOUNT))
                .isInstanceOf(OwnershipException.class);
    }
}
```

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

    private static void requireOwner(QueryAccountsUseCase accounts, String caller, AccountId accountId) {
        boolean owns = accounts.accountsOwnedBy(caller).stream()
                .anyMatch(view -> view.accountId().equals(accountId));
        if (!owns) throw new OwnershipException(caller, accountId);
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
Expected: PASS, 2 tests.

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
Expected: exit 0 and exit 0. Existing tests keep passing because `standalone`'s fixed principal `"local"` owns the accounts those tests create.

- [ ] **Step 7: Prove the decorator is actually IN THE REQUEST PATH (council fix P0-3)**

`AuthorizedUseCasesTest` constructs the decorator directly, so it passes whether or not the decorator is wired. **If the `@Primary` wiring is wrong — annotation forgotten, wrong bean injected — the controller calls the undecorated service, no authorization happens at all, and that unit test still goes green.** An earlier draft's only wiring proof was a Cucumber scenario carrying an escape hatch reading "if `standalone` cannot express two different callers, convert this to a `BalanceControllerTest` case instead" — and `standalone` has exactly **one** fixed principal (`AuthorizationConfig.STANDALONE_PRINCIPAL`), so that hatch was certain to trigger, and a controller-slice test stubs the port and exercises the mock rather than the wiring. The proof would have deleted itself.

Add to the `FullProfile` nested class created in Task 3 Step 7 — that context has the real object graph *and* the real filter chain, so this one test closes both gaps:

```java
        @Test // §6.4: the decorator is wired, not merely written — mallory holds a valid token and is
        // still refused, which no unit test on AuthorizedUseCases could establish
        void aValidTokenForTheWrongOwnerIsForbidden() throws Exception {
            UUID alicesAccount = openAnAccountAs("alice");

            MockMvcBuilders.webAppContextSetup(fullContext)
                    .apply(SecurityMockMvcConfigurers.springSecurity())
                    .build()
                    .perform(get("/api/v1/accounts/{a}/balance", alicesAccount)
                            .header("Authorization", "Bearer " + TestJwt.token("mallory")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/errors/forbidden"));
        }
```

Implement `openAnAccountAs(String owner)` as a helper that POSTs `/api/v1/accounts` with that owner's token and returns the created `accountUid`. **Verify this test fails if you remove `@Primary` from the decorator beans** — that is the specific defect it exists to catch — and report that red→green explicitly.

Also add its positive twin: alice reading her own balance succeeds. A test that only asserts the refusal would pass against a system that refuses everyone.

- [ ] **Step 8: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/config src/test/java/com/ffroliva/tinyledger/config src/test/resources/features
git commit -m "feat: authorise reads at the port boundary, refusing with the catalogued 403"
```

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

Add to `BalanceControllerTest` (a slice is sufficient here — unlike security, filter registration is visible at that level):

```java
    @Test // the filter is in the chain, not merely written
    void everyResponseCarriesAnInteractionId() throws Exception {
        given(queryAccounts.accountsOwnedBy(any())).willReturn(List.of());

        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists("x-fapi-interaction-id"));
    }

    @Test // §6.5/§6.6: and it reaches the problem body, which is the reason the filter exists
    void aProblemResponseCarriesTheTraceId() throws Exception {
        given(queryBalance.balance(any(), any())).willThrow(new RuntimeException("boom"));

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.traceId").exists());
    }
```

If the slice does not register the filter, promote both to the `full` `@SpringBootTest` from Task 3 Step 7 rather than dropping them.

- [ ] **Step 6: Run both pipelines**

Run: `./mvnw -q verify` then `./mvnw -q verify -Pit`
Expected: exit 0 and exit 0.

- [ ] **Step 7: Commit**

```bash
./mvnw -q spotless:apply
git add src/main/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilter.java src/test/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilterTest.java src/test/java/com/ffroliva/tinyledger/balance/adapter/in/web/BalanceControllerTest.java
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
| **P0-1** | 6 | Decorating only the read ports would have left §6.4 enforced in two places permanently. User chose option (a): all five use cases authorise at the boundary; the two in-service checks move out. |
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
