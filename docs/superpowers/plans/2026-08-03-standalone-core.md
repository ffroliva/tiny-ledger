# tiny-ledger Plan 1 — Standalone Core (spec §14 steps 0–4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The brief-compliant, submittable ledger: `./mvnw spring-boot:run` serves the §7 core API
from an in-memory event store, with domain, ports, projections, notification rules, OpenAPI
contract, ArchUnit governance and the `@standalone` Cucumber suite all green.

**Architecture:** Spring Modulith modular monolith (`shared`, `ledger`, `balance`, `audit`*,
`notification`, non-module `platform` + `config`), hexagonal layers inside each module, CQRS as the
module split (spec §3/§4). Event-sourced write path with optimistic concurrency; in-memory adapters
only — Postgres/Kafka/Redis/Keycloak arrive in Plans 2–3. (*`audit` is created as an empty verified
module here; its consumer lands in Plan 2.)

**Tech Stack:** Java 25 (Corretto), Spring Boot 4.1.0, Spring Modulith (Boot-4 line), Jackson 3,
JUnit 5 + AssertJ + ArchUnit + Cucumber-JVM + Awaitility, Maven wrapper, springdoc +
openapi-generator (contract-first), spotless + JaCoCo.

## Global Constraints (from docs/spec.md v3.3 — the contract; cite it, don't re-decide it)

- Package root `com.flaviooliva.ledger`; module/package layout exactly §3.1.
- Versions come from `dr-jskill`'s `versions.json` — bump there first, then `pom.xml` properties (§1.5). Boot **4.1.0**, Java **25**.
- **Jackson 3** annotations/imports from the start (`tools.jackson.*` where applicable) — never Jackson 2 (§1.5).
- `.properties`, never YAML; `@ConfigurationProperties` for typed config (§1.5).
- Money is `long` minorUnits + `java.util.Currency` end to end; JSON shape `{"currency","minorUnits"}` (§2.1/§7). Never float, never BigDecimal on the wire.
- Domain: zero framework imports. Application: no Spring stereotypes/annotations — wiring, transactions and authorisation are composition-root decorators (§4.5/§6.4/§9.2).
- One use-case per service class; no service depends on another service; shared behaviour only in domain policy / `shared` / `platform` (§4.6 rule 5, §9.2).
- Wire DTOs generated from `docs/api/openapi.yaml`; referenced only from `adapter.in.web` (§4.6).
- Errors: RFC 7807 via `spring.mvc.problemdetails.enabled=true`; catalogue §6.5 exactly (401/403/404/409×2/422×2/400/429/503).
- `standalone` profile is default; binds `127.0.0.1`; banner prints `AUTH DISABLED (standalone)`; **fail-closed guard**: full-shaped config under standalone aborts startup (§1).
- Auditor endpoints (`/events`, `/audit/entries`) return **501** in standalone (§7).
- Conventional commits; run `./mvnw -q verify` before every commit; never skip failing tests.
- TDD strictly: failing test first, minimal implementation, green, commit.

---

## File Structure (what exists when this plan is done)

```
pom.xml · mvnw · .mvn/ · .editorconfig · .gitattributes
docs/ (scaffold: INDEX.md, CHANGELOG.md, tutorial/, how-to/, api/openapi.yaml, governance-baseline.md)
scripts/ci/check_docs_governance.py
src/main/java/com/flaviooliva/ledger/
  LedgerApplication.java
  shared/  Money, AccountId, CurrencyMismatchException, package-info (OPEN module)
  ledger/  package-info · domain/{LedgerEvent,AccountOpened,MoneyDeposited,MoneyWithdrawn,
           MovementRejected,Account,MovementType,policy/OverdraftPolicy}
           application/port/in/{OpenAccountUseCase,RecordMovementUseCase,QueryStrongBalanceUseCase,
           commands: OpenAccount,Deposit,Withdraw · results: OpenedAccount,MovementResult,Outcome}
           application/port/out/{EventStorePort,EventPublisherPort,ClockPort,IdGeneratorPort}
           application/usecase/{OpenAccountService,RecordMovementService,StrongBalanceService}
           application/error/{ConcurrencyConflictException,DuplicateMovementException,
           IdempotencyConflictException,OwnershipException,AccountNotFoundException}
           adapter/in/web/{LedgerController,LedgerApiMapper}
           adapter/out/inmemory/InMemoryEventStore · adapter/out/spring/SpringEventPublisher
  balance/ package-info · application/port/in/{QueryBalanceUseCase,QueryHistoryUseCase,
           QueryAccountsUseCase · views: BalanceView,TransactionView,AccountView,HistoryPage,HistoryQuery}
           application/port/out/{BalanceProjectionPort,BalanceCachePort}
           application/projection/BalanceProjector
           application/usecase/{BalanceQueryService,HistoryQueryService,AccountsQueryService}
           adapter/in/events/LedgerEventsListener · adapter/in/web/BalanceController
           adapter/out/inmemory/{InMemoryBalanceProjection,MapBalanceCache}
  audit/   package-info only (verified empty module)
  notification/ package-info · application/{NotificationRules,Notification,NotificationPort}
           adapter/out/log/LogNotificationAdapter · adapter/in/events/NotificationEventsListener
  platform/ ErrorHandlingAdvice, StartupBanner, FailClosedGuard
  config/  StandaloneAdapterConfig, UseCaseConfig, AuthorizationConfig
src/main/resources/ application.properties, application-standalone.properties, banner.txt
src/test/java/...  mirrors main; + architecture/{ModulithTest,HexagonalRulesTest}
                   + contract/EventStoreContract + cucumber/{CucumberTest, steps/}
src/test/resources/features/*.feature (@standalone catalogue)
```

---

### Task 0: Docs scaffold + governance baseline (spec §14 step 0)

**Files:**
- Create: `docs/INDEX.md`, `docs/CHANGELOG.md` (root `CHANGELOG.md`), `docs/tutorial/.gitkeep`, `docs/how-to/.gitkeep`, `docs/_archive/.gitkeep`
- Create: `scripts/ci/check_docs_governance.py`
- Create: `docs/governance-baseline.md`

**Interfaces:**
- Produces: the registered governance baseline (§14 step 0) — stage-6 CI later diffs against it.

- [ ] **Step 1: Create the Diátaxis scaffold**

`docs/INDEX.md`:
```markdown
# Documentation index
| Document | Quadrant | Owner |
|---|---|---|
| ../README.md | Tutorial | Flávio Oliva |
| spec.md | Explanation | Flávio Oliva |
| agentic-workflow.md | Explanation | Flávio Oliva |
| api/openapi.yaml | Reference | Flávio Oliva |
Rebuilt per release (spec §8.5).
```

Root `CHANGELOG.md`:
```markdown
# Changelog — Keep a Changelog format
## [Unreleased]
### Added
- Docs scaffold and governance baseline (spec §14 step 0).
```

- [ ] **Step 2: Run the vendored governance test and capture the baseline**

Run: `python -m pytest .claude/skills/iso-compliance/scripts/test_docs_governance.py -q 2>&1 | tee /tmp/gov.txt`
Expected: FAILURES — that is the point. Copy every `FAILED`/failure-summary line into
`docs/governance-baseline.md` under a dated heading:

```markdown
# Governance baseline — 2026-08-03 (spec §14 step 0)
The registered backlog. Stage 6 fails only on regressions against this list.
## Failing checks at baseline
<paste the failure lines verbatim>
```

- [ ] **Step 3: Write the regression-only wrapper**

`scripts/ci/check_docs_governance.py`:
```python
"""Stage 6 gate: fail on NEW governance violations, never on the registered baseline."""
import re
import subprocess
import sys
from pathlib import Path

BASELINE = Path("docs/governance-baseline.md")
TEST = Path(".claude/skills/iso-compliance/scripts/test_docs_governance.py")


def failures(text: str) -> set[str]:
    return {line.strip() for line in text.splitlines() if re.match(r"^(FAILED|.*::.* FAILED)", line.strip())}


def main() -> int:
    proc = subprocess.run(
        [sys.executable, "-m", "pytest", str(TEST), "-q"], capture_output=True, text=True
    )
    current = failures(proc.stdout + proc.stderr)
    registered = failures(BASELINE.read_text(encoding="utf-8"))
    new = current - registered
    if new:
        print("NEW governance violations (not in baseline):")
        for line in sorted(new):
            print(" ", line)
        return 1
    fixed = registered - current
    if fixed:
        print(f"{len(fixed)} baseline item(s) now pass — prune docs/governance-baseline.md.")
    print(f"governance OK: {len(current)} known, 0 new")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: Verify the wrapper passes against its own baseline**

Run: `python scripts/ci/check_docs_governance.py`
Expected: `governance OK: <n> known, 0 new`, exit 0.

- [ ] **Step 5: Commit**

```bash
git add docs CHANGELOG.md scripts
git commit -m "docs: Diátaxis scaffold and governance baseline (spec §14 step 0)"
```

---

### Task 1: Maven skeleton + Modulith verification (spec §14 step 1)

**Files:**
- Create: `pom.xml`, Maven wrapper (`mvn wrapper:wrapper -Dmaven=3.9.9` or copy from dr-jskill assets), `.editorconfig`, `.gitattributes` (from `.claude/skills/dr-jskill/assets/`)
- Create: `src/main/java/com/flaviooliva/ledger/LedgerApplication.java`
- Create: `package-info.java` for `shared` (open), `ledger`, `balance`, `audit`, `notification`
- Test: `src/test/java/com/flaviooliva/ledger/architecture/ModulithTest.java`

**Interfaces:**
- Produces: the build every later task runs (`./mvnw -q verify`); module boundaries as compile-time fact.

- [ ] **Step 1: Write `pom.xml`** (versions verified against `.claude/skills/dr-jskill/` `versions.json` — if a listed version differs, the skill file wins and this plan's property is updated in the same commit)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
  </parent>
  <groupId>com.flaviooliva</groupId>
  <artifactId>tiny-ledger</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <java.version>25</java.version>
    <spring-modulith.version>2.1.0</spring-modulith.version>
    <archunit.version>1.3.0</archunit.version>
    <cucumber.version>7.20.1</cucumber.version>
    <openapi-generator.version>7.10.0</openapi-generator.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-bom</artifactId>
        <version>${spring-modulith.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-starter-core</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.modulith</groupId><artifactId>spring-modulith-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><version>${archunit.version}</version><scope>test</scope></dependency>
    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-java</artifactId><version>${cucumber.version}</version><scope>test</scope></dependency>
    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-junit-platform-engine</artifactId><version>${cucumber.version}</version><scope>test</scope></dependency>
    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-spring</artifactId><version>${cucumber.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-suite</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.awaitility</groupId><artifactId>awaitility</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
      <plugin>
        <groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId><version>2.44.0</version>
        <configuration><java><palantirJavaFormat/></java></configuration>
        <executions><execution><goals><goal>check</goal></goals></execution></executions>
      </plugin>
      <plugin>
        <groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.12</version>
        <executions>
          <execution><goals><goal>prepare-agent</goal></goals></execution>
          <execution><id>check</id><goals><goal>check</goal></goals>
            <configuration><rules><rule>
              <element>PACKAGE</element>
              <includes><include>com.flaviooliva.ledger.*.domain*</include></includes>
              <limits>
                <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.90</minimum></limit>
                <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.85</minimum></limit>
              </limits>
            </rule></rules></configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write the application class and module markers**

`LedgerApplication.java`:
```java
package com.flaviooliva.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class LedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
```

`shared/package-info.java`:
```java
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.flaviooliva.ledger.shared;
```

`ledger/package-info.java` (same pattern for `balance`, `audit`, `notification`, with the
`allowedDependencies` from spec §3: ledger→`shared`; balance/audit/notification→`shared`, `ledger::events`):
```java
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.flaviooliva.ledger.ledger;
```

- [ ] **Step 3: Write the failing Modulith verification test**

```java
package com.flaviooliva.ledger.architecture;

import com.flaviooliva.ledger.LedgerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithTest {
    @Test
    void modularStructureIsValid() {
        ApplicationModules.of(LedgerApplication.class).verify();
    }
}
```

- [ ] **Step 4: Run** `./mvnw -q verify` — Expected: green (empty modules verify trivially; spotless may reformat first: `./mvnw spotless:apply`).

- [ ] **Step 5: Commit**

```bash
git add pom.xml mvnw mvnw.cmd .mvn .editorconfig .gitattributes src CHANGELOG.md
git commit -m "feat: Maven skeleton, module markers, Modulith verification (spec §14 step 1)"
```

---

### Task 2: Shared kernel — `Money`, `AccountId`

**Files:**
- Create: `shared/Money.java`, `shared/AccountId.java`, `shared/CurrencyMismatchException.java`
- Test: `src/test/java/com/flaviooliva/ledger/shared/MoneyTest.java`

**Interfaces:**
- Produces: `record Money(java.util.Currency currency, long minorUnits)` with
  `plus(Money)`, `minus(Money)`, `isPositive()`, `isNegative()`, `static Money of(String currencyCode, long minorUnits)`;
  `record AccountId(java.util.UUID value)` with `static AccountId random()`, `static AccountId of(String uuid)`;
  `CurrencyMismatchException extends RuntimeException` carrying both currency codes.

- [ ] **Step 1: Write the failing tests**

```java
package com.flaviooliva.ledger.shared;

import static org.assertj.core.api.Assertions.*;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {
    private static final Currency GBP = Currency.getInstance("GBP");

    @Test
    void addsAndSubtractsInMinorUnits() {
        Money a = new Money(GBP, 10_000);
        assertThat(a.plus(new Money(GBP, 2_500))).isEqualTo(new Money(GBP, 12_500));
        assertThat(a.minus(new Money(GBP, 2_500))).isEqualTo(new Money(GBP, 7_500));
    }

    @Test
    void refusesCrossCurrencyArithmetic() {
        assertThatThrownBy(() -> new Money(GBP, 1).plus(Money.of("EUR", 1)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void signHelpers() {
        assertThat(new Money(GBP, 1).isPositive()).isTrue();
        assertThat(new Money(GBP, 0).isPositive()).isFalse();
        assertThat(new Money(GBP, -1).isNegative()).isTrue();
    }

    @Test
    void overflowFailsLoudly() {
        assertThatThrownBy(() -> new Money(GBP, Long.MAX_VALUE).plus(new Money(GBP, 1)))
                .isInstanceOf(ArithmeticException.class);
    }
}
```

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=MoneyTest` — Expected: FAIL (class missing).

- [ ] **Step 3: Implement**

```java
package com.flaviooliva.ledger.shared;

import java.util.Currency;
import java.util.Objects;

public record Money(Currency currency, long minorUnits) {
    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(String currencyCode, long minorUnits) {
        return new Money(Currency.getInstance(currencyCode), minorUnits);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits));
    }

    public boolean isPositive() { return minorUnits > 0; }

    public boolean isNegative() { return minorUnits < 0; }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency.getCurrencyCode(), other.currency.getCurrencyCode());
        }
    }
}
```

```java
package com.flaviooliva.ledger.shared;

public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String expected, String actual) {
        super("currency mismatch: expected %s, got %s".formatted(expected, actual));
    }
}
```

```java
package com.flaviooliva.ledger.shared;

import java.util.Objects;
import java.util.UUID;

public record AccountId(UUID value) {
    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    public static AccountId random() { return new AccountId(UUID.randomUUID()); }

    public static AccountId of(String uuid) { return new AccountId(UUID.fromString(uuid)); }
}
```

- [ ] **Step 4: Run** `./mvnw -q test -Dtest=MoneyTest` — Expected: PASS. Then `./mvnw -q verify`.

- [ ] **Step 5: Commit** — `git add src && git commit -m "feat: shared kernel Money and AccountId (spec §2.1)"`

---

### Task 3: Ledger domain — events, commands, `Account` aggregate

**Files:**
- Create: `ledger/domain/{MovementType,LedgerEvent,AccountOpened,MoneyDeposited,MoneyWithdrawn,MovementRejected,Account}.java`, `ledger/domain/policy/OverdraftPolicy.java`
- Create: `ledger/application/port/in/{OpenAccount,Deposit,Withdraw}.java` (commands)
- Test: `src/test/java/com/flaviooliva/ledger/ledger/domain/AccountTest.java`

**Interfaces (Produces — every later task relies on these exact shapes):**

```java
public enum MovementType { DEPOSIT, WITHDRAWAL }

public sealed interface LedgerEvent permits AccountOpened, MoneyDeposited, MoneyWithdrawn, MovementRejected {
    AccountId accountId(); long version(); java.time.Instant occurredAt();
}
public record AccountOpened(AccountId accountId, long version, Instant occurredAt,
                            String owner, String name, java.util.Currency currency) implements LedgerEvent {}
public record MoneyDeposited(AccountId accountId, long version, Instant occurredAt,
                             java.util.UUID movementUid, Money amount, String reference,
                             Money balanceAfter) implements LedgerEvent {}
public record MoneyWithdrawn(AccountId accountId, long version, Instant occurredAt,
                             java.util.UUID movementUid, Money amount, String reference,
                             Money balanceAfter) implements LedgerEvent {}
public record MovementRejected(AccountId accountId, long version, Instant occurredAt,
                               java.util.UUID movementUid, MovementType type, Money amount,
                               String reason) implements LedgerEvent {}
// commands (§2.4): caller = JWT subject or the fixed standalone principal
public record OpenAccount(String caller, String name, java.util.Currency currency) {}
public record Deposit(String caller, AccountId accountId, java.util.UUID movementUid, Money amount, String reference) {}
public record Withdraw(String caller, AccountId accountId, java.util.UUID movementUid, Money amount, String reference) {}
```

`Account`: `static List<LedgerEvent> open(AccountId id, OpenAccount cmd, Instant now)`,
`static Account rehydrate(List<LedgerEvent> history)`,
`List<LedgerEvent> deposit(Deposit cmd, Instant now)`, `List<LedgerEvent> withdraw(Withdraw cmd, Instant now)`,
getters `id()`, `owner()`, `name()`, `currency()`, `version()`, `balance()` (Money).
Rejection reasons are the §6.5 slugs: `"insufficient-funds"`, `"currency-mismatch"`.

- [ ] **Step 1: Write the failing tests** — one test per §2.2 invariant plus the rejection paths:

```java
package com.flaviooliva.ledger.ledger.domain;

import static org.assertj.core.api.Assertions.*;

import com.flaviooliva.ledger.shared.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AccountTest {
    private static final Instant T = Instant.parse("2026-08-03T12:00:00Z");
    private static final Currency GBP = Currency.getInstance("GBP");

    private Account openedWith(long minorUnits) {
        AccountId id = AccountId.random();
        List<LedgerEvent> history = new ArrayList<>(
                Account.open(id, new OpenAccount("alice", "ACC-001", GBP), T));
        Account account = Account.rehydrate(history);
        if (minorUnits > 0) {
            history.addAll(account.deposit(
                    new Deposit("alice", id, UUID.randomUUID(), new Money(GBP, minorUnits), null), T));
            account = Account.rehydrate(history);
        }
        return account;
    }

    @Test
    void openEmitsAccountOpenedAtVersionOneWithOwnerAndName() {
        List<LedgerEvent> events = Account.open(AccountId.random(), new OpenAccount("alice", "ACC-001", GBP), T);
        assertThat(events).singleElement().isInstanceOf(AccountOpened.class);
        AccountOpened opened = (AccountOpened) events.getFirst();
        assertThat(opened.version()).isEqualTo(1);
        assertThat(opened.owner()).isEqualTo("alice");
        assertThat(opened.name()).isEqualTo("ACC-001");
    }

    @Test
    void depositIncrementsVersionByExactlyOneAndCarriesBalanceAfter() {
        Account account = openedWith(0);
        List<LedgerEvent> events = account.deposit(
                new Deposit("alice", account.id(), UUID.randomUUID(), new Money(GBP, 10_000), "rent"), T);
        MoneyDeposited deposited = (MoneyDeposited) events.getFirst();
        assertThat(deposited.version()).isEqualTo(account.version() + 1);
        assertThat(deposited.balanceAfter()).isEqualTo(new Money(GBP, 10_000));
    }

    @Test
    void withdrawalBeyondBalanceEmitsMovementRejectedNotAnException() {
        Account account = openedWith(5_000);
        List<LedgerEvent> events = account.withdraw(
                new Withdraw("alice", account.id(), UUID.randomUUID(), new Money(GBP, 10_000), null), T);
        MovementRejected rejected = (MovementRejected) events.getFirst();
        assertThat(rejected.reason()).isEqualTo("insufficient-funds");
        assertThat(Account.rehydrate(appended(account, events)).balance()).isEqualTo(new Money(GBP, 5_000));
    }

    @Test
    void exactBalanceWithdrawalIsAllowed() {
        Account account = openedWith(5_000);
        List<LedgerEvent> events = account.withdraw(
                new Withdraw("alice", account.id(), UUID.randomUUID(), new Money(GBP, 5_000), null), T);
        assertThat(events.getFirst()).isInstanceOf(MoneyWithdrawn.class);
    }

    @Test
    void currencyMismatchIsRejectedAsStateNotShape() {
        Account account = openedWith(5_000);
        List<LedgerEvent> events = account.deposit(
                new Deposit("alice", account.id(), UUID.randomUUID(), Money.of("EUR", 100), null), T);
        assertThat(((MovementRejected) events.getFirst()).reason()).isEqualTo("currency-mismatch");
    }

    @Test
    void nonPositiveAmountsAreRejectedByTheAggregateToo() {
        Account account = openedWith(5_000);
        assertThatThrownBy(() -> account.deposit(
                new Deposit("alice", account.id(), UUID.randomUUID(), new Money(GBP, 0), null), T))
                .isInstanceOf(IllegalArgumentException.class); // defence in depth; the boundary 400s first (§4.6)
    }

    @Test
    void balanceIsOnlyEverComputedFromEvents() {
        Account account = openedWith(0);
        List<LedgerEvent> history = new ArrayList<>(Account.open(account.id(),
                new OpenAccount("alice", "ACC-001", GBP), T));
        // rehydrating twice from the same history yields identical state
        assertThat(Account.rehydrate(history).balance()).isEqualTo(Account.rehydrate(history).balance());
    }

    private static List<LedgerEvent> appended(Account account, List<LedgerEvent> tail) {
        List<LedgerEvent> all = new ArrayList<>(Account.open(account.id(),
                new OpenAccount(account.owner(), account.name(), account.currency()), T));
        // NOTE: tests that need full history keep their own list; helper shown for the rejection test
        all.addAll(tail);
        return all;
    }
}
```

(The `appended` helper above is illustrative for the rejection test; the implementer keeps each
test's own `history` list — the assertions are the contract.)

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=AccountTest` — Expected: FAIL (classes missing).

- [ ] **Step 3: Implement the events/commands exactly as the Interfaces block, then `Account`:**

```java
package com.flaviooliva.ledger.ledger.domain;

import com.flaviooliva.ledger.ledger.application.port.in.*;
import com.flaviooliva.ledger.ledger.domain.policy.OverdraftPolicy;
import com.flaviooliva.ledger.shared.*;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

public final class Account {
    private final AccountId id;
    private String owner;
    private String name;
    private Currency currency;
    private long version;
    private long balanceMinorUnits;

    private Account(AccountId id) { this.id = id; }

    public static List<LedgerEvent> open(AccountId id, OpenAccount cmd, Instant now) {
        return List.of(new AccountOpened(id, 1, now, cmd.caller(), cmd.name(), cmd.currency()));
    }

    public static Account rehydrate(List<LedgerEvent> history) {
        if (history.isEmpty()) throw new IllegalArgumentException("empty stream");
        Account account = new Account(history.getFirst().accountId());
        history.forEach(account::apply);
        return account;
    }

    public List<LedgerEvent> deposit(Deposit cmd, Instant now) {
        requirePositive(cmd.amount());
        if (!currency.equals(cmd.amount().currency())) {
            return List.of(new MovementRejected(id, version + 1, now, cmd.movementUid(),
                    MovementType.DEPOSIT, cmd.amount(), "currency-mismatch"));
        }
        Money after = balance().plus(cmd.amount());
        return List.of(new MoneyDeposited(id, version + 1, now, cmd.movementUid(),
                cmd.amount(), cmd.reference(), after));
    }

    public List<LedgerEvent> withdraw(Withdraw cmd, Instant now) {
        requirePositive(cmd.amount());
        if (!currency.equals(cmd.amount().currency())) {
            return List.of(new MovementRejected(id, version + 1, now, cmd.movementUid(),
                    MovementType.WITHDRAWAL, cmd.amount(), "currency-mismatch"));
        }
        Money after = balance().minus(cmd.amount());
        if (!OverdraftPolicy.permits(after)) {
            return List.of(new MovementRejected(id, version + 1, now, cmd.movementUid(),
                    MovementType.WITHDRAWAL, cmd.amount(), "insufficient-funds"));
        }
        return List.of(new MoneyWithdrawn(id, version + 1, now, cmd.movementUid(),
                cmd.amount(), cmd.reference(), after));
    }

    private void apply(LedgerEvent event) {
        if (event.version() != version + 1) {
            throw new IllegalStateException("gap in stream: expected %d got %d".formatted(version + 1, event.version()));
        }
        switch (event) {
            case AccountOpened e -> { owner = e.owner(); name = e.name(); currency = e.currency(); }
            case MoneyDeposited e -> balanceMinorUnits = e.balanceAfter().minorUnits();
            case MoneyWithdrawn e -> balanceMinorUnits = e.balanceAfter().minorUnits();
            case MovementRejected e -> { /* recorded, no balance change */ }
        }
        version = event.version();
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) throw new IllegalArgumentException("amount must be positive");
    }

    public AccountId id() { return id; }
    public String owner() { return owner; }
    public String name() { return name; }
    public Currency currency() { return currency; }
    public long version() { return version; }
    public Money balance() { return new Money(currency, balanceMinorUnits); }
}
```

`policy/OverdraftPolicy.java`:
```java
package com.flaviooliva.ledger.ledger.domain.policy;

import com.flaviooliva.ledger.shared.Money;

public final class OverdraftPolicy {
    private OverdraftPolicy() {}

    /** No overdraft in this PoC (spec §2.2 invariant 1, §15 assumption 2). */
    public static boolean permits(Money balanceAfter) {
        return !balanceAfter.isNegative();
    }
}
```

- [ ] **Step 4: Run** `./mvnw -q test -Dtest=AccountTest` then `./mvnw -q verify` — Expected: PASS (Modulith verify still green: commands live in `ledger.application.port.in`, domain imports only `shared`).

- [ ] **Step 5: Commit** — `git commit -am "feat: ledger domain — events, commands, Account aggregate (spec §2)"`

---

### Task 4: Architecture rules — ArchUnit (spec §9.2)

**Files:**
- Test: `src/test/java/com/flaviooliva/ledger/architecture/HexagonalRulesTest.java`

**Interfaces:** Consumes the package layout from Tasks 1–3. Produces the mechanical governance every later task builds under.

- [ ] **Step 1: Write the rules (they must PASS against Tasks 1–3's code — they are the executable §9.2):**

```java
package com.flaviooliva.ledger.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.flaviooliva.ledger")
class HexagonalRulesTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.apache.kafka..", "io.lettuce..");

    @ArchTest
    static final ArchRule applicationCarriesNoSpringAnnotations = noClasses()
            .that().resideInAPackage("..application..")
            .should().beAnnotatedWith("org.springframework.stereotype.Service")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Component")
            .orShould().beAnnotatedWith("org.springframework.transaction.annotation.Transactional");

    @ArchTest
    static final ArchRule adaptersNeverCallAdapters = slices()
            .matching("..adapter.out.(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule onlyConfigInstantiatesOutboundAdapters = noClasses()
            .that().resideOutsideOfPackages("..config..", "..adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out..");

    @ArchTest
    static final ArchRule noCyclicPackages = slices()
            .matching("com.flaviooliva.ledger.(*)..").should().beFreeOfCycles();

    @ArchTest // §9.2 anti-CRUD: one use case, one service
    static final ArchRule noServiceDependsOnAnotherService = noClasses()
            .that().resideInAPackage("..application.usecase..")
            .should().dependOnClassesThat().resideInAPackage("..application.usecase..");

    @ArchTest // §4.6: wire DTOs only below the web adapter
    static final ArchRule generatedDtosStayInWebAdapters = noClasses()
            .that().resideOutsideOfPackage("..adapter.in.web..")
            .should().dependOnClassesThat().resideInAPackage("com.flaviooliva.ledger.api.generated..");

    @ArchTest // §3.1: time and identity arrive through ports
    static final ArchRule domainNeverCallsNowOrRandomUuid = noClasses()
            .that().resideInAPackage("..domain..")
            .should().callMethod(java.time.Instant.class, "now")
            .orShould().callMethod(java.util.UUID.class, "randomUUID");
}
```

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=HexagonalRulesTest` — Expected: PASS. If a rule fails, the *code* from Tasks 1–3 is wrong — fix the code, never the rule.

- [ ] **Step 3: Commit** — `git commit -am "test: ArchUnit hexagonal and anti-CRUD rules (spec §9.2)"`

---

### Task 5: Ports and use-case services

**Files:**
- Create: `ledger/application/port/out/{EventStorePort,EventPublisherPort,ClockPort,IdGeneratorPort}.java`
- Create: `ledger/application/port/in/{OpenAccountUseCase,RecordMovementUseCase,QueryStrongBalanceUseCase,OpenedAccount,MovementResult,Outcome,StrongBalance}.java`
- Create: `ledger/application/error/{ConcurrencyConflictException,DuplicateMovementException,IdempotencyConflictException,OwnershipException,AccountNotFoundException}.java`
- Create: `ledger/application/usecase/{OpenAccountService,RecordMovementService,StrongBalanceService}.java`
- Test: `src/test/java/com/flaviooliva/ledger/ledger/application/RecordMovementServiceTest.java`

**Interfaces (Produces):**

```java
public interface EventStorePort {
    void append(AccountId streamId, long expectedVersion, List<LedgerEvent> events)
            throws ConcurrencyConflictException, DuplicateMovementException;
    List<LedgerEvent> read(AccountId streamId);                // empty list = unknown account
    Optional<LedgerEvent> findByMovementUid(UUID movementUid); // global (§6.3)
}
public interface EventPublisherPort { void publish(LedgerEvent event); }
public interface ClockPort { java.time.Instant now(); }
public interface IdGeneratorPort { java.util.UUID next(); }

public interface OpenAccountUseCase { OpenedAccount open(OpenAccount cmd); }
public record OpenedAccount(AccountId accountId, long version) {}

public interface RecordMovementUseCase {
    MovementResult deposit(Deposit cmd);
    MovementResult withdraw(Withdraw cmd);
}
public enum Outcome { CREATED, REPLAYED, REJECTED, REJECTED_REPLAYED }
public record MovementResult(AccountId accountId, UUID movementUid, MovementType type,
                             long version, Money amount, Money balanceAfter,
                             Instant occurredAt, Outcome outcome, String rejectionReason) {}

public interface QueryStrongBalanceUseCase { StrongBalance strongBalance(String caller, AccountId accountId); }
public record StrongBalance(AccountId accountId, Money amount, Instant asOf, long streamVersion) {}
```

Exceptions: all `extends RuntimeException`; `IdempotencyConflictException`, `OwnershipException`,
`AccountNotFoundException`, `ConcurrencyConflictException` carry the ids involved;
`DuplicateMovementException` carries the `movementUid` (thrown by the store on a UID race, §6.3).

**Service semantics (spec §4.1 order — authorise before any idempotency answer):**
`RecordMovementService.deposit/withdraw`: ① read stream (`empty → AccountNotFoundException`);
② rehydrate; ③ `!account.owner().equals(cmd.caller()) → OwnershipException`; ④
`findByMovementUid`: same stream + same type/amount → `REPLAYED`/`REJECTED_REPLAYED` result rebuilt
from the found event; same stream + different payload, or different stream → `IdempotencyConflictException`;
⑤ apply command; ⑥ `append(expectedVersion = account.version())`; on `DuplicateMovementException`
re-read by UID and answer as ④; ⑦ publish each event; ⑧ build result
(`MovementRejected → REJECTED` with reason; else `CREATED`).

- [ ] **Step 1: Write the failing tests** — in-memory fakes inline in the test class:

```java
package com.flaviooliva.ledger.ledger.application;

import static org.assertj.core.api.Assertions.*;

import com.flaviooliva.ledger.ledger.application.error.*;
import com.flaviooliva.ledger.ledger.application.port.in.*;
import com.flaviooliva.ledger.ledger.application.port.out.*;
import com.flaviooliva.ledger.ledger.application.usecase.*;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class RecordMovementServiceTest {
    private static final Currency GBP = Currency.getInstance("GBP");
    private final FakeStore store = new FakeStore();
    private final List<LedgerEvent> published = new ArrayList<>();
    private final RecordMovementService service = new RecordMovementService(
            store, published::add, () -> Instant.parse("2026-08-03T12:00:00Z"), UUID::randomUUID);
    private final OpenAccountService openService = new OpenAccountService(
            store, published::add, () -> Instant.parse("2026-08-03T12:00:00Z"), () -> UUID.randomUUID());

    private AccountId opened;

    @BeforeEach
    void openAccount() {
        opened = openService.open(new OpenAccount("alice", "ACC-001", GBP)).accountId();
    }

    @Test
    void firstDepositIsCreatedAndPublished() {
        MovementResult result = service.deposit(new Deposit("alice", opened, UUID.randomUUID(),
                new Money(GBP, 10_000), "rent"));
        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.balanceAfter()).isEqualTo(new Money(GBP, 10_000));
        assertThat(published).hasSize(2); // AccountOpened + MoneyDeposited
    }

    @Test
    void replaySameUidSamePayloadReturnsReplayedWithoutSecondCredit() {
        UUID uid = UUID.randomUUID();
        Deposit cmd = new Deposit("alice", opened, uid, new Money(GBP, 10_000), null);
        service.deposit(cmd);
        MovementResult replay = service.deposit(cmd);
        assertThat(replay.outcome()).isEqualTo(Outcome.REPLAYED);
        assertThat(store.read(opened)).hasSize(2); // no third event
    }

    @Test
    void sameUidDifferentAmountIsAnIdempotencyConflict() {
        UUID uid = UUID.randomUUID();
        service.deposit(new Deposit("alice", opened, uid, new Money(GBP, 10_000), null));
        assertThatThrownBy(() -> service.deposit(new Deposit("alice", opened, uid, new Money(GBP, 999), null)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void foreignCallerIsRefusedBeforeAnyIdempotencyAnswer() {
        UUID uid = UUID.randomUUID();
        service.deposit(new Deposit("alice", opened, uid, new Money(GBP, 10_000), null));
        assertThatThrownBy(() -> service.deposit(new Deposit("mallory", opened, uid, new Money(GBP, 10_000), null)))
                .isInstanceOf(OwnershipException.class); // NOT IdempotencyConflict — §4.1 ordering
    }

    @Test
    void insufficientFundsIsRecordedAndReportedAsRejected() {
        MovementResult result = service.withdraw(new Withdraw("alice", opened, UUID.randomUUID(),
                new Money(GBP, 5_000), null));
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("insufficient-funds");
        assertThat(store.read(opened)).hasSize(2); // MovementRejected IS on the stream
    }

    @Test
    void retryingARejectedUidReplaysTheRejection() {
        UUID uid = UUID.randomUUID();
        service.withdraw(new Withdraw("alice", opened, uid, new Money(GBP, 5_000), null));
        MovementResult replay = service.withdraw(new Withdraw("alice", opened, uid, new Money(GBP, 5_000), null));
        assertThat(replay.outcome()).isEqualTo(Outcome.REJECTED_REPLAYED);
    }

    @Test
    void unknownAccountIs404Shaped() {
        assertThatThrownBy(() -> service.deposit(new Deposit("alice", AccountId.random(),
                UUID.randomUUID(), new Money(GBP, 1), null)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    /** Minimal fake honouring the port contract; the real contract suite is Task 6. */
    static class FakeStore implements EventStorePort {
        final Map<AccountId, List<LedgerEvent>> streams = new HashMap<>();

        public void append(AccountId id, long expectedVersion, List<LedgerEvent> events) {
            List<LedgerEvent> stream = streams.computeIfAbsent(id, k -> new ArrayList<>());
            long current = stream.isEmpty() ? 0 : stream.getLast().version();
            if (current != expectedVersion) throw new ConcurrencyConflictException(id, expectedVersion, current);
            for (LedgerEvent e : events) {
                movementUid(e).ifPresent(uid -> {
                    if (findByMovementUid(uid).isPresent()) throw new DuplicateMovementException(uid);
                });
            }
            stream.addAll(events);
        }

        public List<LedgerEvent> read(AccountId id) {
            return List.copyOf(streams.getOrDefault(id, List.of()));
        }

        public Optional<LedgerEvent> findByMovementUid(UUID uid) {
            return streams.values().stream().flatMap(List::stream)
                    .filter(e -> movementUid(e).map(uid::equals).orElse(false)).findFirst();
        }

        private static Optional<UUID> movementUid(LedgerEvent e) {
            return switch (e) {
                case MoneyDeposited d -> Optional.of(d.movementUid());
                case MoneyWithdrawn w -> Optional.of(w.movementUid());
                case MovementRejected r -> Optional.of(r.movementUid());
                case AccountOpened a -> Optional.empty();
            };
        }
    }
}
```

- [ ] **Step 2: Run** — Expected: FAIL (ports/services missing).

- [ ] **Step 3: Implement** ports/records/exceptions exactly as the Interfaces block, then:

```java
package com.flaviooliva.ledger.ledger.application.usecase;

import com.flaviooliva.ledger.ledger.application.error.*;
import com.flaviooliva.ledger.ledger.application.port.in.*;
import com.flaviooliva.ledger.ledger.application.port.out.*;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.AccountId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RecordMovementService implements RecordMovementUseCase {
    private final EventStorePort store;
    private final EventPublisherPort publisher;
    private final ClockPort clock;
    private final IdGeneratorPort ids;

    public RecordMovementService(EventStorePort store, EventPublisherPort publisher,
                                 ClockPort clock, IdGeneratorPort ids) {
        this.store = store; this.publisher = publisher; this.clock = clock; this.ids = ids;
    }

    @Override public MovementResult deposit(Deposit cmd) {
        return record(cmd.caller(), cmd.accountId(), cmd.movementUid(),
                account -> account.deposit(cmd, clock.now()), MovementType.DEPOSIT, cmd.amount());
    }

    @Override public MovementResult withdraw(Withdraw cmd) {
        return record(cmd.caller(), cmd.accountId(), cmd.movementUid(),
                account -> account.withdraw(cmd, clock.now()), MovementType.WITHDRAWAL, cmd.amount());
    }

    private MovementResult record(String caller, AccountId accountId, UUID movementUid,
                                  java.util.function.Function<Account, List<LedgerEvent>> action,
                                  MovementType type, com.flaviooliva.ledger.shared.Money amount) {
        List<LedgerEvent> history = store.read(accountId);
        if (history.isEmpty()) throw new AccountNotFoundException(accountId);
        Account account = Account.rehydrate(history);
        if (!account.owner().equals(caller)) throw new OwnershipException(caller, accountId); // §4.1 ②③
        Optional<LedgerEvent> existing = store.findByMovementUid(movementUid);               // §4.1 ④ (after authz)
        if (existing.isPresent()) return replayOf(existing.get(), accountId, type, amount);
        List<LedgerEvent> events = action.apply(account);                                    // ⑤
        try {
            store.append(accountId, account.version(), events);                              // ⑥
        } catch (DuplicateMovementException raced) {
            return replayOf(store.findByMovementUid(movementUid).orElseThrow(), accountId, type, amount);
        }
        events.forEach(publisher::publish);                                                  // ⑦
        return resultOf(events.getFirst(), Outcome.CREATED, Outcome.REJECTED);               // ⑧
    }

    private MovementResult replayOf(LedgerEvent event, AccountId requested, MovementType type,
                                    com.flaviooliva.ledger.shared.Money amount) {
        boolean samePayload = switch (event) {
            case MoneyDeposited d -> d.accountId().equals(requested) && type == MovementType.DEPOSIT && d.amount().equals(amount);
            case MoneyWithdrawn w -> w.accountId().equals(requested) && type == MovementType.WITHDRAWAL && w.amount().equals(amount);
            case MovementRejected r -> r.accountId().equals(requested) && r.type() == type && r.amount().equals(amount);
            case AccountOpened a -> false;
        };
        if (!samePayload) throw new IdempotencyConflictException(movementUidOf(event));
        return resultOf(event, Outcome.REPLAYED, Outcome.REJECTED_REPLAYED);
    }

    private MovementResult resultOf(LedgerEvent event, Outcome created, Outcome rejected) {
        return switch (event) {
            case MoneyDeposited d -> new MovementResult(d.accountId(), d.movementUid(), MovementType.DEPOSIT,
                    d.version(), d.amount(), d.balanceAfter(), d.occurredAt(), created, null);
            case MoneyWithdrawn w -> new MovementResult(w.accountId(), w.movementUid(), MovementType.WITHDRAWAL,
                    w.version(), w.amount(), w.balanceAfter(), w.occurredAt(), created, null);
            case MovementRejected r -> new MovementResult(r.accountId(), r.movementUid(), r.type(),
                    r.version(), r.amount(), null, r.occurredAt(), rejected, r.reason());
            case AccountOpened a -> throw new IllegalStateException("not a movement");
        };
    }

    private static UUID movementUidOf(LedgerEvent event) {
        return switch (event) {
            case MoneyDeposited d -> d.movementUid();
            case MoneyWithdrawn w -> w.movementUid();
            case MovementRejected r -> r.movementUid();
            case AccountOpened a -> throw new IllegalStateException("not a movement");
        };
    }
}
```

`OpenAccountService` (id from `IdGeneratorPort`, `Account.open`, append at expectedVersion 0,
publish, return `OpenedAccount`) and `StrongBalanceService` (read → empty→`AccountNotFoundException`
→ rehydrate → ownership check → `new StrongBalance(id, account.balance(), clock.now(), account.version())`)
follow the same shape — both plain classes, both constructor-injected ports only.

- [ ] **Step 4: Run** `./mvnw -q verify` — Expected: PASS, ArchUnit still green (no Spring imports appeared in `application`).

- [ ] **Step 5: Commit** — `git commit -am "feat: ports and use-case services with authorise-first idempotent write path (spec §4.1/§6.3)"`

---

### Task 6: `EventStoreContract` + `InMemoryEventStore` (spec §9.2b)

**Files:**
- Create: `ledger/adapter/out/inmemory/InMemoryEventStore.java`
- Test: `src/test/java/com/flaviooliva/ledger/contract/EventStoreContract.java`, `.../InMemoryEventStoreTest.java`

**Interfaces:**
- Consumes: `EventStorePort` (Task 5).
- Produces: `abstract class EventStoreContract { protected abstract EventStorePort store(); }` —
  Plan 2's `PostgresEventStoreTest` extends the same class unchanged. `InMemoryEventStore` is
  thread-safe (per-stream synchronized append on a `ConcurrentHashMap`).

- [ ] **Step 1: Write the contract suite (failing — no adapter yet):**

```java
package com.flaviooliva.ledger.contract;

import static org.assertj.core.api.Assertions.*;

import com.flaviooliva.ledger.ledger.application.error.*;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

public abstract class EventStoreContract {
    protected abstract EventStorePort store();

    private static final Instant T = Instant.parse("2026-08-03T12:00:00Z");
    private static final Currency GBP = Currency.getInstance("GBP");

    private AccountId newStream(EventStorePort store) {
        AccountId id = AccountId.random();
        store.append(id, 0, List.of(new AccountOpened(id, 1, T, "alice", "ACC-001", GBP)));
        return id;
    }

    private static MoneyDeposited deposit(AccountId id, long version, UUID uid) {
        Money amount = new Money(GBP, 1_000);
        return new MoneyDeposited(id, version, T, uid, amount, null, new Money(GBP, 1_000 * (version - 1)));
    }

    @Test void appendsAndReadsInOrder() {
        EventStorePort store = store();
        AccountId id = newStream(store);
        store.append(id, 1, List.of(deposit(id, 2, UUID.randomUUID())));
        assertThat(store.read(id)).hasSize(2).isSortedAccordingTo(Comparator.comparingLong(LedgerEvent::version));
    }

    @Test void rejectsStaleExpectedVersion() {
        EventStorePort store = store();
        AccountId id = newStream(store);
        assertThatThrownBy(() -> store.append(id, 0, List.of(deposit(id, 2, UUID.randomUUID()))))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test void movementUidIsGloballyUnique() {
        EventStorePort store = store();
        AccountId a = newStream(store); AccountId b = newStream(store);
        UUID uid = UUID.randomUUID();
        store.append(a, 1, List.of(deposit(a, 2, uid)));
        assertThatThrownBy(() -> store.append(b, 1, List.of(deposit(b, 2, uid))))
                .isInstanceOf(DuplicateMovementException.class);
        assertThat(store.findByMovementUid(uid)).isPresent();
    }

    @Test void concurrentAppendsYieldExactlyOneWinner() throws Exception {
        EventStorePort store = store();
        AccountId id = newStream(store);
        int writers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            tasks.add(() -> {
                start.await();
                try {
                    store.append(id, 1, List.of(deposit(id, 2, UUID.randomUUID())));
                    return true;
                } catch (ConcurrencyConflictException e) {
                    return false;
                }
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<Boolean>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            long winners = futures.stream().filter(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).count();
            assertThat(winners).isEqualTo(1);
        }
        assertThat(store.read(id)).hasSize(2); // contiguous, no gaps
    }
}
```

`InMemoryEventStoreTest`:
```java
class InMemoryEventStoreTest extends EventStoreContract {
    private final InMemoryEventStore store = new InMemoryEventStore();
    @Override protected EventStorePort store() { return store; }
}
```

- [ ] **Step 2: Run** — Expected: FAIL (adapter missing).

- [ ] **Step 3: Implement**

```java
package com.flaviooliva.ledger.ledger.adapter.out.inmemory;

import com.flaviooliva.ledger.ledger.application.error.*;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.AccountId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryEventStore implements EventStorePort {
    private final Map<AccountId, List<LedgerEvent>> streams = new ConcurrentHashMap<>();
    private final Map<UUID, LedgerEvent> byMovementUid = new ConcurrentHashMap<>();
    private final Object appendLock = new Object(); // ponytail: global lock — per-stream striping if contention ever matters

    @Override
    public void append(AccountId id, long expectedVersion, List<LedgerEvent> events) {
        synchronized (appendLock) {
            List<LedgerEvent> stream = streams.computeIfAbsent(id, k -> new ArrayList<>());
            long current = stream.isEmpty() ? 0 : stream.getLast().version();
            if (current != expectedVersion) throw new ConcurrencyConflictException(id, expectedVersion, current);
            for (LedgerEvent event : events) {
                uidOf(event).ifPresent(uid -> {
                    if (byMovementUid.containsKey(uid)) throw new DuplicateMovementException(uid);
                });
            }
            stream.addAll(events);
            events.forEach(e -> uidOf(e).ifPresent(uid -> byMovementUid.put(uid, e)));
        }
    }

    @Override
    public List<LedgerEvent> read(AccountId id) {
        synchronized (appendLock) {
            return List.copyOf(streams.getOrDefault(id, List.of()));
        }
    }

    @Override
    public Optional<LedgerEvent> findByMovementUid(UUID uid) {
        return Optional.ofNullable(byMovementUid.get(uid));
    }

    private static Optional<UUID> uidOf(LedgerEvent event) {
        return switch (event) {
            case MoneyDeposited d -> Optional.of(d.movementUid());
            case MoneyWithdrawn w -> Optional.of(w.movementUid());
            case MovementRejected r -> Optional.of(r.movementUid());
            case AccountOpened a -> Optional.empty();
        };
    }
}
```

- [ ] **Step 4: Run** `./mvnw -q verify` — Expected: PASS incl. the concurrency test.

- [ ] **Step 5: Commit** — `git commit -am "feat: EventStoreContract and in-memory store — OCC, global UID, one-winner (spec §9.2b)"`

---

### Task 7: Composition root, decorators, guard, banner (spec §4.5/§1)

**Files:**
- Create: `ledger/adapter/out/spring/SpringEventPublisher.java`
- Create: `config/{StandaloneAdapterConfig,UseCaseConfig,AuthorizationConfig}.java`
- Create: `platform/{FailClosedGuard,StartupBanner}.java`
- Create: `src/main/resources/application.properties`, `application-standalone.properties`
- Test: `src/test/java/com/flaviooliva/ledger/config/FailClosedGuardTest.java`

**Interfaces:**
- Produces: Spring context that boots in `standalone`; `UseCaseConfig` beans
  `OpenAccountUseCase`, `RecordMovementUseCase`, `QueryStrongBalanceUseCase` (the beans are the
  *decorated* instances); fixed standalone principal constant `"local"`
  (`AuthorizationConfig.STANDALONE_PRINCIPAL`).

- [ ] **Step 1: Failing guard test** (ApplicationContextRunner):

```java
package com.flaviooliva.ledger.config;

import static org.assertj.core.api.Assertions.*;

import com.flaviooliva.ledger.platform.FailClosedGuard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FailClosedGuardTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FailClosedGuard.class)
            .withPropertyValues("spring.profiles.active=standalone");

    @Test
    void refusesFullShapedConfigUnderStandalone() {
        runner.withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://keycloak/realm")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bootsCleanStandalone() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }
}
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement:**

```java
package com.flaviooliva.ledger.platform;

import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Spec §1: losing a profile flag degrades to a refusal, never to an unauthenticated ledger. */
@Configuration
public class FailClosedGuard implements EnvironmentAware {
    @Override
    public void setEnvironment(Environment env) {
        boolean standalone = env.matchesProfiles("standalone") || env.getActiveProfiles().length == 0;
        if (!standalone) return;
        String[] fullShaped = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "spring.datasource.url",
                "spring.kafka.bootstrap-servers",
        };
        for (String key : fullShaped) {
            if (env.containsProperty(key)) {
                throw new IllegalStateException(
                        "standalone profile is active but full-mode config '%s' is present — refusing to start unauthenticated (spec §1)"
                                .formatted(key));
            }
        }
    }
}
```

`config/UseCaseConfig.java` (transaction decorator is a no-op passthrough in Plan 1 — the real
`TransactionTemplate` decorator arrives with Postgres in Plan 2; authz decorator is real now):

```java
package com.flaviooliva.ledger.config;

import com.flaviooliva.ledger.ledger.adapter.out.spring.SpringEventPublisher;
import com.flaviooliva.ledger.ledger.application.port.in.*;
import com.flaviooliva.ledger.ledger.application.port.out.*;
import com.flaviooliva.ledger.ledger.application.usecase.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCaseConfig { // profile-independent — the whole trick of spec §1
    @Bean EventPublisherPort publisher(ApplicationEventPublisher p) { return new SpringEventPublisher(p); }

    @Bean OpenAccountUseCase openAccount(EventStorePort store, EventPublisherPort publisher,
                                         ClockPort clock, IdGeneratorPort ids) {
        return new OpenAccountService(store, publisher, clock, ids);
    }

    @Bean RecordMovementUseCase recordMovement(EventStorePort store, EventPublisherPort publisher,
                                               ClockPort clock, IdGeneratorPort ids) {
        return new RecordMovementService(store, publisher, clock, ids);
    }

    @Bean QueryStrongBalanceUseCase strongBalance(EventStorePort store, ClockPort clock) {
        return new StrongBalanceService(store, clock);
    }
}
```

`StandaloneAdapterConfig` (`@Profile("standalone")`): beans `EventStorePort → new InMemoryEventStore()`,
`ClockPort → Instant::now`, `IdGeneratorPort → UUID::randomUUID`.
`SpringEventPublisher`: implements `EventPublisherPort`, delegates to `ApplicationEventPublisher::publishEvent`.
`StartupBanner`: `ApplicationRunner` logging access URL and, when standalone,
`AUTH DISABLED (standalone)`.
`application.properties`: `spring.profiles.default=standalone`,
`spring.mvc.problemdetails.enabled=true`.
`application-standalone.properties`: `server.address=127.0.0.1`.

- [ ] **Step 4: Run** `./mvnw -q verify` and boot check `./mvnw -q spring-boot:run &` → banner shows, `curl -s http://127.0.0.1:8080/actuator/health || true` (no actuator yet — the process starting cleanly is the check), stop it.

- [ ] **Step 5: Commit** — `git commit -am "feat: composition root, standalone profile, fail-closed guard, banner (spec §4.5/§1)"`

---

### Task 8: `balance` module — projector, ports, queries

**Files:**
- Create: `balance/application/port/in/{QueryBalanceUseCase,QueryHistoryUseCase,QueryAccountsUseCase,BalanceView,TransactionView,AccountView,HistoryQuery,HistoryPage}.java`
- Create: `balance/application/port/out/{BalanceProjectionPort,BalanceCachePort}.java`
- Create: `balance/application/projection/BalanceProjector.java`
- Create: `balance/application/usecase/{BalanceQueryService,HistoryQueryService,AccountsQueryService}.java`
- Create: `balance/adapter/in/events/LedgerEventsListener.java`
- Create: `balance/adapter/out/inmemory/{InMemoryBalanceProjection,MapBalanceCache}.java`
- Modify: `balance/package-info.java` → `allowedDependencies = {"shared", "ledger :: events"}` — expose `ledger.domain` events as the `events` named interface (`@NamedInterface("events")` on `ledger/domain/package-info.java`)
- Modify: `config/StandaloneAdapterConfig.java`, `config/UseCaseConfig.java` (projection/cache beans, query beans)
- Test: `.../balance/application/BalanceProjectorTest.java`

**Interfaces (Produces):**

```java
public record BalanceView(AccountId accountId, Money amount, Instant asOf, long streamVersion) {}
public record TransactionView(UUID transactionUid, AccountId accountId, MovementType type,
                              String direction /* "IN"|"OUT" */, Money amount, Money balanceAfter,
                              String status /* "SETTLED" */, Instant transactionTime,
                              Instant settlementTime, String reference) {}
public record AccountView(AccountId accountId, String name, String owner,
                          java.util.Currency currency, Instant createdAt) {}
public record HistoryQuery(String cursor, int limit, Instant minTransactionTimestamp, Instant maxTransactionTimestamp) {}
public record HistoryPage(List<TransactionView> transactions, String nextCursor) {}

public interface BalanceProjectionPort {
    void apply(LedgerEvent event);                       // idempotent on (accountId, version); buffers/refuses out-of-order
    Optional<BalanceView> balance(AccountId id);
    HistoryPage history(AccountId id, HistoryQuery query); // keyset (transactionTime, transactionUid) DESC
    List<AccountView> accountsOwnedBy(String owner);
    Optional<AccountView> account(AccountId id);
}
public interface BalanceCachePort {
    Optional<BalanceView> get(AccountId id);
    void put(AccountId id, BalanceView view);            // implementations honour the 60 s TTL (§6.2)
    void evict(AccountId id);
}
```

`BalanceProjector` (plain class): `on(LedgerEvent)` → `projection.apply(event)`; on
`MoneyDeposited`/`MoneyWithdrawn` also `cache.evict(accountId)` (§6.2 event-driven eviction).
`LedgerEventsListener`: `@Component` adapter with `@ApplicationModuleListener void on(LedgerEvent e)`
delegating to the projector — the only Spring-touching piece.
Cursor encoding: `Base64(transactionTime.toEpochMilli() + ":" + transactionUid)`.

- [ ] **Step 1: Failing tests** — projector semantics (the E4/E5 discipline at unit level):

```java
class BalanceProjectorTest {
    // fixtures: InMemoryBalanceProjection + MapBalanceCache(ttl=Duration.ofSeconds(60), clock)
    @Test void appliesDepositAndServesBalanceWithStaleness() { /* apply AccountOpened+MoneyDeposited(v2, balanceAfter 10_000); balance() == 10_000, streamVersion == 2 */ }
    @Test void duplicateDeliveryIsAppliedOnce() { /* apply same MoneyDeposited twice; balance credited once (E4) */ }
    @Test void outOfOrderDeliveryIsNotApplied() { /* apply v3 before v2: balance unchanged until v2 arrives, then both apply in order (E5 buffer-and-catch-up) */ }
    @Test void rejectionsAppearInHistoryButNotBalance() { /* MovementRejected: balance unchanged; history() does NOT list it (feed shows settled movements; the raw stream is the auditor's view) */ }
    @Test void historyIsNewestFirstKeysetPaginated() { /* 3 deposits; limit 2 → newest 2 + nextCursor; second page → oldest 1, null cursor */ }
    @Test void accountsProjectionServesOwnerScopedList() { /* AccountOpened for alice + bob; accountsOwnedBy("alice") lists only alice's (N12 mechanism) */ }
    @Test void cacheEvictedOnMovementEvents() { /* prime cache; apply MoneyDeposited; cache.get() empty */ }
    @Test void mapCacheExpiresByTtl() { /* MutableClock; put; advance 61 s; get() empty (§6.2 TTL contract) */ }
}
```

Write these as real tests with the fixtures inline (the comments above are the behaviour contract;
each becomes assertions in the same style as Tasks 3/5/6 — construct events with explicit versions
and `balanceAfter`, no mocks, real adapters).

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** the ports/records, `InMemoryBalanceProjection`
(`Map<AccountId, TreeMap<Long, LedgerEvent>> applied` for idempotency + ordering buffer,
`Map<AccountId, List<TransactionView>>` feed, `Map<AccountId, AccountView>` accounts),
`MapBalanceCache(Duration ttl, ClockPort clock)` with timestamp-on-read expiry,
`BalanceProjector`, the three one-method query services (`BalanceQueryService` reads cache →
projection → populates cache), `LedgerEventsListener`, and the config beans.

- [ ] **Step 4: Run** `./mvnw -q verify` — PASS; Modulith verify confirms `balance` only touches `ledger::events`.

- [ ] **Step 5: Commit** — `git commit -am "feat: balance module — idempotent ordered projector, keyset history, accounts projection (spec §4.4)"`

---

### Task 9: `notification` module (spec §3, P8)

**Files:**
- Create: `notification/application/{Notification,NotificationPort,NotificationRules}.java`
- Create: `notification/adapter/in/events/NotificationEventsListener.java`
- Create: `notification/adapter/out/log/LogNotificationAdapter.java`
- Modify: `config/UseCaseConfig.java` (rules bean with threshold from `@ConfigurationProperties`), `application.properties` (`ledger.notification.large-movement-minor-units=1000000`)
- Test: `.../notification/NotificationRulesTest.java`

**Interfaces:**
- Produces: `record Notification(UUID movementUid, AccountId accountId, String kind /* LARGE_MOVEMENT | MOVEMENT_REJECTED */, Money amount, Instant at)`;
  `NotificationRules.evaluate(LedgerEvent) → Optional<Notification>`; `NotificationPort.record(Notification)`.

- [ ] **Step 1: Failing tests:** deposit of 15 000.00 (1_500_000 minor units) → LARGE_MOVEMENT;
deposit of 20.00 → empty; every `MovementRejected` → MOVEMENT_REJECTED; `AccountOpened` → empty.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** (`NotificationRules(long thresholdMinorUnits)` plain class;
listener adapter delegates; log adapter emits one structured SLF4J line
`notification kind={} movementUid={} accountUid={} minorUnits={}`).
- [ ] **Step 4: Run** `./mvnw -q verify` — PASS.
- [ ] **Step 5: Commit** — `git commit -am "feat: notification module — threshold and rejection rules, log delivery (spec §3, P8)"`

---

### Task 10: OpenAPI contract + generated interfaces (spec §5, §14 step 3)

**Files:**
- Create: `docs/api/openapi.yaml` — complete contract: all nine §7 operations, `Money`
  `{currency: string(ISO-4217), minorUnits: int64}`, `Transaction` (incl. `balanceAfter`, `direction`,
  `status`, both timestamps, `reference`), `Balance` (`amount`, `asOf`, `streamVersion`), `Account`
  (`accountUid`, `name`, `currency`, `createdAt`, `owner`), wrapped lists
  (`{"accounts":[…]}`, `{"transactions":[…],"links":{"next":…}}`), `ProblemDetail` responses per the
  §6.5 catalogue, `consistency` query param enum `[strong]`, cursor + `minTransactionTimestamp`/`maxTransactionTimestamp` params, security scheme `bearerAuth` (enforced in Plan 3)
- Modify: `pom.xml` — `openapi-generator-maven-plugin` (`generatorName=spring`,
  `interfaceOnly=true`, `useSpringBoot3=true`, `apiPackage=com.flaviooliva.ledger.api.generated.api`,
  `modelPackage=com.flaviooliva.ledger.api.generated.model`, `useTags=true`)

**Interfaces:**
- Produces: generated `AccountsApi`, `MovementsApi`, `BalanceApi`, `TransactionsApi`, `AuditApi`
  interfaces + model DTOs — Task 11 implements them; the §9.2 rule already fences the generated
  package into `adapter.in.web`.

- [ ] **Step 1:** Write the YAML (paths exactly §7's table; operationIds:
`openAccount`, `listAccounts`, `getAccount`, `putDeposit`, `putWithdrawal`, `getBalance`,
`listTransactions`, `getEvents`, `listAuditEntries`).
- [ ] **Step 2:** Wire the generator; run `./mvnw -q verify`.
Expected: BUILD SUCCESS with generated sources under `target/generated-sources/openapi` compiling — the contract is now a compile-time fact (§5).
- [ ] **Step 3: Commit** — `git commit -am "feat: OpenAPI contract and generated server interfaces (spec §5/§7)"`

---

### Task 11: Web adapters (spec §4.6, §6.5, §7)

**Files:**
- Create: `ledger/adapter/in/web/{LedgerController,LedgerApiMapper}.java`
- Create: `balance/adapter/in/web/BalanceController.java`
- Create: `platform/ErrorHandlingAdvice.java`
- Test: `.../adapter/in/web/LedgerControllerTest.java` (`@WebMvcTest` + stubbed use-case beans), `.../BalanceControllerTest.java`

**Interfaces:**
- Consumes: use-case beans (Task 7/8 exact signatures), generated interfaces (Task 10).
- Produces: the running §7 API. `LedgerController implements AccountsApi (openAccount only), MovementsApi`
  + the strong-read mapping `@GetMapping(path="/api/v1/accounts/{accountUid}/balance", params="consistency=strong")`;
  `BalanceController implements BalanceApi (default mapping), TransactionsApi, AccountsApi (list/get)`.
  Auditor endpoints (`getEvents`, `listAuditEntries`) return **501** with a ProblemDetail
  (`/errors/not-available-in-standalone`) — Plan 2/3 replace the stubs in `full`.
  Caller principal in standalone: `AuthorizationConfig.STANDALONE_PRINCIPAL` (`"local"`).

**`ErrorHandlingAdvice` maps exactly §6.5:** `MethodArgumentNotValidException|HttpMessageNotReadable → 400 /errors/invalid-amount`,
`CurrencyMismatchException → 422 /errors/currency-mismatch`, `AccountNotFoundException → 404 /errors/account-not-found`,
`OwnershipException → 403 /errors/forbidden`, `IdempotencyConflictException → 409 /errors/idempotency-conflict`,
`ConcurrencyConflictException → 409 /errors/version-conflict`, catch-all → 500 with no internals; every body carries `traceId` when tracing is present (full wiring Plan 3).

- [ ] **Step 1: Failing `@WebMvcTest` tests** — the §6.3 response table as HTTP facts:
PUT first deposit → 201 with `balanceAfter.minorUnits`; same PUT again (service stubbed to return
`REPLAYED`) → 200; `IdempotencyConflictException` → 409 + `type: /errors/idempotency-conflict`;
`OwnershipException` → 403; unknown account → 404; `minorUnits: -5` in body → 400 before any
service call (verify stub untouched); `GET /balance?consistency=strong` routes to the
strong-read stub while parameterless `GET /balance` routes to the projection stub (two tests, two
controllers); `GET .../events` → 501.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** controllers + mapper
(`LedgerApiMapper`: generated DTO ↔ command/result, `Outcome.CREATED → 201`, `REPLAYED/REJECTED_REPLAYED → 200`,
`REJECTED → 422 ProblemDetail /errors/insufficient-funds|currency-mismatch` — rejection reasons map to types) + advice.
- [ ] **Step 4: Run** `./mvnw -q verify`; then boot and smoke by hand:

```bash
./mvnw -q spring-boot:run &
UID=$(python -c "import uuid;print(uuid.uuid4())")
ACC=$(curl -s -X POST 127.0.0.1:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"name":"ACC-001","currency":"GBP"}' | python -c "import json,sys;print(json.load(sys.stdin)['accountUid'])")
curl -s -X PUT 127.0.0.1:8080/api/v1/accounts/$ACC/deposits/$UID -H 'Content-Type: application/json' \
  -d '{"amount":{"currency":"GBP","minorUnits":10000}}'
curl -s 127.0.0.1:8080/api/v1/accounts/$ACC/balance
```
Expected: 201 → deposit JSON with `balanceAfter`; balance JSON with `asOf` + `streamVersion`.
- [ ] **Step 5: Commit** — `git commit -am "feat: web adapters — §7 API on the in-memory core, §6.5 problem details"`

---

### Task 12: Cucumber `@standalone` suite (spec §9.3, §14 step 4)

**Files:**
- Create: `src/test/java/com/flaviooliva/ledger/cucumber/{CucumberTest,CucumberSpringConfig,LedgerSteps}.java`
- Create: `src/test/resources/features/{accounts.feature,deposits.feature,withdrawals.feature,history.feature,idempotency.feature,notification.feature,eventual-consistency.feature}`
- Create: test utility `cucumber/PausableListenerGate.java` (test-profile bean the listener adapter consults; `pause()`/`resume()` — E1/E2's deliberate stale window)

**Interfaces:**
- Consumes: the running app on a random port (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`).
- Produces: the executable `@standalone` catalogue: **P0–P6, P8, N1, N3, N4, N5, N11, E1–E5, E8** (tags match spec §9.3; @full rows wait for Plans 2–3).

- [ ] **Step 1:** Runner + one feature first (N1, the spec's own §9.3 example, verbatim with `ACC-001`), steps driving HTTP only. Run: FAIL (no steps). Implement steps; green.
- [ ] **Step 2:** Add the remaining features one at a time, red→green each:
each scenario's Given/When/Then asserts exactly its catalogue row (§9.3) — P6 asserts `200` + identical body + single credit; N3 drives two concurrent PUTs with the same `expectedVersion` via two threads and asserts one `201` + retry success; N11 asserts `409` + original untouched; E1/E2 use the gate + Awaitility (never `Thread.sleep`); E8 rebuilds the projection from the stream and compares.
- [ ] **Step 3:** `./mvnw -q verify` — the whole build green: unit, architecture, contract, `@WebMvcTest`, Cucumber.
- [ ] **Step 4: Commit** — `git commit -am "test: @standalone Cucumber catalogue P0-P8/N1-N11/E1-E8 subset (spec §9.3)"`

---

### Task 13: README, curl examples, governance delta (spec §14 step 4 close-out)

**Files:**
- Create: `README.md` — quickstart (`./mvnw spring-boot:run`, the Task 11 curl sequence verbatim), the two run modes table, `AUTH DISABLED (standalone)` note, auditor-endpoints-501 note, link to spec/INDEX
- Modify: `CHANGELOG.md`, `docs/INDEX.md`, `docs/governance-baseline.md` (prune items the scaffold now satisfies)

- [ ] **Step 1:** Write README; run its curl block against a running app by hand — every command must work as pasted (§8.3's promise; the extracted-runner lands in Plan 4).
- [ ] **Step 2:** `python scripts/ci/check_docs_governance.py` — expected: fewer known items than baseline, 0 new; prune the baseline accordingly.
- [ ] **Step 3:** `./mvnw -q verify` one final time.
- [ ] **Step 4: Commit** — `git commit -am "docs: README quickstart, changelog, governance baseline pruned (plan 1 complete)"`

---

## Self-Review (performed at authoring time)

- **Spec coverage (steps 0–4):** step 0 → Task 0; step 1 → Task 1; step 2 → Tasks 2–7 (domain +
  in-memory store, *no endpoints*, honouring §5); step 3 → Task 10; step 4 → Tasks 11–13 (endpoints
  + features). §4.1 ordering → Task 5 test `foreignCallerIsRefusedBeforeAnyIdempotencyAnswer`;
  §6.3 table → Tasks 5/11; §9.2 → Task 4; §9.2b → Task 6 (+TTL in Task 8); §4.6 shapes → Tasks 10/11;
  §1 guard/banner/bind → Task 7; N12's mechanism → Task 8 (scenario itself is @full, Plan 3).
- **Placeholder scan:** Task 8 step 1 lists behaviour contracts as named tests with explicit
  fixtures rather than full listings — each names its exact assertions; no TBD/TODO anywhere.
  Task 10's YAML is specified by operation list + schema requirements + §7 rather than inline —
  the §7 table and §6.5 catalogue in the spec are the field-level source the implementer copies from.
- **Type consistency:** `MovementResult`/`Outcome` used identically in Tasks 5 and 11;
  `HistoryQuery/HistoryPage` in Tasks 8, 10, 11; `findByMovementUid` present in port (T5), fake (T5),
  contract (T6), store (T6); `balanceAfter` on events (T3) → `TransactionView` (T8) → wire (T10).
