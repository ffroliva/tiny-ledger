# Admin on-behalf-of (`ledger:admin`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `ledger:admin` — an operator who may record a deposit or withdrawal on an
account they do not own, acting on the owner's behalf, while remaining unable to read that account's
balance, history or the audit trail. This turns the repaired design in
`docs/superpowers/plans/2026-08-04-spec-admin-on-behalf-of-proposal.md` (decisions D1–D8) into
executable tasks against the code as it exists on `phase-4-plan3-hardening` today.

**Architecture:** Exactly one comparison point widens: the ownership check inside
`RecordMovementService`, for change operations only. Every other authorisation site — the read-model
decorator (`AuthorizedUseCases`), the strong read (`StrongBalanceService`), the accounts collection
(`AccountsQueryService`/`BalanceController`), and the auditor routes (`SecurityConfig`'s filter
chain) — is untouched by this plan; each task that could plausibly widen one of them instead adds a
test proving it still refuses `ledger:admin`. The acting principal is carried on the event itself
(`LedgerEvent.actor()`), stamped by the aggregate from the command's `caller`, and reaches the audit
trail over the same Kafka header transport `FullAdapterConfig` already uses for `event-type`,
`stream-version` and `occurred-at` — a fourth unconditional header, not a payload parse, so
`AuditKafkaListener`'s stated independence from the ledger's JSON shape holds.

**Tech Stack:** Java 25, Spring Boot 4.1.0 (**Spring Security 7.x**, not 6.5), Spring Modulith 2.1.0,
Spring Kafka, Liquibase, JUnit 5 + AssertJ, Testcontainers (Postgres/Redis/Kafka/Keycloak),
`tools.jackson` (Jackson 3.1.4) — the project's actual Jackson coordinates; do not add
`com.fasterxml.jackson.*` imports anywhere in this plan.

## Global Constraints

- Boot 4.1.0 → **Spring Security 7.x**, not 6.5.
- **Never run `-Pit` locally.** Push and read CI with `gh run watch`. `./mvnw -q verify` (no
  profile) stays the local command — it starts zero containers and skips every `*IT.java`.
- **Never two Maven builds in one tree.** One `./mvnw -q verify` per checkpoint, not one per task.
- **`verify` must start zero containers** — Testcontainers only activate under `*IT` classes, which
  Surefire's default excludes already skip.
- **One Spring test context** — `AbstractIntegrationTest` is the only `@SpringBootTest`; no task in
  this plan adds a per-class `@Import`, `@TestConfiguration` or `@TestPropertySource`. `missCount`
  stays `1`.
- **Baseline at the time of writing: 163 unit / 52 integration, CI-green.** Every task's verification
  step states the new counts and the run conclusion, not just "tests pass."
- **Prove every gate by deliberately violating it** where a task adds a new automated check (e.g. run
  a new test with a typo'd `-Dtest` value and confirm the build now fails loudly —
  `surefire.failIfNoSpecifiedTests=true` / failsafe's `failIfNoSpecifiedTests=true` are both already
  set in `pom.xml`, so a class-name typo is a red build, not a silent green one).
- **Spec text lands in the same commit as the code implementing it.** Every task below edits
  `docs/spec.md` itself, in the same commit as the code — there is no trailing "update the docs" task.
  Task 6 is the one exception, and it is metadata only (the version header and the revision-history
  row summarising what already landed in Tasks 1–5), not new behavioural prose.
- Commit with `git commit -F - <<'EOF' … EOF` (bash heredoc, never a PowerShell here-string), listing
  every changed path explicitly, never `git add -A`. Never merge — push the branch.
- **Spotless enforces `palantirJavaFormat` on `verify`** (`spotless:check` is bound to it, with no
  `apply` goal bound anywhere). This plan's hand-written code blocks are not guaranteed to already
  match its canonical output. If `./mvnw -q verify` fails on `spotless:check`, run
  `./mvnw -q spotless:apply` and re-verify — do not hand-tune whitespace to satisfy it.
- **Do not run Maven** while executing this plan is fine (the constraint in the authoring brief was
  for the plan-writing pass) — every task's steps below assume the executing engineer *does* run
  `./mvnw -q verify` locally and pushes for `-Pit`/CI.

### What this plan does **not** touch

No admin UI, no impersonation header, no token exchange (§13 already declares delegation protocols a
non-goal; D5 makes on-behalf-of implicit). No Python CLI, no pytest-bdd — neither exists in this
repository yet (`§11` is spec'd, unbuilt; `find … -iname "*.py"` returns nothing under `src` or a
`ledger-cli` tree). No seed script pinning deterministic `accountUid`s to realm fixtures — the same
reason: nothing in this repository consumes it yet, and this plan's own tests do not need it (see
Task 5's note on how P9 addresses `alice`'s account without one). Neither gap is introduced by this
plan; both are pre-existing, and v3.10's "Known divergences" table already tracks the seed-script one.

### Corrections made against the proposal while writing this plan (verify, don't assume)

1. **Migration numbering.** The proposal's impact inventory names the new changeset
   `004-add-audit-actor.sql`. `004` is already taken by `changes/004-init-event-publication.sql`
   (checked via `src/main/resources/db/changelog/db.changelog-master.xml`). This plan's changeset is
   `005-add-audit-actor.sql`.
2. **P9's account-addressing problem is specific to the unbuilt Python CLI, not to this plan.** The
   proposal's long note under P9 explains why `trent` cannot resolve `alice`'s account through
   `GET /api/v1/accounts` (he owns nothing, and widening that route is forbidden by D8), and proposes
   deterministic `accountUid` pinning as the fix for the CLI/pytest-bdd layer that reads scenarios by
   account *name*. That layer does not exist in this repository. This plan's P9 is a JUnit
   `MockMvc` test (matching how N1–N12 are actually implemented today — see the note under Task 5) that
   captures `alicesAccount`'s UUID directly from the `201`'s response body, the same way
   `SecurityConfigIT.openAnAccountAs` already does for every existing ownership test. No realm change,
   no seed script, no CLI involved. When `§11`'s CLI is eventually built, it will still need the
   pinning mechanism (or an equivalent) to drive P9 through pytest-bdd — that remains correctly
   deferred, unchanged by this plan.
3. **The `§6.4` enforcement-sites table conflates two comparison points as one row.** v3.9's table (the
   proposal's `§3.5`/`§3.7` edits predate it and are marked `[superseded]`/restored respectively) has
   one row for "changes state, or reads at the aggregate's version" covering both
   `RecordMovementService` and `StrongBalanceService` under "the service." Since D1 makes exactly one
   of those two widen, Task 2 splits that row in two so the table itself states which one moves.

## OPEN items

**None block this plan.** Every gap the repaired proposal left for "the implementing plan" to decide
(the Kafka-header-vs-payload-parse transport in D3/D4, the migration number, P9's addressing) is
decided above or in the task it belongs to, with its justification inline. The proposal's own two
open questions (feed transparency, trail queryability) are already closed as proposed in its Status
block — no action needed here.

---

## Task 1: `actor` carried on every domain event

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/domain/LedgerEvent.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/domain/MoneyDeposited.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/domain/MoneyWithdrawn.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/domain/MovementRejected.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/domain/AccountOpened.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/domain/Account.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/contract/EventStoreContract.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/balance/adapter/out/inmemory/InMemoryBalanceProjectionTest.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/balance/adapter/out/postgres/PostgresBalanceProjectionIT.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/balance/application/BalanceProjectorTest.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/notification/NotificationRulesTest.java`
- Create: `src/test/java/com/ffroliva/tinyledger/ledger/adapter/out/postgres/PostgresEventStoreLegacyPayloadTest.java`
- Modify: `docs/spec.md` (§2.3, §2.4, §4.1)

**Interfaces:**
- Produces: `LedgerEvent.actor()` — `String`, declared on the sealed interface beside `accountId()`,
  `version()`, `occurredAt()`. `MoneyDeposited`/`MoneyWithdrawn`/`MovementRejected` carry it as the
  **last** record component (positional — every constructor call site changes). `AccountOpened`
  implements it as `return owner();` — no new component, never null.
- Consumes (Task 2 relies on this): `Account.deposit(Deposit cmd, Instant now)` and
  `Account.withdraw(Withdraw cmd, Instant now)` stamp `cmd.caller()` as `actor` on every event they
  emit — Task 2 does not change this method's signature, only what `Deposit`/`Withdraw` themselves carry.

### Step 1: Write the failing domain test

Add to `src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java`, after
`depositIncrementsVersionByExactlyOneAndCarriesBalanceAfter`:

```java
@Test // §2.3/§2.4: the use case stamps the caller onto every event it emits, as `actor`
void depositStampsTheCallerAsActor() {
    Account account = openedWith(0);
    List<LedgerEvent> events = account.deposit(
            new Deposit("alice", account.id(), UUID.randomUUID(), new Money(GBP, 10_000), "rent"), T);
    MoneyDeposited deposited = (MoneyDeposited) events.getFirst();
    assertThat(deposited.actor()).isEqualTo("alice");
}

@Test // AccountOpened has no on-behalf-of form (§15.8) — actor is always the owner, never a component
void accountOpenedDerivesActorFromOwner() {
    List<LedgerEvent> events = Account.open(AccountId.random(), new OpenAccount("alice", "ACC-001", GBP), T);
    AccountOpened opened = (AccountOpened) events.getFirst();
    assertThat(opened.actor()).isEqualTo("alice");
}

@Test // a rejection is audit-relevant too (§2.3) — it also carries who attempted it
void rejectionStampsTheCallerAsActor() {
    Account account = openedWith(5_000);
    List<LedgerEvent> events =
            account.withdraw(new Withdraw("alice", account.id(), UUID.randomUUID(), new Money(GBP, 10_000), null), T);
    assertThat(((MovementRejected) events.getFirst()).actor()).isEqualTo("alice");
}
```

Note: `Deposit`/`Withdraw` in this file are constructed with today's 5-arg signature — Task 1 does
not change those two records (that is Task 2). Only the *events* gain the new component here.

### Step 2: Run the new tests to verify they fail

Run: `./mvnw -q test -Dtest=AccountTest`
Expected: **compile failure** — `actor()` does not exist yet on `MoneyDeposited`/`AccountOpened`/
`MovementRejected`. This is the correct RED: the type doesn't exist, not merely a wrong assertion.

### Step 3: Add `actor()` to `LedgerEvent` and the four event records

`src/main/java/com/ffroliva/tinyledger/ledger/domain/LedgerEvent.java` — full replacement:

```java
package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;

public sealed interface LedgerEvent permits AccountOpened, MoneyDeposited, MoneyWithdrawn, MovementRejected {
    AccountId accountId();

    long version();

    Instant occurredAt();

    /**
     * §2.3/§2.4: the principal that issued the command. On the three movement events it is a record
     * component, stamped by the use case (Account.deposit/withdraw) from the command's caller — never
     * from the account's owner. On {@link AccountOpened} it is derived from {@code owner()}: an account
     * has no owner to act on behalf of until it exists (§15.8). A legacy payload written before this
     * field existed deserialises with it absent (null) rather than failing to read at all — §15.9
     * records how that absence is interpreted.
     */
    String actor();
}
```

`src/main/java/com/ffroliva/tinyledger/ledger/domain/MoneyDeposited.java` — full replacement:

```java
package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record MoneyDeposited(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        Money amount,
        String reference,
        Money balanceAfter,
        String actor)
        implements LedgerEvent {}
```

`src/main/java/com/ffroliva/tinyledger/ledger/domain/MoneyWithdrawn.java` — same shape:

```java
package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record MoneyWithdrawn(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        Money amount,
        String reference,
        Money balanceAfter,
        String actor)
        implements LedgerEvent {}
```

`src/main/java/com/ffroliva/tinyledger/ledger/domain/MovementRejected.java` — full replacement:

```java
package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record MovementRejected(
        AccountId accountId,
        long version,
        Instant occurredAt,
        UUID movementUid,
        MovementType type,
        Money amount,
        String reason,
        String actor)
        implements LedgerEvent {}
```

`src/main/java/com/ffroliva/tinyledger/ledger/domain/AccountOpened.java` — add the derived method,
no new record component:

```java
package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;
import java.util.Currency;

public record AccountOpened(
        AccountId accountId, long version, Instant occurredAt, String owner, String name, Currency currency)
        implements LedgerEvent {

    // §15.8: an account has no owner to act on behalf of until it exists — actor is always the owner,
    // never a separate fact, so this is a derivation, not a fourth stamped field.
    @Override
    public String actor() {
        return owner;
    }
}
```

### Step 4: Stamp `actor` in `Account.deposit`/`Account.withdraw`

`src/main/java/com/ffroliva/tinyledger/ledger/domain/Account.java` — replace the `deposit` and
`withdraw` methods:

```java
    public List<LedgerEvent> deposit(Deposit cmd, Instant now) {
        requirePositive(cmd.amount());
        if (!currency.equals(cmd.amount().currency())) {
            return List.of(new MovementRejected(
                    id,
                    version + 1,
                    now,
                    cmd.movementUid(),
                    MovementType.DEPOSIT,
                    cmd.amount(),
                    "currency-mismatch",
                    cmd.caller()));
        }
        Money after = balance().plus(cmd.amount());
        return List.of(new MoneyDeposited(
                id, version + 1, now, cmd.movementUid(), cmd.amount(), cmd.reference(), after, cmd.caller()));
    }

    public List<LedgerEvent> withdraw(Withdraw cmd, Instant now) {
        requirePositive(cmd.amount());
        if (!currency.equals(cmd.amount().currency())) {
            return List.of(new MovementRejected(
                    id,
                    version + 1,
                    now,
                    cmd.movementUid(),
                    MovementType.WITHDRAWAL,
                    cmd.amount(),
                    "currency-mismatch",
                    cmd.caller()));
        }
        Money after = balance().minus(cmd.amount());
        if (!OverdraftPolicy.permits(after)) {
            return List.of(new MovementRejected(
                    id,
                    version + 1,
                    now,
                    cmd.movementUid(),
                    MovementType.WITHDRAWAL,
                    cmd.amount(),
                    "insufficient-funds",
                    cmd.caller()));
        }
        return List.of(new MoneyWithdrawn(
                id, version + 1, now, cmd.movementUid(), cmd.amount(), cmd.reference(), after, cmd.caller()));
    }
```

The `apply(LedgerEvent event)` switch and every other method in `Account.java` is unchanged — `actor`
is never read by aggregate state, only carried on the event for the audit trail (Task 4).

### Step 5: Fix every other construction site — the compiler finds them all

These records are positional, so a missed site is a **compile error**, not a silent bug — run
`./mvnw -q test-compile` after Step 3/4 and fix everything it names before proceeding. The complete
list, each getting one new trailing argument (`null` — none of these tests assert on `actor`, so the
literal value is immaterial):

- `src/test/java/com/ffroliva/tinyledger/contract/EventStoreContract.java:28` —
  `return new MoneyDeposited(id, version, T, uid, amount, null, new Money(GBP, 1_000 * (version - 1)), null);`
- `src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java:107` —
  `gapped.add(new MoneyDeposited(id, 3, T, UUID.randomUUID(), new Money(GBP, 100), null, new Money(GBP, 100), null));`
- `src/test/java/com/ffroliva/tinyledger/balance/adapter/out/inmemory/InMemoryBalanceProjectionTest.java` —
  three `new MoneyDeposited(` calls (around lines 69, 89, 103): append `, null` before the final `)`
  of each.
- `src/test/java/com/ffroliva/tinyledger/balance/adapter/out/postgres/PostgresBalanceProjectionIT.java` —
  seven `new MoneyDeposited(` calls (around lines 65, 84, 109, 126, 144, 161, 199): same, append `, null`.
- `src/test/java/com/ffroliva/tinyledger/balance/application/BalanceProjectorTest.java` — the
  `deposit(...)` helper (around line 194) and the `withdraw(...)` helper (around line 199) each build
  one event; append `, null` to each. The inline `new MovementRejected(...)` in
  `rejectionsAffectNeitherBalanceNorHistory` (around line 78) needs `, null` too. Example — the
  `deposit` helper becomes:

  ```java
  private static MoneyDeposited deposit(AccountId id, long version, long amount, long balanceAfter, Instant at) {
      return new MoneyDeposited(
              id, version, at, UUID.randomUUID(), new Money(GBP, amount), "ref", new Money(GBP, balanceAfter), null);
  }
  ```
- `src/test/java/com/ffroliva/tinyledger/notification/NotificationRulesTest.java` — three
  `new MoneyDeposited(` calls (lines 27, 41, 49), one `new MoneyWithdrawn(` (line 63), one
  `new MovementRejected(` (line 73): append `, null` to each. `new AccountOpened(...)` (line 86) is
  **unchanged** — no new component there. Example — line 27 becomes:

  ```java
  MoneyDeposited event = new MoneyDeposited(
          account, 2, T0, movementUid, new Money(GBP, 1_500_000), "ref", new Money(GBP, 1_500_000), null);
  ```

`AccountOpened(...)` call sites everywhere else in the test tree (there are many, across
`InMemoryBalanceProjectionTest`, `PostgresBalanceProjectionIT`, `BalanceProjectorTest`,
`StrongBalanceServiceTest`, `EventStoreContract`) are **unaffected** — confirm this by running
`./mvnw -q test-compile` and observing no errors are reported against any `AccountOpened(` line.

### Step 6: Run the domain tests to verify they pass

Run: `./mvnw -q test -Dtest=AccountTest,EventStoreContract`
Expected: PASS, including the three new tests from Step 1.

Run the full unit suite once to confirm every fixed-up call site compiles and its test still passes:
Run: `./mvnw -q test`
Expected: PASS — unit count rises from 163 to **166** (three new `AccountTest` methods; the
call-site fixes in Step 5 are not new tests, they are existing tests updated to compile).

### Step 7: Prove a legacy payload (no `actor` key) still deserialises

Write `src/test/java/com/ffroliva/tinyledger/ledger/adapter/out/postgres/PostgresEventStoreLegacyPayloadTest.java`:

```java
package com.ffroliva.tinyledger.ledger.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.ledger.domain.MoneyDeposited;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * §15.9: a payload written before this feature has no `actor` key at all. `PostgresEventStore`
 * deserialises with the same plain {@link ObjectMapper} it uses in production — no custom module, no
 * {@code @JsonCreator} anywhere on these records — so this proves a legacy row still reads, with
 * {@code actor()} absent, rather than the read failing outright.
 */
class PostgresEventStoreLegacyPayloadTest {

    @Test
    void aPayloadWithNoActorKeyDeserialisesWithActorAbsent() {
        ObjectMapper mapper = new ObjectMapper();
        MoneyDeposited withActor = new MoneyDeposited(
                AccountId.random(),
                2,
                Instant.parse("2026-08-01T00:00:00Z"),
                UUID.randomUUID(),
                new Money(Currency.getInstance("GBP"), 100),
                "rent",
                new Money(Currency.getInstance("GBP"), 100),
                "alice");

        // Round-trips today's shape, then strips the key a pre-feature payload never had, rather than
        // hand-writing the nested AccountId/Money/Currency JSON shape and risking it drifting from
        // what PostgresEventStore actually stores.
        ObjectNode legacy = (ObjectNode) mapper.readTree(mapper.writeValueAsString(withActor));
        legacy.remove("actor");

        MoneyDeposited deserialised = mapper.treeToValue(legacy, MoneyDeposited.class);

        assertThat(deserialised.actor()).isNull();
        assertThat(deserialised.amount()).isEqualTo(new Money(Currency.getInstance("GBP"), 100));
    }
}
```

Run: `./mvnw -q test -Dtest=PostgresEventStoreLegacyPayloadTest`
Expected: FAIL first if run before Step 3 lands (no `actor()` to strip/assert on) — since this repo's
task order already has Step 3 done by now, run it once, expect PASS. Then run
`./mvnw -q test -Dtest=Typo` deliberately: expect a **build failure** (`failIfNoSpecifiedTests=true`),
not a silent green — this is the constraint from the Global Constraints section, proven once here so
later tasks can just state the count.

### Step 8: `docs/spec.md` — §2.3, §2.4, §4.1

Edit `docs/spec.md`. In §2.3 (Domain events), replace the event table's last three rows and the line
after it:

Current:
```
| `MoneyDeposited` | Funds credited. |
| `MoneyWithdrawn` | Funds debited after invariant checks pass. |
| `MovementRejected` | A command failed a business invariant. Recorded, not thrown away — rejections are audit-relevant. |

Events are the write model's source of truth. Nothing else is.
```

Replace with:
```
| `MoneyDeposited` | Funds credited. Carries the **`actor`**. |
| `MoneyWithdrawn` | Funds debited after invariant checks pass. Carries the **`actor`**. |
| `MovementRejected` | A command failed a business invariant. Recorded, not thrown away — rejections are audit-relevant. Carries the **`actor`**. |

Every event answers **`actor()`** — the principal that issued the command — declared on the sealed
`LedgerEvent` interface beside `accountId`, `version` and `occurredAt`, so the audit projection maps
one accessor instead of switching on type. On the three movement events it is a record component; on
`AccountOpened` it is derived from `owner`, because an account has no owner to act on behalf of until
it exists (§15.8). For an owner-initiated movement `actor` equals the stream's `owner`; when an admin
acts on the owner's behalf (§6.4) the pair `(actor, owner)` is the whole record of the delegation —
one immutable row answering both *who acted* and *whose account it was*.

Events are the write model's source of truth. Nothing else is.
```

In §2.4 (Commands), the first sentence gains one more:

Current:
```
Every command carries the **caller principal** (the JWT subject; a fixed local principal in
`standalone`) — authorisation is a use-case concern (§6.4), and a use case cannot check what it
never receives.
```

Replace with:
```
Every command carries the **caller principal** (the JWT subject; a fixed local principal in
`standalone`) — authorisation is a use-case concern (§6.4), and a use case cannot check what it
never receives. The principal is not only checked: the use case stamps it onto every event it emits
as the `actor` (§2.3), so *who acted* survives in the log rather than only in a request that is
already gone.
```

In §4.1 (Write path), step 4:

Current:
```
4. Command applied; the aggregate emits events or rejects.
```

Replace with:
```
4. Command applied; the aggregate emits events or rejects — each emitted event stamped with the
   caller principal as its `actor` (§2.3).
```

### Step 9: Verify and commit

Run: `./mvnw -q verify`
Expected: green, zero containers started, unit count **166**.

```bash
git commit -F - -- \
  src/main/java/com/ffroliva/tinyledger/ledger/domain/LedgerEvent.java \
  src/main/java/com/ffroliva/tinyledger/ledger/domain/MoneyDeposited.java \
  src/main/java/com/ffroliva/tinyledger/ledger/domain/MoneyWithdrawn.java \
  src/main/java/com/ffroliva/tinyledger/ledger/domain/MovementRejected.java \
  src/main/java/com/ffroliva/tinyledger/ledger/domain/AccountOpened.java \
  src/main/java/com/ffroliva/tinyledger/ledger/domain/Account.java \
  src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java \
  src/test/java/com/ffroliva/tinyledger/contract/EventStoreContract.java \
  src/test/java/com/ffroliva/tinyledger/balance/adapter/out/inmemory/InMemoryBalanceProjectionTest.java \
  src/test/java/com/ffroliva/tinyledger/balance/adapter/out/postgres/PostgresBalanceProjectionIT.java \
  src/test/java/com/ffroliva/tinyledger/balance/application/BalanceProjectorTest.java \
  src/test/java/com/ffroliva/tinyledger/notification/NotificationRulesTest.java \
  src/test/java/com/ffroliva/tinyledger/ledger/adapter/out/postgres/PostgresEventStoreLegacyPayloadTest.java \
  docs/spec.md <<'EOF'
feat: carry the acting principal on every ledger event

`LedgerEvent.actor()` — a record component on the three movement events, derived from `owner` on
`AccountOpened` since an account has no owner to act on behalf of until it exists. `Account.deposit`/
`withdraw` stamp the command's caller, not the account's owner, so a future admin-performed movement
records who actually acted. A legacy payload with no `actor` key still deserialises, actor absent —
proven directly against the production ObjectMapper rather than assumed.

docs/spec.md §2.3/§2.4/§4.1 updated in this commit, not a trailing one.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
```

---

## Task 2: `ledger:admin` widens `RecordMovementService`'s ownership check

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/application/port/in/Deposit.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/application/port/in/Withdraw.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/application/usecase/RecordMovementService.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/platform/CallerPrincipal.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerController.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/ledger/application/RecordMovementServiceTest.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/audit/KafkaAuditModuleIT.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/balance/adapter/in/events/LedgerEventsListenerTest.java`
- Modify: `docs/spec.md` (§4.1, §6.4, §6.5, §13, §15)

**Interfaces:**
- Produces: `Deposit(String caller, boolean callerIsAdmin, AccountId accountId, UUID movementUid, Money amount, String reference)`
  and the identically-shaped `Withdraw` — `callerIsAdmin` is the second component, right after
  `caller`. `CallerPrincipal.isAdmin()` — `boolean`, `false` whenever there is no `JwtAuthenticationToken`
  (i.e. always `false` in `standalone`).
- Consumes: Task 1's `Account.deposit(Deposit cmd, Instant now)`/`Account.withdraw(Withdraw cmd, Instant now)`
  — unchanged signatures, `cmd.caller()` still supplies `actor`.

### Step 1: Write the failing use-case tests

Add to `src/test/java/com/ffroliva/tinyledger/ledger/application/RecordMovementServiceTest.java`,
after `foreignCallerIsRefusedBeforeAnyIdempotencyAnswer`:

```java
@Test // §6.4 D1: the ONE comparison point ledger:admin widens — a change operation, not a read
void adminCanDepositOnAnAccountTheyDoNotOwn() {
    MovementResult result = service.deposit(
            new Deposit("trent", true, opened, UUID.randomUUID(), new Money(GBP, 10_000), null));
    assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
}

@Test // the actor stamped is the admin who acted — the owner on the stream never changes
void adminDepositRecordsTheAdminAsActorAndLeavesTheOwnerUnchanged() {
    service.deposit(new Deposit("trent", true, opened, UUID.randomUUID(), new Money(GBP, 10_000), null));

    MoneyDeposited deposited = (MoneyDeposited) published.getLast();
    assertThat(deposited.actor()).isEqualTo("trent");

    AccountOpened openedEvent = (AccountOpened) store.read(opened).getFirst();
    assertThat(openedEvent.owner()).isEqualTo("alice");
}

@Test // the control: same caller, same account, callerIsAdmin=false — proves the flag gates the widening
void nonAdminCallerStillCannotDepositOnAnAccountTheyDoNotOwn() {
    assertThatThrownBy(() -> service.deposit(
                    new Deposit("mallory", false, opened, UUID.randomUUID(), new Money(GBP, 10_000), null)))
            .isInstanceOf(OwnershipException.class);
}

@Test // a movement is recorded as an event only the FIRST time it succeeds (§4.1/§4.5): the log
// records who first performed it, not everyone who later retries the same movementUid+payload —
// even when the retrier is an admin. RecordMovementService returns the replay before emitting
// anything, and replayOf's samePayload check never compares the caller.
void adminReplayingSomeoneElsesMovementUidDoesNotChangeTheRecordedActor() {
    UUID uid = UUID.randomUUID();
    service.deposit(new Deposit("alice", false, opened, uid, new Money(GBP, 10_000), null));

    MovementResult replay = service.deposit(new Deposit("trent", true, opened, uid, new Money(GBP, 10_000), null));

    assertThat(replay.outcome()).isEqualTo(Outcome.REPLAYED);
    assertThat(published).hasSize(2); // AccountOpened + the ONE MoneyDeposited — trent's retry emitted nothing
    MoneyDeposited onlyDeposit = (MoneyDeposited) published.getLast();
    assertThat(onlyDeposit.actor()).isEqualTo("alice"); // NOT trent
}
```

### Step 2: Run the new tests to verify they fail

Run: `./mvnw -q test -Dtest=RecordMovementServiceTest`
Expected: **compile failure** — `Deposit` does not have a `callerIsAdmin` component yet.

### Step 3: Widen `Deposit`/`Withdraw` and `RecordMovementService`

`src/main/java/com/ffroliva/tinyledger/ledger/application/port/in/Deposit.java` — full replacement:

```java
package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.util.UUID;

/**
 * §2.4: caller = JWT subject or the fixed standalone principal. §6.4: {@code callerIsAdmin} is
 * whether that principal holds {@code ledger:admin} — the one fact
 * {@link com.ffroliva.tinyledger.ledger.application.usecase.RecordMovementService} needs to widen its
 * ownership check that it cannot otherwise see, since {@code application} carries no Spring Security
 * type (ArchUnit, §4.5).
 */
public record Deposit(
        String caller, boolean callerIsAdmin, AccountId accountId, UUID movementUid, Money amount, String reference) {}
```

`src/main/java/com/ffroliva/tinyledger/ledger/application/port/in/Withdraw.java` — identically:

```java
package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.util.UUID;

/** §2.4/§6.4: see {@link Deposit} — same shape, same reason for `callerIsAdmin`. */
public record Withdraw(
        String caller, boolean callerIsAdmin, AccountId accountId, UUID movementUid, Money amount, String reference) {}
```

`src/main/java/com/ffroliva/tinyledger/ledger/application/usecase/RecordMovementService.java` —
replace `deposit`, `withdraw` and `record`:

```java
    @Override
    public MovementResult deposit(Deposit cmd) {
        return record(
                cmd.caller(),
                cmd.callerIsAdmin(),
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.deposit(cmd, clock.now()),
                MovementType.DEPOSIT,
                cmd.amount(),
                cmd.reference());
    }

    @Override
    public MovementResult withdraw(Withdraw cmd) {
        return record(
                cmd.caller(),
                cmd.callerIsAdmin(),
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.withdraw(cmd, clock.now()),
                MovementType.WITHDRAWAL,
                cmd.amount(),
                cmd.reference());
    }

    private MovementResult record(
            String caller,
            boolean callerIsAdmin,
            AccountId accountId,
            UUID movementUid,
            java.util.function.Function<Account, List<LedgerEvent>> action,
            MovementType type,
            com.ffroliva.tinyledger.shared.Money amount,
            String reference) {
        List<LedgerEvent> history = store.read(accountId); // ①
        if (history.isEmpty()) throw new AccountNotFoundException(accountId);
        Account account = Account.rehydrate(history); // ②
        // §6.4 D1: the role check already ran in the filter chain (SecurityConfig, ledger:writer).
        // This is the ownership term alone — ledger:admin widens it, and only it. The role term is
        // untouched: an admin without ledger:writer never reaches this line at all (N15).
        if (!account.owner().equals(caller) && !callerIsAdmin) throw new OwnershipException(caller, accountId); // ③
        Optional<LedgerEvent> existing = store.findByMovementUid(movementUid); // ④ (after authz)
        if (existing.isPresent()) return replayOf(existing.get(), accountId, type, amount, reference);
        List<LedgerEvent> events = action.apply(account); // ⑤
        try {
            store.append(accountId, account.version(), events); // ⑥
        } catch (DuplicateMovementException raced) {
            return replayOf(store.findByMovementUid(movementUid).orElseThrow(), accountId, type, amount, reference);
        }
        events.forEach(publisher::publish); // ⑦
        return resultOf(events.getFirst(), Outcome.CREATED, Outcome.REJECTED); // ⑧
    }
```

Everything else in the file (`replayOf`, `resultOf`, `movementUidOf`) is unchanged.

### Step 4: `CallerPrincipal.isAdmin()`

`src/main/java/com/ffroliva/tinyledger/platform/CallerPrincipal.java` — add, after `current()`:

```java
    /**
     * §6.4: whether the caller holds {@code ledger:admin} — the one fact
     * {@code RecordMovementService} needs to widen its ownership check. {@code standalone} has no
     * {@link Authentication} at all (permitAll, no bearer token), so this is always {@code false}
     * there — consistent with §15.8: on-behalf-of has no meaning where there is only one principal.
     */
    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt
                && jwt.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ledger:admin"));
    }
```

No new imports are needed — `Authentication`, `SecurityContextHolder` and `JwtAuthenticationToken`
are already imported in this file for `current()`.

### Step 5: Wire `LedgerController`

`src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerController.java` — replace
`putDeposit` and `putWithdrawal`:

```java
    @Override
    public ResponseEntity<Transaction> putDeposit(UUID accountUid, UUID depositUid, MovementRequest request) {
        return respond(
                recordMovement.deposit(new Deposit(
                        callerPrincipal.current(),
                        callerPrincipal.isAdmin(),
                        new AccountId(accountUid),
                        depositUid,
                        LedgerApiMapper.toMoney(request.getAmount()),
                        request.getReference())),
                request);
    }

    @Override
    public ResponseEntity<Transaction> putWithdrawal(UUID accountUid, UUID withdrawalUid, MovementRequest request) {
        return respond(
                recordMovement.withdraw(new Withdraw(
                        callerPrincipal.current(),
                        callerPrincipal.isAdmin(),
                        new AccountId(accountUid),
                        withdrawalUid,
                        LedgerApiMapper.toMoney(request.getAmount()),
                        request.getReference())),
                request);
    }
```

### Step 6: Fix every remaining `Deposit`/`Withdraw` construction site

Same discipline as Task 1 Step 5: these are positional records, `./mvnw -q test-compile` finds every
site it broke. Insert `false` as the **second** constructor argument (immediately after the caller
string) at each of the following — none of these tests assert on admin behaviour, so `false` is
correct and immaterial beyond compiling:

- `src/test/java/com/ffroliva/tinyledger/audit/KafkaAuditModuleIT.java:85,105`
- `src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java:21,44,55,66,75,87,95,115,128`
- `src/test/java/com/ffroliva/tinyledger/ledger/application/RecordMovementServiceTest.java:34,43,53,54,61,62,69,78,79,86`
  (the three tests added in Step 1 already use the new 6-arg shape and need no further change)
- `src/test/java/com/ffroliva/tinyledger/balance/adapter/in/events/LedgerEventsListenerTest.java:40`

Example — `AccountTest.java:44` goes from:
```java
new Deposit("alice", account.id(), UUID.randomUUID(), new Money(GBP, 10_000), "rent"), T);
```
to:
```java
new Deposit("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 10_000), "rent"), T);
```

Example — `KafkaAuditModuleIT.java:85` goes from:
```java
movements.deposit(new Deposit("alice", opened.accountId(), UUID.randomUUID(), Money.of("GBP", 2500), "salary"));
```
to:
```java
movements.deposit(new Deposit("alice", false, opened.accountId(), UUID.randomUUID(), Money.of("GBP", 2500), "salary"));
```

### Step 7: Run the tests to verify they pass

Run: `./mvnw -q test -Dtest=RecordMovementServiceTest`
Expected: PASS, including the four new tests.

Run: `./mvnw -q test`
Expected: PASS — unit count rises from 166 to **170**.

### Step 8: `docs/spec.md` — §4.1, §6.4, §6.5, §13, §15

In §4.1 (Write path), step 2:

Current:
```
2. Aggregate rehydrated by replaying its event stream; **ownership checked against the caller
   principal before anything else is answered** — a foreign caller gets the §6.5 refusal, never an
   idempotency oracle.
```

Replace with:
```
2. Aggregate rehydrated by replaying its event stream; **ownership checked against the caller
   principal before anything else is answered** — or, for a caller holding `ledger:admin`, the
   widened check of §6.4. A caller who satisfies neither gets the §6.5 refusal, never an idempotency
   oracle.
```

In §6.4 (Security), the role table gains one row, after `ledger:auditor`:

```
| `ledger:admin` | Widen `ledger:writer` to **any** account for change operations, acting on behalf of its owner; never widens `ledger:reader` — reads, including `?consistency=strong`, stay owner-scoped. Grants no operation on its own, and no access to the audit trail |
```

Immediately below, the enforcement-sites table's first row splits in two — this is the correction
noted at the top of this plan: v3.9's table names one row "the service" for both
`RecordMovementService` and `StrongBalanceService`, and D1 makes exactly one of the two widen, so the
table must say which.

Current:
```
**Every authorisation decision is made by the component that holds the state the decision needs.**
That principle decides where a new operation's check belongs. It yields four sites, and **this list
is closed — a fifth requires an ADR.**

| The operation | Authorised | Because |
|---|---|---|
| Changes state, or reads at the aggregate's version (`?consistency=strong`) | In the service, against the rehydrated aggregate, before the idempotency lookup (§6.3) | The decision must be taken against the same state, at the same version, the command is applied to |
| Reads a read model for one named account | A decorator wrapping the inbound port (§4.5) | The read model is the authority for a question the read model answers |
| Returns a collection the caller sees only part of | The port takes the visibility scope as a parameter (`accountsOwnedBy`) — the scope *is* the authorisation | There is no set to decorate; widening it is a port-signature change |
| Depends on role alone, with no account subject (`/audit/**`, `/accounts/*/events`) | The security filter chain in `config` | There is no subject to compare and no inbound port to decorate |
```

Replace with:
```
**Every authorisation decision is made by the component that holds the state the decision needs.**
That principle decides where a new operation's check belongs. It yields five comparison points
across four sites, and **this list is closed — a sixth requires an ADR.**

| The operation | Authorised | Because |
|---|---|---|
| Changes state (`PUT .../deposits/*`, `PUT .../withdrawals/*`) | In `RecordMovementService`, against the rehydrated aggregate, before the idempotency lookup (§6.3). Ownership admits the caller if the account's `owner` matches **or** the caller holds `ledger:admin` — the one comparison point `ledger:admin` widens | The decision must be taken against the same state, at the same version, the command is applied to |
| Reads at the aggregate's version (`?consistency=strong`) | In `StrongBalanceService`, the same in-service mechanism, **not widened** — a strong read is still a read | Same as above; `ledger:admin` is a change-operation grant, not a read grant |
| Reads a read model for one named account | A decorator wrapping the inbound port (§4.5), **not widened** | The read model is the authority for a question the read model answers |
| Returns a collection the caller sees only part of | The port takes the visibility scope as a parameter (`accountsOwnedBy`) — the scope *is* the authorisation, **not widened** (D8) | There is no set to decorate; widening it is a port-signature change |
| Depends on role alone, with no account subject (`/audit/**`, `/accounts/*/events`) | The security filter chain in `config` — `ledger:admin` is absent from both matchers | There is no subject to compare and no inbound port to decorate |
```

Further down §6.4, the test-users table gains one row, after `mallory`:

```
| `trent` | `ledger:writer`, `ledger:reader`, `ledger:admin` | — | **On-behalf-of.** Moves money on an account he does not own; the movement records `actor=trent` on `alice`'s stream while the owner stays `alice`. **403 on the audit trail** — acting and reviewing are different jobs |
```

And, immediately after the existing `mallory` paragraph (the one starting "`mallory` is the one that
earns its place."), a new paragraph:

```
`trent` is the cryptographic literature's trusted arbitrator, and the name is the point: authorised,
and still not above the record. He earns his place from the opposite side to `mallory` — `mallory`
proves the ownership comparison exists, `trent` proves the exception to it is exactly one clause
wide. A suite whose `trent` can also read the audit trail has tested a superuser and called it an
administrator.
```

Then the "ownership mechanism, end to end" paragraph:

Current:
```
**The ownership mechanism, end to end:** `AccountOpened` records the `owner` (§2.3), so ownership
is a fact of the event stream, not sidecar state; every command and query carries the caller
principal (§2.4); the use case compares the two. `mallory`'s N7 is a test of that comparison, not
of a role.
```

Replace with:
```
**The ownership mechanism, end to end:** `AccountOpened` records the `owner` (§2.3), so ownership
is a fact of the event stream, not sidecar state; every command and query carries the caller
principal (§2.4); the use case compares the two. For a command — `RecordMovementService` alone —
that comparison admits the caller if they hold `ledger:admin`; every query's comparison,
`StrongBalanceService`'s strong read included, is untouched, because admin widens change operations
only, never reads. Every event a command then emits records the caller as its `actor` (§2.3), so an
admin-performed movement carries both halves of the answer an investigation needs — *who acted* and
*whose account it was* — on the same immutable row, and the audit trail surfaces the pair (§7).
`mallory`'s N7 is a test of the comparison, not of a role; `trent`'s P9 and N13–N18 are tests that
the admin clause widened the write comparison, and only the write comparison.
```

In §6.5 (Error handling), the 403 row:

Current:
```
| Forbidden — wrong role *or* wrong owner | 403 | `/errors/forbidden` |
```

Replace with:
```
| Forbidden — wrong role, or (on a change operation) wrong owner without `ledger:admin` | 403 | `/errors/forbidden` |
```

In §13 (Non-goals), add one bullet:

```
- Delegation and impersonation protocols. An admin acts under their own identity and their own
  token (§15.8); OAuth 2.0 Token Exchange (RFC 8693) — a console minting a scoped, time-boxed
  on-behalf-of token — is the recorded production upgrade path, and is not built here.
```

In §15 (Documented assumptions), add item 8 (item 9 lands in Task 4, alongside the cutover logic it
describes):

```
8. On-behalf-of is **implicit**: an admin acts under their own identity and their own JWT, against
   the same endpoints, and the operation is "on behalf of" the owner purely because it targets that
   owner's account. There is no impersonation header, no delegation token and no token exchange
   (§13). Account *opening* has no on-behalf-of form — an account has no owner until it exists.
```

### Step 9: Verify and commit

Run: `./mvnw -q verify`
Expected: green, zero containers, unit count **170**.

```bash
git commit -F - -- \
  src/main/java/com/ffroliva/tinyledger/ledger/application/port/in/Deposit.java \
  src/main/java/com/ffroliva/tinyledger/ledger/application/port/in/Withdraw.java \
  src/main/java/com/ffroliva/tinyledger/ledger/application/usecase/RecordMovementService.java \
  src/main/java/com/ffroliva/tinyledger/platform/CallerPrincipal.java \
  src/main/java/com/ffroliva/tinyledger/ledger/adapter/in/web/LedgerController.java \
  src/test/java/com/ffroliva/tinyledger/ledger/application/RecordMovementServiceTest.java \
  src/test/java/com/ffroliva/tinyledger/ledger/domain/AccountTest.java \
  src/test/java/com/ffroliva/tinyledger/audit/KafkaAuditModuleIT.java \
  src/test/java/com/ffroliva/tinyledger/balance/adapter/in/events/LedgerEventsListenerTest.java \
  docs/spec.md <<'EOF'
feat: ledger:admin widens RecordMovementService's ownership check only

D1: exactly one comparison point widens — the ownership check inside RecordMovementService, for
change operations only. CallerPrincipal.isAdmin() reads the JWT's authorities (always false in
standalone, matching D6). StrongBalanceService, AuthorizedUseCases and the accounts collection are
untouched; SecurityConfig is untouched — an admin without ledger:writer never reaches the widened
comparison at all (the role and ownership terms are conjunctive, not one bypass).

docs/spec.md §4.1/§6.4/§6.5/§13/§15 item 8 updated in this commit.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
```

---

## Task 3: Realm fixture — `ledger:admin` role and `trent`

**Files:**
- Modify: `docker/keycloak/realm-tiny-ledger.json`
- Modify: `src/test/java/com/ffroliva/tinyledger/testsupport/KeycloakTokens.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java`
- Modify: `docs/spec.md` (§6.4 — already fully edited by Task 2; this task's spec change is none,
  see note below)

**Interfaces:**
- Produces: `trent`, a Keycloak fixture user, pinned subject `00000000-0000-4000-8000-000000000007`,
  holding `ledger:writer`, `ledger:reader`, `ledger:admin`, owning no account. `KeycloakTokens.SUBJECTS`
  gains `"trent" → "00000000-0000-4000-8000-000000000007"`, consumed by Task 5's tests.

Note: the §6.4 test-users table row and the `trent` paragraph are spec **prose** describing this
fixture's *behaviour*, which only becomes true once Task 2's code exists — they were placed in Task
2's commit, alongside the ownership-mechanism edit they belong next to. This task adds no further
spec prose; it only makes the fixture Task 2's spec already describes actually exist.

### Step 1: Write the failing fixture test

Add to `src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java`, after
`theMintedTokenSubjectMatchesThePinnedFixture`:

```java
@Test // same reason as theMintedTokenSubjectMatchesThePinnedFixture, for the new fixture user
void theMintedTokenSubjectMatchesThePinnedFixtureForTrent() throws Exception {
    String token = KeycloakTokens.accessToken(issuerUri(), "trent");
    assertThat(SignedJWT.parse(token).getJWTClaimsSet().getSubject())
            .isEqualTo(KeycloakTokens.SUBJECTS.get("trent"));
}
```

No new imports — `SignedJWT` and `KeycloakTokens` are already imported in this file.

### Step 2: Run it to verify it fails

This is an `*IT` — cannot run locally without `-Pit`, and this plan's Global Constraints forbid
running `-Pit` locally. Push after Step 4 and read CI (`gh run watch`); expect this test to fail
first (`trent` unresolvable — `KeycloakTokens.SUBJECTS.get("trent")` is `null`, and the realm has no
such user, so `KeycloakTokens.accessToken` throws `IllegalStateException` on the 401/400 from
Keycloak) before the fixture exists.

### Step 3: Add the role and the user

`docker/keycloak/realm-tiny-ledger.json` — add `ledger:admin` to the `roles.realm` array, after
`ledger:auditor`:

```json
      { "name": "ledger:auditor", "description": "Read the audit trail across all accounts; no writes" },
      { "name": "ledger:admin",   "description": "Move money on any account, acting on behalf of its owner; no reads, no audit trail" }
```

Add a new user object to the `users` array, after `mallory` and before `nobody`:

```json
    { "id": "00000000-0000-4000-8000-000000000007", "username": "trent",   "enabled": true,
      "email": "trent@tiny-ledger.test",   "emailVerified": true, "firstName": "Trent",   "lastName": "Fixture",
      "credentials": [{ "type": "password", "value": "dev-only", "temporary": false }],
      "realmRoles": ["ledger:writer", "ledger:reader", "ledger:admin"] },
```

### Step 4: Pin the subject in `KeycloakTokens`

`src/test/java/com/ffroliva/tinyledger/testsupport/KeycloakTokens.java` — add to the `SUBJECTS` map:

```java
    public static final Map<String, String> SUBJECTS = Map.of(
            "alice", "00000000-0000-4000-8000-000000000001",
            "bob", "00000000-0000-4000-8000-000000000002",
            "carol", "00000000-0000-4000-8000-000000000003",
            "dave", "00000000-0000-4000-8000-000000000004",
            "mallory", "00000000-0000-4000-8000-000000000005",
            "nobody", "00000000-0000-4000-8000-000000000006",
            "trent", "00000000-0000-4000-8000-000000000007");
```

(`Map.of` supports up to 10 pairs — 14 arguments here is fine, no signature change needed.)

### Step 5: Push and verify on CI

```bash
git commit -F - -- \
  docker/keycloak/realm-tiny-ledger.json \
  src/test/java/com/ffroliva/tinyledger/testsupport/KeycloakTokens.java \
  src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java <<'EOF'
feat: add ledger:admin role and trent fixture user to the Keycloak realm

D6: trent holds ledger:writer, ledger:reader, ledger:admin and owns no account — the classic cast's
trusted arbitrator, authorised and still not above the record. Pinned subject
00000000-0000-4000-8000-000000000007, following the existing fixture numbering.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
git push
gh run watch
```

Expected: the new test passes; integration count rises from 52 to **53**. Confirm the run
conclusion is `success`, not just that the job started.

---

## Task 4: The audit trail surfaces `actor`

**Files:**
- Create: `src/main/resources/db/changelog/changes/005-add-audit-actor.sql`
- Modify: `src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `src/main/java/com/ffroliva/tinyledger/audit/application/port/out/AuditTrailPort.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/audit/adapter/out/postgres/PostgresAuditTrail.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListener.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditController.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java`
- Modify: `docs/api/openapi.yaml`
- Modify: `src/test/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditControllerTest.java`
- Create: `src/test/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListenerTest.java`
- Modify: `docs/spec.md` (§7, §15, §9.3 — the N18 row; see note in Task 5 about why the rest of the
  catalogue lands there instead)

**Interfaces:**
- Produces: `AuditTrailPort.AuditEntry` gains a 7th component, `String actor` (nullable), **appended
  last** after `payload`. `AuditKafkaListener.CUTOVER` — `static final Instant`, package-visible, the
  instant this feature lands. Consumed by Task 5's tests via
  `com.ffroliva.tinyledger.api.generated.model.AuditEntry.actor` on the wire.

### The transport decision, made and justified here

`FullAdapterConfig.ledgerEventExternalization()` already builds three Kafka headers
(`event-type`, `stream-version`, `occurred-at`) from `LedgerEvent`, with the comment "Headers, not
payload parsing: the audit module never has to know the JSON shape of a ledger event, which is what
keeps the module boundary real." `actor` becomes a **fourth unconditional header**, not a payload
parse: every event `LedgerEvent.actor()` can return in normal operation is non-null (movement events
are always freshly stamped from a non-null, non-blank caller by `CallerPrincipal.current()`'s
fail-closed contract; `AccountOpened.actor()` always derives from `owner`), so `Map.of(...)` — which
throws on a null value — is safe here exactly as it already is for the other three headers. The only
place `actor` can be absent is on the **read** side: a Kafka message published by the pre-this-deploy
code, which never had the header at all, arriving at the consumer on a replay or an offset reset.
That is what `AuditKafkaListener`'s cutover comparison exists to handle, and it is the only place this
plan adds a null check.

### Step 1: Write the failing tests

Create `src/test/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListenerTest.java`:

```java
package com.ffroliva.tinyledger.audit.adapter.in.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

/**
 * §15.9/N18: the cutover comparison lives entirely in the header-to-column mapping, so it is provable
 * without Kafka, Postgres or Spring — just the listener and a fake {@link AuditTrailPort}. N18 itself
 * (a post-cutover event with no {@code actor}) cannot be driven through the HTTP API — no endpoint
 * emits an event without stamping one (§4.1 step 4) — so this is its only executable form (§9.3).
 */
class AuditKafkaListenerTest {

    private static final UUID ACCOUNT = UUID.randomUUID();

    private AuditTrailPort.AuditEntry recorded;
    private final AuditKafkaListener listener = new AuditKafkaListener(entry -> recorded = entry);

    @Test
    void aPresentActorHeaderIsStoredVerbatim() {
        listener.on(record(Instant.parse("2026-08-10T00:00:00Z"), "trent"));

        assertThat(recorded.actor()).isEqualTo("trent");
    }

    @Test // §15.9: absence before the cutover reads as the owner — stored as literal absence, not guessed
    void aMissingActorHeaderBeforeTheCutoverIsStoredAsAbsent() {
        listener.on(record(AuditKafkaListener.CUTOVER.minusSeconds(1), null));

        assertThat(recorded.actor()).isNull();
    }

    @Test // N18: on/after the cutover every publisher stamps actor unconditionally — absence is a defect
    void aMissingActorHeaderOnOrAfterTheCutoverIsReportedAsUnknown() {
        listener.on(record(AuditKafkaListener.CUTOVER, null));

        assertThat(recorded.actor()).isEqualTo("unknown");
    }

    private static ConsumerRecord<String, String> record(Instant occurredAt, String actor) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader("event-type", "MoneyDeposited".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("stream-version", "2".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader("occurred-at", occurredAt.toString().getBytes(StandardCharsets.UTF_8)));
        if (actor != null) headers.add(new RecordHeader("actor", actor.getBytes(StandardCharsets.UTF_8)));
        return new ConsumerRecord<>(
                "ledger.events",
                0,
                0L,
                0L,
                TimestampType.CREATE_TIME,
                -1,
                -1,
                ACCOUNT.toString(),
                "{}",
                headers,
                Optional.empty());
    }
}
```

### Step 2: Run it to verify it fails

Run: `./mvnw -q test -Dtest=AuditKafkaListenerTest`
Expected: **compile failure** — the `AuditKafkaListener(AuditTrailPort)` constructor exists, but
`AuditTrailPort.AuditEntry` has no `actor` component and `AuditKafkaListener.CUTOVER` doesn't exist.

### Step 3: `AuditTrailPort.AuditEntry` gains `actor`

`src/main/java/com/ffroliva/tinyledger/audit/application/port/out/AuditTrailPort.java` — replace the
`AuditEntry` record:

```java
    record AuditEntry(
            UUID accountId,
            String eventType,
            long streamVersion,
            Instant occurredAt,
            Instant recordedAt,
            String payload,
            String actor) {}
```

Nothing else in this file changes.

### Step 4: `AuditKafkaListener` — the cutover instant and the header read

`src/main/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListener.java` — full
replacement:

```java
package com.ffroliva.tinyledger.audit.adapter.in.events;

import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Reads the externalized ledger stream (ADR 0001). Everything it needs arrives as the record key
 * and headers, so the audit module never parses a ledger event's JSON — the payload is stored
 * verbatim and the module stays independent of the write side's serialization format. `actor` is a
 * fourth header alongside `event-type`/`stream-version`/`occurred-at` (§4.3/§6.4), not a payload
 * parse — see the plan that landed it for why that keeps this class's stated independence true.
 */
public class AuditKafkaListener {

    /**
     * §15.9: absence of the `actor` header reads as `actor = owner` (stored as a literal {@code null},
     * interpreted by convention at the API boundary — §7) only for events that occurred before this
     * instant. On or after it, every publisher stamps `actor` unconditionally, so a missing header is
     * a defect — the trail records the literal string {@code "unknown"} rather than silently looking
     * like pre-feature behaviour.
     */
    static final Instant CUTOVER = Instant.parse("2026-08-07T00:00:00Z");

    private final AuditTrailPort trail;

    public AuditKafkaListener(AuditTrailPort trail) {
        this.trail = trail;
    }

    // Topic literal rather than a shared constant: the audit module consumes this stream as an
    // external contract, not as a compile-time dependency on the publisher.
    @KafkaListener(topics = "ledger.events", groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> record) {
        Instant occurredAt = Instant.parse(header(record, "occurred-at"));
        trail.record(new AuditTrailPort.AuditEntry(
                UUID.fromString(record.key()),
                header(record, "event-type"),
                Long.parseLong(header(record, "stream-version")),
                occurredAt,
                // §7's recordedAt: when the audit module saw the event, which is here — the Kafka hop is
                // exactly the gap between this and occurredAt.
                Instant.now(),
                record.value(),
                actorOf(record, occurredAt)));
    }

    private static String actorOf(ConsumerRecord<String, String> record, Instant occurredAt) {
        Header header = record.headers().lastHeader("actor");
        if (header != null) return new String(header.value(), StandardCharsets.UTF_8);
        return occurredAt.isBefore(CUTOVER) ? null : "unknown";
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) throw new IllegalStateException("ledger event without header: " + name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
```

### Step 5: Run the listener tests to verify they pass

Run: `./mvnw -q test -Dtest=AuditKafkaListenerTest`
Expected: PASS, all three cases.

### Step 6: `FullAdapterConfig` — the fourth header

`src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java` — replace the `.headers(...)`
call inside `ledgerEventExternalization()`:

```java
                .headers(
                        LedgerEvent.class,
                        event -> Map.of(
                                "event-type", event.getClass().getSimpleName(),
                                "stream-version", String.valueOf(event.version()),
                                "occurred-at", event.occurredAt().toString(),
                                "actor", event.actor()))
```

Also update the comment immediately above it, which currently reads "Headers, not payload parsing:
the audit module never has to know the JSON shape of a ledger event, which is what keeps the module
boundary real." — leave the comment as-is; it is still exactly true.

### Step 7: `PostgresAuditTrail` — persist and read the column

`src/main/java/com/ffroliva/tinyledger/audit/adapter/out/postgres/PostgresAuditTrail.java` — three
edits.

The `SELECT` constant:

```java
    private static final String SELECT =
            "SELECT account_id, event_type, stream_version, payload, occurred_at, recorded_at, actor FROM audit_entries";
```

`record(AuditEntry entry)`:

```java
    @Override
    public void record(AuditEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO audit_entries (account_id, event_type, stream_version, payload, occurred_at, recorded_at, actor) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?) "
                        + "ON CONFLICT (account_id, stream_version) DO NOTHING",
                entry.accountId(),
                entry.eventType(),
                entry.streamVersion(),
                entry.payload(),
                Timestamp.from(entry.occurredAt()),
                Timestamp.from(entry.recordedAt()),
                entry.actor());
    }
```

`entryRowMapper()`:

```java
    private RowMapper<AuditEntry> entryRowMapper() {
        return (rs, rowNum) -> new AuditEntry(
                rs.getObject("account_id", UUID.class),
                rs.getString("event_type"),
                rs.getLong("stream_version"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("payload"),
                rs.getString("actor"));
    }
```

### Step 8: The migration

Create `src/main/resources/db/changelog/changes/005-add-audit-actor.sql`:

```sql
--liquibase formatted sql

--changeset flavio:5-add-audit-actor
ALTER TABLE audit_entries ADD COLUMN actor VARCHAR(255);
```

`src/main/resources/db/changelog/db.changelog-master.xml` — add the include, after `004`:

```xml
    <include file="changes/004-init-event-publication.sql" relativeToChangelogFile="true"/>
    <include file="changes/005-add-audit-actor.sql" relativeToChangelogFile="true"/>
```

Nullable by design (§15.9), no backfill, no index — an `actor` filter on the trail is one parameter
and one index the day an investigation needs it (the proposal's open question 2, already closed as
"no" for this POC).

### Step 9: `AuditController` maps `actor` onto the wire

`src/main/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditController.java` — replace
`auditEntry(...)`:

```java
    private static AuditEntry auditEntry(AuditTrailPort.AuditEntry entry) {
        return new AuditEntry(
                        entry.accountId(),
                        entry.streamVersion(),
                        AuditEntry.TypeEnum.fromValue(entry.eventType()),
                        at(entry.occurredAt()),
                        at(entry.recordedAt()))
                .actor(entry.actor());
    }
```

`event(...)` (the raw event stream mapping) is **unchanged** — it already parses `entry.payload()`
into a generic `Map<String, Object>` and returns the whole thing, so `actor` appears there for free
once Task 1's events carry it in their JSON. No change needed, and none is made.

### Step 10: `docs/api/openapi.yaml` — `AuditEntry.actor`

Add one optional property to the `AuditEntry` schema (not added to `required`), after `recordedAt`:

```yaml
        actor:
          type: string
          description: |
            The principal that issued the command (§2.3/§2.4). Equal to the account's owner for an
            owner-initiated movement; different for an admin acting on behalf of the owner (§6.4).
            Absent on entries recorded before the cutover instant §15.9 records, where it reads as
            the owner; absent on or after it is a defect, and the trail reports it as `"unknown"`.
```

### Step 11: Update `AuditControllerTest` and add the round-trip proof

`src/test/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditControllerTest.java` — the two
`new AuditTrailPort.AuditEntry(...)` calls inside `page(...)` each need the new trailing argument.
Replace the whole `page(...)` method:

```java
        private static AuditTrailPort.Page page(String nextCursor) {
            return new AuditTrailPort.Page(
                    List.of(
                            new AuditTrailPort.AuditEntry(
                                    ACCOUNT, "AccountOpened", 1, OCCURRED, RECORDED, """
                                    {"name":"ACC-001","owner":"alice"}""", null),
                            new AuditTrailPort.AuditEntry(
                                    ACCOUNT, "MoneyDeposited", 2, OCCURRED, RECORDED, """
                                    {"amount":{"currency":"GBP","minorUnits":2500}}""", "alice")),
                    nextCursor);
        }
```

Add a new test in the `WithAnAuditTrailWired` nested class, after `auditTrailIsFilteredByAccountAndTimeRange`:

```java
        @Test // §7/D4: the audit entry surfaces the acting principal; absent stays absent on the wire
        void auditTrailSurfacesTheActor() throws Exception {
            given(trail.trail(new AuditTrailPort.TrailQuery(ACCOUNT, null, 50, null, null))).willReturn(page(null));

            mvc.perform(get("/api/v1/audit/entries").param("accountUid", ACCOUNT.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.auditEntries[0].actor").doesNotExist())
                    .andExpect(jsonPath("$.auditEntries[1].actor").value("alice"));
        }
```

### Step 12: Run the audit tests to verify they pass

Run: `./mvnw -q test -Dtest=AuditKafkaListenerTest,AuditControllerTest`
Expected: PASS. `AuditControllerTest` is `@WebMvcTest` — Surefire, no containers.

Run: `./mvnw -q test`
Expected: PASS — unit count rises from 170 to **174** (three `AuditKafkaListenerTest` methods, one
new `AuditControllerTest` method).

### Step 13: `docs/spec.md` — §7, §15 item 9, §9.3 N18

In §7 (API), add one paragraph, immediately before "The balance resource returns the money object
plus the staleness markers §9.3 E1 demands:":

```
An audit entry carries one field the transaction does not: the **`actor`**, the principal that
issued the command, which for an on-behalf-of movement is not the account's `owner` (§6.4). The
raw event stream exposes it inherently — it is a field of the event. The customer-facing
transaction resource is deliberately silent on it: the compliance trail is where attribution is
read, and `actor` is an optional field, so surfacing it on the feed later is an addition, not a
break.
```

In §15 (Documented assumptions), add item 9, after item 8 (which Task 2 already added):

```
9. Events are immutable and there is no backfill. An event or audit entry with no `actor` reads as
   `actor = owner` only if it predates the cutover instant recorded when this lands. After that
   instant, an event or audit entry with no `actor` is a defect, and the trail reports `unknown`,
   never the owner.
```

In §9.3 (Scenario catalogue), add one negative row after N12 — this is the only catalogue row this
task adds; the rest (P9, N13–N17, the tagging paragraph, the positive heading, §5's ID-set) land in
Task 5 together with the JUnit tests that prove them, since N13–N17 don't exist as passing tests
until then. N18's row lands here, one task ahead of its neighbours, because its proof (Step 1–5
above) already exists and passes — TDD requires the test beside the code it proves, and the
catalogue is quoted as one table, so it cannot be split mid-row without the sentence around it going
stale; this is that one deliberate exception, not an oversight:

```
| N18 | An event written after the cutover with no `actor` | Reported as `unknown`, never as the owner |
```

Immediately after, one paragraph recording why this scenario has no HTTP-driven form:

```
**N18 cannot be driven through the HTTP API**, which §9.3 requires of catalogue scenarios — no
endpoint writes an event without stamping `actor` (§4.1 step 4). Its only executable form is
`AuditKafkaListenerTest`, a repository-level test of the header-to-column mapping directly.
```

### Step 14: Verify and commit

Run: `./mvnw -q verify`
Expected: green, zero containers, unit count **174**.

```bash
git commit -F - -- \
  src/main/resources/db/changelog/changes/005-add-audit-actor.sql \
  src/main/resources/db/changelog/db.changelog-master.xml \
  src/main/java/com/ffroliva/tinyledger/audit/application/port/out/AuditTrailPort.java \
  src/main/java/com/ffroliva/tinyledger/audit/adapter/out/postgres/PostgresAuditTrail.java \
  src/main/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListener.java \
  src/main/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditController.java \
  src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java \
  docs/api/openapi.yaml \
  src/test/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditControllerTest.java \
  src/test/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListenerTest.java \
  docs/spec.md <<'EOF'
feat: the audit trail surfaces the acting principal

D3/D4: actor crosses to the audit trail as a fourth Kafka header, alongside event-type/stream-version/
occurred-at (FullAdapterConfig) — not a payload parse, so AuditKafkaListener's stated independence
from the ledger's JSON shape holds. A message with no actor header reads as the owner if it predates
the cutover instant, and as "unknown" (a defect, not silence) if it doesn't — proven directly against
the listener with a hand-built ConsumerRecord, no Kafka needed. New nullable audit_entries.actor
column (changeset 005 — 004 was already spent on event publication). AuditEntry.actor is optional on
the wire, additive.

docs/spec.md §7/§15 item 9/§9.3 N18 updated in this commit.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
```

---

## Task 5: The catalogue proof — P9 and N13–N17

**Files:**
- Modify: `src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java`
- Modify: `docs/spec.md` (§9.3, §5)

**Interfaces:**
- Consumes: `trent` (Task 3), `RecordMovementService`'s widened check (Task 2), the audit trail's
  `actor` field (Task 4).

### Why these are JUnit `MockMvc` tests, not `.feature` files

§9.3 frames the catalogue as living in Cucumber `.feature` files (`insufficient-funds.feature`,
`concurrency.feature`, `authorisation.feature`, `rate-limit.feature` for the negative table). None of
those four files exist in `src/test/resources/features/` — checked directly. N1–N12 are implemented
today as plain JUnit integration tests (`SecurityConfigIT`, `RoleAuthorizationIT`, `RateLimitIT`),
because Cucumber only runs the `@standalone` subset in-process (§9.3) and every `@full` scenario
needs the real Keycloak/Kafka/Postgres stack `AbstractIntegrationTest` provides — the same stack
these two classes already extend. P9 and N13–N17 follow that existing, working pattern rather than
inventing a `.feature` file the rest of the negative catalogue doesn't have either. Fixing that
framing for the *whole* catalogue is a pre-existing gap, not introduced or worsened here, and out of
this plan's scope.

### Step 1: Write the failing tests

Add to `src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java`. First, two new static
imports are needed (`put`, alongside the existing `get`/`post`):

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
```

and two new plain imports:

```java
import java.time.Duration;
import static org.awaitility.Awaitility.await;
```

(Place `java.time.Duration` with the other `java.*` imports, and the `await` static import with the
other `static` ones — Spotless's `palantirJavaFormat` will re-sort on the next `./mvnw -q verify` if
placement is imperfect; do not hand-tune import order.)

Add these five methods, after `theRawEventStreamIsRefusedToAnOrdinaryToken`:

```java
    /**
     * P9: trent (admin) deposits into alice's account, addressed by its `accountUid` — not a name.
     * `trent` owns no account (D6), so this never goes near `GET /api/v1/accounts`; the UUID comes
     * straight from `openAnAccountAs`'s own response, the same way every other ownership test in this
     * class already gets it. The movement succeeds and the trail attributes it to him, not to alice —
     * the pair (actor, owner) on one row is the whole record of the delegation.
     */
    @Test
    void anAdminRecordsACrossAccountMovementAndTheAuditTrailAttributesItToHim() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", alicesAccount, UUID.randomUUID())
                        .header("Authorization", bearer("trent"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":10000}}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/accounts/{a}/balance?consistency=strong", alicesAccount)
                        .header("Authorization", bearer("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount.minorUnits").value(10000));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get("/api/v1/audit/entries")
                        .param("accountUid", alicesAccount.toString())
                        .header("Authorization", bearer("dave")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditEntries[0].type").value("MoneyDeposited"))
                .andExpect(jsonPath("$.auditEntries[0].actor").value(KeycloakTokens.SUBJECTS.get("trent"))));
    }

    // P9's other half: admin widens the write, never the read — trent cannot read the account he
    // just acted on, neither the eventually-consistent balance nor the strong one.
    @Test
    void anAdminCannotReadTheAccountHeJustActedOn() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/balance", alicesAccount).header("Authorization", bearer("trent")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/accounts/{a}/balance?consistency=strong", alicesAccount)
                        .header("Authorization", bearer("trent")))
                .andExpect(status().isForbidden());
    }

    @Test // N13: ledger:admin widens ownership only — the trail stays ledger:auditor-only
    void theAuditTrailIsRefusedToAnAdmin() throws Exception {
        mvc.perform(get("/api/v1/audit/entries").header("Authorization", bearer("trent")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    // N14: same reason as N13, the other auditor route — SecurityConfig denies both with one matcher,
    // so a fix that split the routes and covered only /audit/** would pass N13 while an admin still
    // reads the raw event stream on this one.
    @Test
    void theRawEventStreamIsRefusedToAnAdmin() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(get("/api/v1/accounts/{a}/events", alicesAccount).header("Authorization", bearer("trent")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // N17: mallory holds ledger:writer but not ledger:admin — the widening is gated on the role
    void aWriterWithoutAdminCannotDepositIntoSomeoneElsesAccount() throws Exception {
        UUID alicesAccount = openAnAccountAs("alice");

        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", alicesAccount, UUID.randomUUID())
                        .header("Authorization", bearer("mallory"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":10000}}"))
                .andExpect(status().isForbidden());
    }
```

Add to `src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java`. Two new imports:

```java
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
```

and:

```java
import org.springframework.security.core.authority.SimpleGrantedAuthority;
```

Add these two methods, after `aReaderOnlyTokenCanListHerOwnAccounts`:

```java
    // N16/D8: ledger:admin never widens GET /api/v1/accounts — trent owns nothing, so the list is empty
    @Test
    void anAdminListsOnlyTheAccountsHeOwnsWhichIsNone() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer("trent")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isEmpty());
    }

    /**
     * N15: the actual conjunction test. `ledger:admin` alone does not satisfy the chain's
     * `ledger:writer` matcher on this path — P9 alone cannot fail against a blanket
     * `if (admin) return true` bypass that also happened to grant roles; this can, because it holds
     * `ledger:admin` and nothing else. `.with(jwt().authorities(...))` bypasses the real decoder and
     * injects the authorities directly — the same technique `SecurityConfigIT#anErrorDispatchDoesNotEchoTheRequestPath`
     * already uses — so this needs no realm change: the chain-level rule is what is under test, not
     * the token issuer.
     */
    @Test
    void anAdminWithoutWriterCannotDeposit() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/deposits/" + UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ledger:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MOVEMENT_BODY))
                .andExpect(status().isForbidden());
    }
```

### Step 2: Push and verify the tests fail correctly

These are all `*IT` classes — cannot run locally under this plan's constraints. Commit is not yet
made; instead, push to a scratch point or rely on the fact that at this point in the plan, Tasks 1–4
are already merged and green, so these new methods are the only red surface. If you want a local RED
proof before pushing, temporarily run with `-Pit` **once**, locally, as an explicit, deliberate
exception noted in your own commit message that you reverted — otherwise, skip straight to Step 3
(the implementation already exists from Tasks 1–4; this task is purely new test coverage) and push,
reading CI for both red-then-green would require two pushes. Given Tasks 1–4 already implement
every behaviour these tests assert, the pragmatic path is: write the tests, run
`./mvnw -q test-compile` locally to confirm they compile, then push once and confirm green on CI.

Run: `./mvnw -q test-compile`
Expected: PASS (compiles clean — no red/green cycle possible locally for `*IT` under this plan's
constraints, which is why Global Constraints route this through CI).

### Step 3: `docs/spec.md` — §9.3, §5

In §9.3 (Scenario catalogue), the tagging paragraph:

Current:
```
Every scenario below is a committed `.feature` file, tagged **`@standalone`** or **`@full`**.
Cucumber runs the `@standalone` subset in-process on every push (§12.1 stage 5); pytest-bdd re-runs
the **entire** catalogue against the composed stack (§9.6), where auth, Kafka and the shared
limiter actually exist. The auth scenarios (N6–N10), the shared-limiter N9, Kafka's E6, auditor P7,
restart-persistence E7 and real-Postgres N2 are `@full` by necessity — a mode with no auth cannot
assert a `403`, and a mode that loses state on restart cannot assert recovery.
```

Replace with:
```
Every scenario below is a committed `.feature` file, tagged **`@standalone`** or **`@full`**.
Cucumber runs the `@standalone` subset in-process on every push (§12.1 stage 5); pytest-bdd re-runs
the **entire** catalogue against the composed stack (§9.6), where auth, Kafka and the shared
limiter actually exist. The auth scenarios (N6–N10, N13–N18), the shared-limiter N9, Kafka's E6,
auditor P7, on-behalf-of P9, restart-persistence E7 and real-Postgres N2 are `@full` by necessity —
a mode with no auth cannot assert a `403` or an admin, and a mode that loses state on restart cannot
assert recovery.
```

The positive table heading:

Current:
```
**Positive — `deposits.feature`, `withdrawals.feature`, `history.feature`**
```

Replace with:
```
**Positive — `deposits.feature`, `withdrawals.feature`, `history.feature`, `authorisation.feature`**
```

Add one positive row after P8:

```
| P9 | `trent` (admin) deposits 100.00 into `alice`'s account, addressed by its `accountUid` — not the `ACC-001` name | `201`; balance 100.00; `MoneyDeposited` on the stream carrying `actor=trent` while the stream's `owner` stays `alice`; the audit entry for that version reports the same `actor` — the movement is attributable to the person, not merely to "an admin". `trent`'s own read of that balance is refused: `403`, same as any non-owner's — admin widens change operations only, never reads |
```

Add negative rows after N12 (N18 is already present from Task 4 — insert N13–N17 before it):

```
| N13 | `trent` (admin) requests `GET /api/v1/audit/entries` | `403`. `ledger:admin` widens ownership, not roles: the trail belongs to `ledger:auditor`, and the principal who may move money on any account is not the one who reviews it |
| N14 | `trent` (admin) requests `GET /api/v1/accounts/{accountUid}/events` | `403`, same reason as N13. `SecurityConfig` denies both auditor routes with a single matcher; a fix that split the routes and covered only `/audit/**` would pass N13 while an admin still reads the raw event stream on the other route |
| N15 | `trent` **without** `ledger:writer` attempts a cross-account deposit | 403. The actual conjunction test — P9 cannot fail against a short-circuit that also grants roles |
| N16 | `trent` requests `GET /api/v1/accounts` | Only accounts he owns — none. Proves D8 |
| N17 | `mallory` (writer, no admin) attempts a cross-account write | 403. Proves the widening is gated on the role rather than always-on |
```

Add one clarifying paragraph after the N18 explanation Task 4 already added (why N13/N14 rather than
mirroring N7 onto the write path):

```
**Why N13/N14 and not a cross-account write refusal.** The obvious candidate — `mallory` deposits into
`ACC-001` and is refused — mirrors N7 onto the write path, and N7 already fails the moment the
ownership comparison stops discriminating. What no existing scenario can fail is the shape this
change actually invites: an admin clause implemented as a blanket bypass. That implementation passes
every role check, every ownership check and every positive scenario in the catalogue, and is visible
only where an admin reaches something an admin should not have. N13–N18 are those scenarios.
```

Finally, §5 (Spec-driven design), the `Requirement IDs` sentence:

Current:
```
**Requirement IDs:** the scenario IDs *are* the requirement IDs — `REQ-<scenario-id>` for every
catalogue row (P0…P8, N1…N12, E1…E9), and the `REQ-NNN` tags §8.2 harvests from tests use exactly
these. Membership is the catalogue itself, never a range that can drift.
```

Replace with:
```
**Requirement IDs:** the scenario IDs *are* the requirement IDs — `REQ-<scenario-id>` for every
catalogue row (P0…P9, N1…N18, E1…E9), and the `REQ-NNN` tags §8.2 harvests from tests use exactly
these. Membership is the catalogue itself, never a range that can drift.
```

### Step 4: Push and verify on CI

```bash
git commit -F - -- \
  src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java \
  src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java \
  docs/spec.md <<'EOF'
test: prove the admin clause widens exactly one comparison point

P9: trent deposits on alice's account (201, actor=trent on the stream and in the audit trail, owner
unchanged) and is refused reading it back, eventual or strong. N13/N14: admin is refused both auditor
routes. N15: ledger:admin alone does not satisfy the chain's ledger:writer matcher — the actual
conjunction test a blanket bypass would fail. N16: GET /accounts stays empty for trent (D8). N17:
mallory (writer, no admin) still cannot write cross-account.

Addressed by accountUid captured from the account-creation response, not by name through
GET /api/v1/accounts — trent owns nothing and widening that route is forbidden (D8), so this plan's
JUnit tests never need the deterministic-UUID realm pinning the proposal flagged as unbuilt; that gap
is unchanged and still owned by the (also unbuilt) Python CLI.

docs/spec.md §9.3/§5 updated in this commit.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
git push
gh run watch
```

Expected: green. Integration count rises from 53 (after Task 3) to **59** (six new `*IT` methods:
two in `SecurityConfigIT` for P9, two more for N13/N14, one for N17, plus N16/N15 in
`RoleAuthorizationIT`). Confirm the run conclusion is `success`.

---

## Task 6: Spec version — 3.11 → 3.12

**Files:**
- Modify: `docs/spec.md` (header, revision history)

This is metadata only — every behavioural sentence Tasks 1–5 needed was already written in the same
commit as the code it describes. This task records that a coherent version now exists.

### Step 1: Bump the header

Current:
```
**Author:** Flávio Oliva
**Version:** 3.11
**Status:** Contract for implementation
```

Replace with:
```
**Author:** Flávio Oliva
**Version:** 3.12
**Status:** Contract for implementation
```

### Step 2: Add the revision-history row

Determine today's UTC date: `date -u +%F`. Add, after the `3.11` row:

```
| 3.12 | <date from `date -u +%F`> | Admin on-behalf-of: `ledger:admin` widens the ownership term at one comparison point — `RecordMovementService`'s in-service check — for change operations only, never for reads, whether the read-model decorator, `StrongBalanceService`'s strong read, or the account collection (D8), and never the role term; every event records the acting principal as `actor` (§2.3/§2.4/§4.1) and the audit entry surfaces it (§7); admin is not an auditor — separation of duties kept; test user `trent`, scenarios P9/N13–N18, error row (§6.5), assumptions 8–9, delegation protocols declared a non-goal (§13); `audit_entries.actor` added by changeset 005 |
```

### Step 3: Confirm the existing gaps table needs no change

Read the "Known divergences between this document and the code" table (the row above "Revision
history"). Confirm the row "the seed script that pins deterministic `accountUid`s to the realm's
fixture users is still not built" is **still accurate** — this plan does not build it (see the note
at the top of this document). No edit needed; this step is a check, not a change.

### Step 4: Verify and commit

Run: `./mvnw -q verify`
Expected: green, unit count **174**, unchanged from Task 4 (this task adds no code).

```bash
git commit -F - -- docs/spec.md <<'EOF'
docs: bump spec to 3.12 — admin on-behalf-of lands

Header and revision-history row only; every behavioural sentence already landed in Tasks 1–5's own
commits, in the section it describes.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
git push
gh run watch
```

Verify: `git log -1 --format='%s'` prints `docs: bump spec to 3.12 — admin on-behalf-of lands`.
Confirm the CI run conclusion is `success` and the final state is: **174 unit / 59 integration**,
`ledger:admin` widening exactly `RecordMovementService`, `trent` refused every read and both auditor
routes, `mallory` still refused cross-account, N15's conjunction proven, D8 proven, and `docs/spec.md`
at 3.12 with no section describing behaviour the code does not have.
