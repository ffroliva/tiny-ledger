# §14 Step 9 Parts 2 and 3 — Tracing, OTLP Export, JSON Logs and the Opt-in Collector

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit traces and metrics from the ledger over OTLP, correlate every log line to its trace, and ship one opt-in OTel Collector — so that §9.4's `InMemorySpanExporter` assertions pass **and** a Collector container receives real spans and metrics.

**Architecture:** Micrometer + Micrometer Tracing over the OpenTelemetry *bridge*, wired by one Boot starter. Domain spans come from a use-case **decorator** in `config`, mirroring the existing `TransactionalUseCases`, so `..application..` never imports a Micrometer type. Export is **off by default**; a single Compose service behind `profiles: [observability]` turns it on and forwards to Grafana Cloud.

**Tech Stack:** Spring Boot 4.1.0 · `spring-boot-starter-opentelemetry` · Micrometer 1.17.0 / Micrometer Tracing 1.7.0 / OpenTelemetry 1.62.0 (all Boot-BOM-managed) · `otel/opentelemetry-collector-contrib` · Testcontainers 1.20.5

**Design:** [`docs/superpowers/specs/2026-08-07-step-9-parts-2-3-observability-design.md`](../specs/2026-08-07-step-9-parts-2-3-observability-design.md)

**Branch:** `step-9-tracing-and-collector` · **Draft PR:** #15 (opened before Task 1, because `ci.yml` scopes `push` to `main` and a branch with no open PR gets no CI at all)

---

## Rules that govern every task

These are `AGENTS.md`'s, restated because a task executed out of order must still obey them.

1. **`./mvnw -q verify` must be green before every commit, and must start ZERO containers.** A container under `verify` is a bug, not a slow test.
2. **Never run `-Pit` locally.** Push and read CI: `gh run watch`, `gh run view --log-failed`. One Maven build per tree at a time.
3. **Commit with explicit pathspecs. Never `git add -A`.**
4. **`main` is protected.** Every change goes through the PR. **Never merge without asking.**
5. **No credential in any committed file, commit message, or chat.** The repo is PUBLIC and gitleaks gates CI. Values live in `.env.grafana` only.
6. **A test that would pass with its fix reverted is not coverage.** Every red proof must be checked three ways: (a) the run's exit code, (b) the surefire/failsafe **XML** shows the named test actually executed — `-Dtest` matching nothing exits 0 — and (c) the failure message is the *assertion* you expected, not a context-startup error or a hang. Part 1 shipped two reds that failed for the wrong reason.
7. **If a document claim turns out false, fix it the same day** and record it in the spec's revision history.

### Reading the counts

`./mvnw -q verify` prints nothing on success. To count tests, use the surefire XML paired with that run's exit code (AGENTS trap 3):

```bash
./mvnw verify > /tmp/verify.log 2>&1; echo "EXIT=$?"
grep -ho '<testcase ' target/surefire-reports/*.xml | wc -l
```

### The zero-container check, done differentially

A grep that can never match is not evidence (AGENTS trap 7). Use the build log, and pair it with a run that *must* score hits:

```bash
grep -c "Creating container for image" /tmp/verify.log     # MUST be 0
```

Control: the same pattern over a `-Pit` CI log must be non-zero. Fetch it with
`gh run view <id> --log | grep -c "Creating container for image"`. Report both numbers, never just the zero.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `pom.xml` | Two dependencies | 1 |
| `src/main/resources/application.properties` | Export off by default, sampling, **resource attributes corrected** | 1, 2 |
| `src/main/resources/application-full.properties` | JSON logs, Kafka template observation | 6, 4 |
| `src/main/java/…/config/TracedUseCases.java` | **new** — the use-case span + `ledger.movements` counter | 3 |
| `src/main/java/…/config/UseCaseConfig.java` | Wires the traced beans as `@Primary` | 3 |
| `src/main/java/…/config/FullAdapterConfig.java` | Transactional beans give up `@Primary`; DLT counter | 3, 7 |
| `src/main/java/…/balance/adapter/in/events/LedgerEventsListener.java` | Projection-apply span | 5 |
| `src/main/java/…/audit/adapter/in/events/AuditKafkaListener.java` | Consume span **linked** to the producer | 4 |
| `src/test/java/…/testsupport/ObservabilityTestConfig.java` | **new** — the `InMemorySpanExporter` bean | 5 |
| `src/test/java/…/testsupport/AbstractIntegrationTest.java` | Imports it; enables test export by property | 5 |
| `src/test/java/…/observability/ObservabilityIT.java` | **new** — §9.4's three assertions | 5 |
| `src/test/java/…/observability/OtlpExportIT.java` | **new** — telemetry leaves the process | 8 |
| `src/test/resources/otel-collector-test.yaml` | **new** — OTLP in, file out | 8 |
| `docker/otel-collector.yaml` | **new** — OTLP in, tail-sample, Grafana Cloud out | 9 |
| `docker/docker-compose.yml` | The opt-in service | 9 |
| `docs/spec.md`, `CHANGELOG.md`, `README.md`, `docs/INDEX.md` | The record | 10 |

---

## Task 1: The dependency, and the first boot

Boot 4.1 collapsed four dependencies into one and renamed every export property. This task adds
them, turns export **off**, and proves the application still starts.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1: Add the two dependencies**

In `pom.xml`, immediately after the `spring-boot-starter-actuator` dependency (around line 77):

```xml
    <!-- Spec §6.6 / §14 step 9 part 2. ONE starter, not four: read out of the published POM,
         spring-boot-starter-opentelemetry (4.1.0) brings micrometer-registry-otlp,
         micrometer-tracing-bridge-otel and opentelemetry-exporter-otlp, plus
         spring-boot-micrometer-tracing-opentelemetry and spring-boot-opentelemetry. Every version
         comes from the Boot BOM (micrometer 1.17.0, micrometer-tracing 1.7.0, opentelemetry 1.62.0),
         so nothing is pinned here.

         Micrometer Tracing over the OTel BRIDGE, never the OTel Java agent (ADR 0005): domain spans
         are written explicitly and stay reviewable in the source. Export is off by default in
         application.properties — the classpath decides what CAN be exported, the properties decide
         whether anything IS. -->
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-opentelemetry</artifactId></dependency>
```

And with the other test-scoped dependencies (after `spring-security-test`, around line 82):

```xml
    <!-- §9.4's InMemorySpanExporter. Deliberately NOT spring-boot-starter-opentelemetry-test: that
         module carries @AutoConfigureTracing and @AutoConfigureMetrics and no in-memory exporter at
         all, and both annotations change a test's context cache key (AGENTS.md trap 5). The same
         effect is reached by a property — see AbstractIntegrationTest. -->
    <dependency><groupId>io.opentelemetry</groupId><artifactId>opentelemetry-sdk-testing</artifactId><scope>test</scope></dependency>
```

- [ ] **Step 2: Turn every exporter off, and sample everything**

Append to `src/main/resources/application.properties`:

```properties
# ── §14 step 9 part 2: OTLP export, OFF by default ───────────────────────────────────────────────
# Spec §6.6: with the `observability` Compose profile inactive there is nothing listening on 4318,
# and an enabled exporter would fill the log with failed-export retries against a port no one holds.
# Spans and meters are still CREATED — that is what puts trace_id and span_id on every log line and
# gives §9.4's assertions something to read. Only the shipping is off.
#
# These are the Boot 4.1 property names, read out of each jar's spring-configuration-metadata.json.
# The `management.otlp.tracing.*` spelling most documentation still shows is the DEPRECATED alias;
# the live names are below, and the endpoints are set only by the tests and by a real deployment.
management.tracing.export.otlp.enabled=false
management.otlp.metrics.export.enabled=false
management.logging.export.otlp.enabled=false

# Spec §6.6: 100% at the application, thinned at the Collector. Boot's default is 0.1, which would
# silently discard nine spans in ten — including every one §9.4 asserts on. Tail sampling belongs at
# the Collector because only it can see whether a trace ended in an error (docker/otel-collector.yaml);
# a head sampler discards errors at exactly the same rate as successes, which is backwards.
management.tracing.sampling.probability=1.0
```

- [ ] **Step 3: Run the gate, and count the containers**

```bash
./mvnw verify > /tmp/t1.log 2>&1; echo "EXIT=$?"
grep -c "Creating container for image" /tmp/t1.log
grep -ho '<testcase ' target/surefire-reports/*.xml | wc -l
```

Expected: `EXIT=0`, container count **0**, and the testcase count unchanged from `main`'s baseline
(record the number; Task 3 onwards will add to it).

**This is the first boot of the new dependency.** Three standalone `@SpringBootTest` contexts run
here — `CucumberSpringConfig`, `LedgerEventsListenerTest` and `ActuatorProbeTest` — so a broken
auto-configuration fails loudly rather than waiting for CI. **Expect this step to correct something.
If it does, fix the plan in the same commit** (the goal statement requires it).

- [ ] **Step 4: Prove a `Tracer` bean exists in every context**

Add to `src/test/java/com/ffroliva/tinyledger/platform/ActuatorProbeTest.java` (a class that already
boots the standalone context, so this adds no new context and forks nothing):

```java
    /**
     * §14 step 9 part 2. Two beans that must exist in EVERY context, because Task 3's use-case
     * decorator and Task 5's projection span both inject them by type: a missing Tracer is a
     * context-startup failure at the composition root, which is a far worse failure mode than a
     * missing span. Asserted in `standalone` — the mode with no Kafka, no Postgres and no
     * MeterRegistry-bearing adapter config — because that is the context most likely to lack them.
     */
    @Test
    void tracingAndMeteringBeansExistInStandalone(
            @Autowired ObjectProvider<io.micrometer.tracing.Tracer> tracer,
            @Autowired ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        assertThat(tracer.getIfAvailable()).as("Tracer bean").isNotNull();
        assertThat(meters.getIfAvailable()).as("MeterRegistry bean").isNotNull();
    }
```

Run it:

```bash
./mvnw -q test -Dtest=ActuatorProbeTest 2>&1 | tail -20; echo "EXIT=$?"
```

Expected: PASS. If either is null, **stop** — Task 3 must inject `ObjectProvider<Tracer>` instead of
`Tracer`, and this plan is edited to say so before continuing.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties \
        src/test/java/com/ffroliva/tinyledger/platform/ActuatorProbeTest.java
git commit -m "feat: add the OpenTelemetry starter, with every exporter off by default

One dependency, not four: spring-boot-starter-opentelemetry brings
micrometer-registry-otlp, micrometer-tracing-bridge-otel and
opentelemetry-exporter-otlp, all Boot-BOM-managed.

Export is off in the base configuration (spec §6.6) so an inactive
observability profile costs no failed-export retries. Spans and meters are
still created, which is what puts trace_id on every log line.

The property names are Boot 4.1's, read out of each jar's configuration
metadata: management.tracing.export.otlp.enabled, not the deprecated
management.otlp.tracing.* alias. Sampling is pinned to 1.0 because Boot's
default of 0.1 would discard nine spans in ten, including every span §9.4
asserts on."
```

---

## Task 2: Correct part 1's resource-attribute model

**This is a defect carried in from part 1, found while reading the Boot 4.1 metadata.**
`application.properties` declares:

```properties
management.observations.key-values.service.namespace=…
management.observations.key-values.service.instance.id=…
```

`management.observations.key-values.*` adds **common key-values to every observation** — they become
**span tags and meter tags**. They are *not* OTel resource attributes; Boot reads those from
`management.opentelemetry.resource-attributes.*`.

Two consequences, and the second is serious:

1. §6.6 says "every signal carries `service.name`, `service.namespace` and `service.instance.id`" as
   *resource* attributes. As declared, they would arrive as span attributes instead — the wrong shape
   for every backend that groups by resource.
2. **`service.instance.id` defaults to `${random.uuid}` — a fresh value per process.** As a *meter*
   tag that is one new time series per restart and per replica, forever. That is precisely the
   one-way door §6.6's cardinality rule exists to prevent, and part 1 wrote it in while quoting the
   rule.

**Files:**
- Modify: `src/main/resources/application.properties:92-98`

- [ ] **Step 1: Replace the three lines**

Replace the block ending at `management.observations.key-values.service.instance.id=…` with:

```properties
# ADR 0005: without these, twenty replicas emit one indistinguishable stream and "which instance is
# slow" has no answer. Environment-sourced, never hardcoded.
#
# CORRECTED at part 2 (spec v3.41). Part 1 declared the latter two under
# `management.observations.key-values.*`, which adds common key-values to every OBSERVATION — they
# become span tags and METER tags, not resource attributes. Boot reads OTel resource attributes from
# `management.opentelemetry.resource-attributes.*`, and `spring.application.name` supplies
# service.name on its own.
#
# The meter half was the real defect: service.instance.id is ${random.uuid}, a fresh value per
# process, so as a meter tag it mints one permanent time series per restart and per replica. That is
# exactly the one-way door §6.6's cardinality rule names — written in by the pass that quoted the
# rule. NO GATE ENFORCES THIS; it was found by reading Boot 4.1's configuration metadata.
spring.application.name=${LEDGER_SERVICE_NAME:tiny-ledger}
management.opentelemetry.resource-attributes.service.namespace=${LEDGER_SERVICE_NAMESPACE:local}
management.opentelemetry.resource-attributes.service.instance.id=${LEDGER_INSTANCE_ID:${random.uuid}}
```

- [ ] **Step 2: Verify the properties bind against the running app, not against the jar**

Part 1's own finding was that property names must be checked against a running context. Add to
`ActuatorProbeTest`:

```java
    /**
     * §6.6 / ADR 0005, and a regression pin for the part-1 defect corrected at v3.41: these must be
     * OTel RESOURCE attributes, not observation key-values. Asserted through the Environment rather
     * than through the SDK because the binding is the thing that was wrong — the value arrived, at
     * the wrong address.
     */
    @Test
    void serviceIdentityIsDeclaredAsResourceAttributesAndNotAsObservationKeyValues(
            @Autowired org.springframework.core.env.Environment env) {
        assertThat(env.getProperty("management.opentelemetry.resource-attributes.service.namespace"))
                .isNotBlank();
        assertThat(env.getProperty("management.opentelemetry.resource-attributes.service.instance.id"))
                .isNotBlank();
        assertThat(env.getProperty("management.observations.key-values.service.instance.id"))
                .as("a per-process UUID as a meter tag is an unbounded time series (§6.6)")
                .isNull();
    }
```

- [ ] **Step 3: Red proof — the test must fail with the old spelling**

```bash
git stash push src/main/resources/application.properties
./mvnw -q test -Dtest=ActuatorProbeTest 2>&1 | tail -30; echo "EXIT=$?"
grep -l 'serviceIdentityIsDeclaredAsResourceAttributes' target/surefire-reports/*.xml
git stash pop
```

Expected: `EXIT` non-zero; the XML grep **must name a file** (proving the test ran); and the failure
must be an `AssertionError` on `isNotBlank`, **not** a context-startup error. If it is a startup
error, the red is for the wrong reason — fix that before continuing (AGENTS trap 4).

- [ ] **Step 4: Green**

```bash
./mvnw verify > /tmp/t2.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t2.log
```

Expected: `EXIT=0`, containers **0**.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.properties \
        src/test/java/com/ffroliva/tinyledger/platform/ActuatorProbeTest.java
git commit -m "fix: service identity is a resource attribute, not a meter tag

Part 1 declared service.namespace and service.instance.id under
management.observations.key-values.*, which adds common key-values to every
observation — they become span AND METER tags. Boot reads OTel resource
attributes from management.opentelemetry.resource-attributes.*.

The meter half is the defect that matters. service.instance.id is
\${random.uuid}, a fresh value per process, so as a meter tag it mints one
permanent time series per restart and per replica — exactly the one-way door
§6.6's cardinality rule names, written in by the pass that quoted the rule.

Proven by reverting the properties: the new assertion fails on isNotBlank, not
on a context-startup error."
```

---

## Task 3: The use-case span decorator and `ledger.movements`

Spec §6.6: *"Domain spans are added by decoration, not by annotation."* `TracedUseCases` mirrors
`TransactionalUseCases` — same package, same shape, same reason: `..application..` imports no
Micrometer type, exactly as it imports no `@Transactional`.

**It is the OUTERMOST decorator** (`traced → transactional → service`) so the span covers the commit.
That forces two mechanical changes in `FullAdapterConfig`: its transactional beans give up `@Primary`
and declare their concrete types, or the context fails to start with two `@Primary` candidates for
one interface.

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/config/TracedUseCases.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/config/UseCaseConfig.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java:161-171`
- Create: `src/test/java/com/ffroliva/tinyledger/config/TracedUseCasesTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/ffroliva/tinyledger/config/TracedUseCasesTest.java`:

```java
package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.Outcome;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/**
 * Unit-level, no Spring context, no containers: the decorator is a plain object and the two things
 * it must get right — the span's attributes and the counter's TAGS — are both observable from
 * SimpleTracer and SimpleMeterRegistry.
 *
 * <p>The cardinality assertions are the point of the class, not padding. §6.6's rule that account
 * ids never reach a meter has NO GATE; this is the nearest thing to one, and it only covers this
 * meter.
 */
class TracedUseCasesTest {

    private static final AccountId ACCOUNT = AccountId.of("2f1b9f7e-0000-4000-8000-000000000001");
    private static final Money TEN = new Money(Currency.getInstance("GBP"), 1000L);

    private SimpleTracer tracer;
    private MeterRegistry meters;

    @BeforeEach
    void setUp() {
        tracer = new SimpleTracer();
        meters = new SimpleMeterRegistry();
    }

    private RecordMovementUseCase traced(RecordMovementUseCase delegate) {
        return new TracedUseCases.Movements(delegate, tracer, meters);
    }

    private static Deposit deposit() {
        return new Deposit("alice", false, ACCOUNT, UUID.randomUUID(), TEN, "rent");
    }

    private static Withdraw withdraw() {
        return new Withdraw("alice", false, ACCOUNT, UUID.randomUUID(), TEN, "rent");
    }

    private static MovementResult result(Outcome outcome, String reason) {
        return new MovementResult(
                ACCOUNT, UUID.randomUUID(), MovementType.WITHDRAWAL, 7L, TEN, TEN, Instant.EPOCH, outcome, reason);
    }

    @Nested
    class Spans {

        @Test
        void aSettledMovementProducesOneSpanCarryingTheDomainAttributes() {
            traced(new StubMovements(result(Outcome.CREATED, null))).withdraw(withdraw());

            var span = tracer.onlySpan();
            assertThat(span.getName()).isEqualTo("ledger.record-movement");
            assertThat(span.getTags())
                    .containsEntry("ledger.account_id", ACCOUNT.value().toString())
                    .containsEntry("ledger.movement_type", "WITHDRAWAL")
                    .containsEntry("ledger.stream_version", "7");
            assertThat(span.getEndTimestamp()).isNotNull();
        }

        @Test
        void aRejectionTagsTheReasonOnTheSpanAsWellAsTheCounter() {
            traced(new StubMovements(result(Outcome.REJECTED, "insufficient-funds"))).withdraw(withdraw());

            assertThat(tracer.onlySpan().getTags())
                    .containsEntry("ledger.rejection_reason", "insufficient-funds");
        }

        @Test
        void aConcurrencyConflictEndsTheSpanAndRecordsTheError() {
            var boom = new ConcurrencyConflictException(ACCOUNT, 3L, 4L);
            try {
                traced(new ThrowingMovements(boom)).withdraw(withdraw());
            } catch (ConcurrencyConflictException expected) {
                // rethrown, deliberately: a decorator that swallows is a decorator that lies
            }
            assertThat(tracer.onlySpan().getEndTimestamp())
                    .as("a span left unended leaks and never reaches a backend")
                    .isNotNull();
            assertThat(tracer.onlySpan().getError()).isSameAs(boom);
        }
    }

    @Nested
    class Meters {

        @Test
        void aSettledDepositCountsWithReasonNone() {
            traced(new StubMovements(
                            new MovementResult(
                                    ACCOUNT,
                                    UUID.randomUUID(),
                                    MovementType.DEPOSIT,
                                    1L,
                                    TEN,
                                    TEN,
                                    Instant.EPOCH,
                                    Outcome.CREATED,
                                    null)))
                    .deposit(deposit());

            assertThat(meters.get("ledger.movements")
                            .tag("type", "DEPOSIT")
                            .tag("outcome", "created")
                            .tag("reason", "none")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void aRejectionCountsUnderItsReason() {
            traced(new StubMovements(result(Outcome.REJECTED, "insufficient-funds"))).withdraw(withdraw());

            assertThat(meters.get("ledger.movements")
                            .tag("outcome", "rejected")
                            .tag("reason", "insufficient-funds")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void aConcurrencyConflictCountsAsItsOwnOutcome() {
            try {
                traced(new ThrowingMovements(new ConcurrencyConflictException(ACCOUNT, 3L, 4L)))
                        .withdraw(withdraw());
            } catch (ConcurrencyConflictException expected) {
                // see above
            }
            assertThat(meters.get("ledger.movements")
                            .tag("outcome", "conflict")
                            .tag("reason", "none")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void noMeterTagCarriesAnAccountIdOrAMovementUid() {
            traced(new StubMovements(result(Outcome.CREATED, null))).withdraw(withdraw());

            assertThat(meters.get("ledger.movements").counter().getId().getTags())
                    .as("§6.6: account ids and movement uids go on spans and logs, NEVER on meters")
                    .noneMatch(tag -> tag.getValue().contains("-") && tag.getValue().length() == 36);
        }
    }

    private record StubMovements(MovementResult answer) implements RecordMovementUseCase {
        @Override
        public MovementResult deposit(Deposit cmd) {
            return answer;
        }

        @Override
        public MovementResult withdraw(Withdraw cmd) {
            return answer;
        }
    }

    private record ThrowingMovements(RuntimeException boom) implements RecordMovementUseCase {
        @Override
        public MovementResult deposit(Deposit cmd) {
            throw boom;
        }

        @Override
        public MovementResult withdraw(Withdraw cmd) {
            throw boom;
        }
    }
}
```

`SimpleTracer` lives in `micrometer-tracing-test`. Add it to `pom.xml` beside the other test
dependencies:

```xml
    <!-- SimpleTracer/SimpleSpan: lets TracedUseCasesTest assert span names and tags with no Spring
         context and no OTel SDK. Version from the Boot BOM's micrometer-tracing-bom import. -->
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-tracing-test</artifactId><scope>test</scope></dependency>
```

- [ ] **Step 2: Run it and watch it fail for the right reason**

```bash
./mvnw -q test -Dtest=TracedUseCasesTest 2>&1 | tail -20; echo "EXIT=$?"
```

Expected: a **compilation** failure naming `TracedUseCases` — the class does not exist yet. That is
the correct red for a new type. (`failIfNoSpecifiedTests=true` is set in `pom.xml`, so a typo in the
`-Dtest` pattern fails rather than exiting 0.)

- [ ] **Step 3: Write `TracedUseCases`**

Create `src/main/java/com/ffroliva/tinyledger/config/TracedUseCases.java`:

```java
package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Spec §6.6: <strong>domain spans are added by decoration, not by annotation.</strong> This is the
 * same shape, in the same place, as {@link TransactionalUseCases} — and for the same reason:
 * {@code ..application..} imports no Micrometer type, exactly as it imports no {@code @Transactional}.
 * {@code HexagonalRulesTest} is the authority, and it stays green because nothing here leaks inward.
 *
 * <p><strong>This is the OUTERMOST decorator</strong> — {@code traced → transactional → service}. A
 * span that ends before the commit reports a write as faster than it is, which is the same class of
 * defect as summing a gauge that should be maxed: a plausible number that is false. That ordering is
 * why {@code FullAdapterConfig}'s transactional beans give up {@code @Primary} and declare their
 * concrete types — two {@code @Primary} candidates for one interface is a context-startup failure,
 * not a warning.
 *
 * <p><strong>Cardinality (§6.6, and there is no gate).</strong> The account id, the movement uid and
 * the interaction id go on the SPAN. The counter's tags are {@code type}, {@code outcome} and
 * {@code reason} — three enumerable sets whose product is about sixteen series and does not grow
 * with traffic. {@code reason} is {@code none} rather than absent on a settled movement, so every
 * series of this meter carries the same tag keys.
 *
 * <p>{@code reason}'s values are domain literals — today {@code currency-mismatch} and
 * {@code insufficient-funds}, both from {@code Account}. Interpolating a request detail into that
 * string would turn this counter unbounded, and nothing would catch it.
 */
final class TracedUseCases {

    static final String MOVEMENTS = "ledger.movements";

    private TracedUseCases() {}

    static class Opening implements OpenAccountUseCase {
        private final OpenAccountUseCase delegate;
        private final Tracer tracer;

        Opening(OpenAccountUseCase delegate, Tracer tracer) {
            this.delegate = delegate;
            this.tracer = tracer;
        }

        @Override
        public OpenedAccount open(OpenAccount cmd) {
            Span span = tracer.nextSpan().name("ledger.open-account").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                OpenedAccount opened = delegate.open(cmd);
                span.tag("ledger.account_id", opened.accountId().value().toString());
                span.tag("ledger.stream_version", Long.toString(opened.version()));
                return opened;
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        }
    }

    static class Movements implements RecordMovementUseCase {
        private final RecordMovementUseCase delegate;
        private final Tracer tracer;
        private final MeterRegistry meters;

        Movements(RecordMovementUseCase delegate, Tracer tracer, MeterRegistry meters) {
            this.delegate = delegate;
            this.tracer = tracer;
            this.meters = meters;
        }

        @Override
        public MovementResult deposit(Deposit cmd) {
            return record(cmd.accountId().value().toString(), () -> delegate.deposit(cmd));
        }

        @Override
        public MovementResult withdraw(Withdraw cmd) {
            return record(cmd.accountId().value().toString(), () -> delegate.withdraw(cmd));
        }

        private MovementResult record(String accountId, java.util.function.Supplier<MovementResult> call) {
            Span span = tracer.nextSpan().name("ledger.record-movement").start();
            span.tag("ledger.account_id", accountId);
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                MovementResult result = call.get();
                span.tag("ledger.movement_type", result.type().name());
                span.tag("ledger.stream_version", Long.toString(result.version()));
                if (result.rejectionReason() != null) {
                    span.tag("ledger.rejection_reason", result.rejectionReason());
                }
                count(result.type().name(), result.outcome().name().toLowerCase(java.util.Locale.ROOT), reasonOf(result));
                return result;
            } catch (ConcurrencyConflictException e) {
                // Counted as its own outcome rather than folded into an error rate: §6.6 asks for a
                // concurrency-conflict rate by name, and a conflict is an ordinary, expected outcome
                // of an optimistic append — not a fault.
                span.error(e);
                count(typeOf(e), "conflict", "none");
                throw e;
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        }

        /** A conflict carries no MovementType — the append never got far enough to have one. */
        private static String typeOf(ConcurrencyConflictException ignored) {
            return "unknown";
        }

        private static String reasonOf(MovementResult result) {
            return result.rejectionReason() == null ? "none" : result.rejectionReason();
        }

        private void count(String type, String outcome, String reason) {
            Counter.builder(MOVEMENTS)
                    .description("Movements recorded, by type and outcome (spec §6.6)")
                    .tag("type", type)
                    .tag("outcome", outcome)
                    .tag("reason", reason)
                    .register(meters)
                    .increment();
        }
    }
}
```

- [ ] **Step 4: Run the test — it must pass**

```bash
./mvnw -q test -Dtest=TracedUseCasesTest 2>&1 | tail -20; echo "EXIT=$?"
```

Expected: `EXIT=0`, 6 tests.

- [ ] **Step 5: Wire it — `UseCaseConfig` gets the `@Primary` traced beans**

In `src/main/java/com/ffroliva/tinyledger/config/UseCaseConfig.java`, add these imports:

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
```

and add, after the `recordMovement` bean:

```java
    /**
     * §6.6 / §14 step 9 part 2. Profile-independent, like everything else in this class — and it has
     * to be, because the chain differs by profile and the decorator does not:
     *
     * <pre>
     *   full:        traced -> transactional -> service
     *   standalone:  traced -> service
     * </pre>
     *
     * <p>The {@code ObjectProvider} is what makes one bean method cover both. In {@code full},
     * {@code FullAdapterConfig} contributes the transactional decorator and it is selected; in
     * {@code standalone} there is none and the plain service is used. Tracing is OUTERMOST so the
     * span covers the commit — see {@link TracedUseCases}.
     */
    @Bean
    @Primary
    OpenAccountUseCase tracedOpenAccount(
            ObjectProvider<TransactionalUseCases.Opening> transactional, OpenAccountService plain, Tracer tracer) {
        // NOT getIfAvailable(Supplier): CORRECTED during execution. That overload fixes the
        // supplier's type to the provider's own generic, so `() -> plain` fails to compile with
        // "OpenAccountService cannot be converted to TransactionalUseCases.Opening". Two lines.
        TransactionalUseCases.Opening decorated = transactional.getIfAvailable();
        return new TracedUseCases.Opening(decorated != null ? decorated : plain, tracer);
    }

    @Bean
    @Primary
    RecordMovementUseCase tracedRecordMovement(
            ObjectProvider<TransactionalUseCases.Movements> transactional,
            RecordMovementService plain,
            Tracer tracer,
            MeterRegistry meters) {
        TransactionalUseCases.Movements decorated = transactional.getIfAvailable();
        return new TracedUseCases.Movements(decorated != null ? decorated : plain, tracer, meters);
    }
```

**Two corrections made during execution, recorded here rather than left for the next reader:**
`SimpleTracer`'s accessor is `onlySpan()`, not `getOnlySpan()` (Micrometer Tracing 1.7.0), and the
`ObjectProvider` overload above. Both were compile failures on the first run, which is the cheap
kind. `@Primary`'s import in `FullAdapterConfig` turned out to be unused after the change — spotless
removes it.

- [ ] **Step 6: `FullAdapterConfig` gives up `@Primary`**

Replace `src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java:161-171` with:

```java
    /**
     * <strong>No {@code @Primary}, and a concrete return type — both deliberate, both required.</strong>
     * {@code UseCaseConfig}'s traced decorator is the {@code @Primary} bean now (§14 step 9 part 2), and
     * two {@code @Primary} candidates for one interface is a context-startup failure. The concrete type
     * is what lets that decorator select this bean by {@code ObjectProvider} in {@code full} and fall
     * back to the plain service in {@code standalone}, from a single profile-independent bean method.
     * Declaring {@code OpenAccountUseCase} here instead would make the provider ambiguous.
     */
    @Bean
    public TransactionalUseCases.Opening transactionalOpenAccount(OpenAccountService delegate) {
        return new TransactionalUseCases.Opening(delegate);
    }

    /** See {@link #transactionalOpenAccount} — same two constraints, same reason. */
    @Bean
    public TransactionalUseCases.Movements transactionalRecordMovement(RecordMovementService delegate) {
        return new TransactionalUseCases.Movements(delegate);
    }
```

Remove the now-unused `OpenAccountUseCase`, `RecordMovementUseCase` and `Primary` imports **only if
nothing else in the file uses them** — `@Primary` is still used by other beans in this class, so
check before deleting.

`TransactionalUseCases` and its two nested classes are package-private. Spring must be able to see
them as bean types from within `..config..`, which it can. But `@Transactional` needs a proxy, and
with `proxyTargetClass=true` (Boot's default) that is a CGLIB subclass — which requires the class and
its methods to be non-final and visible. They are. **If the context fails to start here, the fallback
is to widen `TransactionalUseCases` and its nested classes to `public`** and record why in the
javadoc; do not reintroduce `@Primary`.

- [ ] **Step 7: Run the whole gate**

```bash
./mvnw verify > /tmp/t3.log 2>&1; echo "EXIT=$?"
grep -c "Creating container for image" /tmp/t3.log
grep -ho '<testcase ' target/surefire-reports/*.xml | wc -l
```

Expected: `EXIT=0`, containers **0**, testcase count = Task 1's baseline + 8 (6 in
`TracedUseCasesTest`, 2 added to `ActuatorProbeTest` in Tasks 1 and 2).

`HexagonalRulesTest` runs here. It must stay green — nothing in `TracedUseCases` is imported by
`..application..`. If it reddens, the decorator has been put in the wrong package.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/ffroliva/tinyledger/config/TracedUseCases.java \
        src/main/java/com/ffroliva/tinyledger/config/UseCaseConfig.java \
        src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java \
        src/test/java/com/ffroliva/tinyledger/config/TracedUseCasesTest.java \
        pom.xml
git commit -m "feat: domain spans and the movement counter, by decoration

Spec §6.6: domain spans are added by decoration, not by annotation.
TracedUseCases is the same shape in the same place as TransactionalUseCases, so
..application.. still imports no Micrometer type — HexagonalRulesTest is the
authority and stays green.

Tracing is the OUTERMOST decorator so the span covers the commit. A span that
ends before the commit reports a write as faster than it is. That ordering is
why FullAdapterConfig's transactional beans give up @Primary and declare their
concrete types: two @Primary candidates for one interface is a context-startup
failure, and the ObjectProvider is what lets one profile-independent bean
method cover both chains.

One counter, ledger.movements, tagged type/outcome/reason — about sixteen
series, none of which grows with traffic. The account id and the movement uid
go on the span. §6.6's cardinality rule has no gate;
noMeterTagCarriesAnAccountIdOrAMovementUid is the nearest thing to one, and it
covers this meter only."
```

---

## Task 4: The Kafka hop — produce, and a consume span that is a LINK

Spec §6.6: *"Fan-out uses span links, not parent-child."* A child span makes the producing request
appear to last until the slowest consumer finishes and misreports `http.server.duration` to every
dashboard. Spring Kafka's listener observation produces exactly that child, so it stays **off** and
the consume span is built by hand.

Verified against `micrometer-tracing-bridge-otel-1.7.0`, not assumed: `OtelSpanBuilder` really
overrides `addLink(Link)` (the interface default would be a silent no-op), and
`OtelTraceContextBuilder` implements `traceId`/`spanId`/`sampled`.

**Files:**
- Modify: `src/main/resources/application-full.properties`
- Modify: `src/main/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListener.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java:116-119`
- Create: `src/test/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditSpanLinkTest.java`

- [ ] **Step 1: Turn on producer-side observation**

Append to `src/main/resources/application-full.properties`:

```properties
# §6.6, the "Producer -> Kafka -> consumer" row of the trace-context table. This is what injects the
# W3C `traceparent` header into every externalized record — Modulith publishes through the
# auto-configured KafkaTemplate, so enabling observation on the template covers the relay without
# ADR 0001's delivery path changing at all.
spring.kafka.template.observation-enabled=true
# LISTENER observation stays OFF, deliberately and not by omission. It would make the audit
# consumer's span a CHILD of the producing span, which is the one thing §6.6 refuses: the request
# span would then appear to last until the slowest consumer finishes. AuditKafkaListener builds a
# LINKED span by hand instead.
spring.kafka.listener.observation-enabled=false
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditSpanLinkTest.java`:

```java
package com.ffroliva.tinyledger.audit.adapter.in.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.TraceContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The parsing half of the Kafka-hop link, unit-tested with no broker and no tracer. The linking half
 * is asserted end to end by {@code ObservabilityIT} against a real hop — this class exists because a
 * malformed `traceparent` must degrade to "no link" and never to a thrown exception: the audit trail
 * is a compliance record, and losing an entry over a telemetry header would be the tail wagging the
 * dog.
 */
class AuditSpanLinkTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void aWellFormedTraceparentYieldsItsTraceAndSpanIds() {
        Optional<TraceparentRef> parsed = TraceparentRef.parse("00-" + TRACE_ID + "-" + SPAN_ID + "-01");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().traceId()).isEqualTo(TRACE_ID);
        assertThat(parsed.get().spanId()).isEqualTo(SPAN_ID);
        assertThat(parsed.get().sampled()).isTrue();
    }

    @Test
    void anUnsampledFlagIsCarriedThroughRatherThanForcedTrue() {
        assertThat(TraceparentRef.parse("00-" + TRACE_ID + "-" + SPAN_ID + "-00"))
                .get()
                .extracting(TraceparentRef::sampled)
                .isEqualTo(false);
    }

    @Test
    void aMalformedOrAbsentHeaderYieldsNoLinkAndNeverThrows() {
        assertThat(TraceparentRef.parse(null)).isEmpty();
        assertThat(TraceparentRef.parse("")).isEmpty();
        assertThat(TraceparentRef.parse("garbage")).isEmpty();
        assertThat(TraceparentRef.parse("00-tooshort-" + SPAN_ID + "-01")).isEmpty();
        assertThat(TraceparentRef.parse("00-" + TRACE_ID + "-" + SPAN_ID)).isEmpty();
    }

    @Test
    void anAllZeroTraceIdIsRefused() {
        assertThat(TraceparentRef.parse("00-" + "0".repeat(32) + "-" + SPAN_ID + "-01"))
                .as("W3C: an all-zero id is the invalid sentinel, and a link to it is worse than none")
                .isEmpty();
    }

    @Test
    void itBuildsATraceContextThroughTheTracersOwnBuilder() {
        TraceContext.Builder builder = new io.micrometer.tracing.otel.bridge.OtelTraceContextBuilder();
        TraceContext context = TraceparentRef.parse("00-" + TRACE_ID + "-" + SPAN_ID + "-01")
                .orElseThrow()
                .toTraceContext(builder);

        assertThat(context.traceId()).isEqualTo(TRACE_ID);
        assertThat(context.spanId()).isEqualTo(SPAN_ID);
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
./mvnw -q test -Dtest=AuditSpanLinkTest 2>&1 | tail -20; echo "EXIT=$?"
```

Expected: compilation failure naming `TraceparentRef`.

- [ ] **Step 4: Write `TraceparentRef` and link the consume span**

Create `src/main/java/com/ffroliva/tinyledger/audit/adapter/in/events/TraceparentRef.java`:

```java
package com.ffroliva.tinyledger.audit.adapter.in.events;

import io.micrometer.tracing.TraceContext;
import java.util.Optional;

/**
 * The W3C {@code traceparent} header, split into the two ids a span LINK needs.
 *
 * <p>Parsed here rather than through {@code Propagator.extract}, because extraction returns a span
 * builder with a remote <em>parent</em> — and a parent is precisely what §6.6 refuses for fan-out.
 * The format is fixed and four fields wide: {@code 00-<32 hex traceId>-<16 hex spanId>-<2 hex flags>}.
 *
 * <p>Every failure is an empty result, never an exception. This header is telemetry; the record is a
 * compliance entry. Losing an audit entry because a tracing header was malformed would invert their
 * importance exactly.
 */
record TraceparentRef(String traceId, String spanId, boolean sampled) {

    private static final int TRACE_ID_LENGTH = 32;
    private static final int SPAN_ID_LENGTH = 16;

    static Optional<TraceparentRef> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String[] parts = header.split("-");
        if (parts.length != 4) {
            return Optional.empty();
        }
        String traceId = parts[1];
        String spanId = parts[2];
        if (!isHexOfLength(traceId, TRACE_ID_LENGTH) || !isHexOfLength(spanId, SPAN_ID_LENGTH)) {
            return Optional.empty();
        }
        if (isAllZeroes(traceId) || isAllZeroes(spanId)) {
            return Optional.empty();
        }
        boolean sampled = parts[3].length() == 2 && (Integer.parseInt(parts[3], 16) & 0x01) == 1;
        return Optional.of(new TraceparentRef(traceId, spanId, sampled));
    }

    TraceContext toTraceContext(TraceContext.Builder builder) {
        return builder.traceId(traceId).spanId(spanId).sampled(sampled).build();
    }

    private static boolean isHexOfLength(String value, int length) {
        if (value.length() != length) {
            return false;
        }
        return value.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    private static boolean isAllZeroes(String value) {
        return value.chars().allMatch(c -> c == '0');
    }
}
```

Then modify `AuditKafkaListener`. Add these imports:

```java
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
```

change the field and constructor:

```java
    private final AuditTrailPort trail;
    private final Tracer tracer;

    public AuditKafkaListener(AuditTrailPort trail, Tracer tracer) {
        this.trail = trail;
        this.tracer = tracer;
    }
```

and replace the body of `on(ConsumerRecord)`:

```java
    /**
     * <strong>The consume span is LINKED to the producing span, not parented by it (§6.6).</strong>
     * One write fans out to balance, notification and audit concurrently; modelling those as children
     * of the HTTP span would make the request appear to last until the slowest of them finishes and
     * would misreport {@code http.server.duration} to every dashboard built on it. A link is the OTel
     * semantic for asynchronous fan-out and keeps the request's own duration honest.
     *
     * <p>Which is also why {@code spring.kafka.listener.observation-enabled} is {@code false} rather
     * than merely unset: Spring Kafka's listener observation would create the child this refuses.
     */
    @KafkaListener(topics = "ledger.events", groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> consumed) {
        Span span = consumeSpan(consumed);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Instant occurredAt = Instant.parse(header(consumed, "occurred-at"));
            span.tag("ledger.account_id", consumed.key());
            span.tag("ledger.stream_version", header(consumed, "stream-version"));
            trail.recordEntry(new AuditTrailPort.AuditEntry(
                    UUID.fromString(consumed.key()),
                    header(consumed, "event-type"),
                    Long.parseLong(header(consumed, "stream-version")),
                    occurredAt,
                    // §7's recordedAt: when the audit module saw the event, which is here — the Kafka
                    // hop is exactly the gap between this and occurredAt.
                    Instant.now(),
                    consumed.value(),
                    actorOf(consumed, occurredAt)));
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** A new root, linked back to the producer — never a child. Absent or malformed header: no link. */
    private Span consumeSpan(ConsumerRecord<String, String> consumed) {
        Span.Builder builder = tracer.spanBuilder().name("ledger.audit.record").setNoParent();
        Header traceparent = consumed.headers().lastHeader("traceparent");
        TraceparentRef.parse(traceparent == null ? null : new String(traceparent.value(), StandardCharsets.UTF_8))
                .map(ref -> ref.toTraceContext(tracer.traceContextBuilder()))
                .ifPresent(context -> builder.addLink(new Link(context)));
        return builder.start();
    }
```

Update the construction in `FullAdapterConfig`:

```java
    @Bean
    public AuditKafkaListener auditKafkaListener(AuditTrailPort trail, Tracer tracer) {
        return new AuditKafkaListener(trail, tracer);
    }
```

adding `import io.micrometer.tracing.Tracer;`.

`AuditKafkaListenerTest` constructs the listener directly and will no longer compile. Give it
`new SimpleTracer()` (from `micrometer-tracing-test`, added in Task 3) — it needs no other change.

- [ ] **Step 5: Run both tests**

```bash
./mvnw -q test -Dtest='AuditSpanLinkTest,AuditKafkaListenerTest' 2>&1 | tail -20; echo "EXIT=$?"
grep -ho '<testcase ' target/surefire-reports/TEST-*AuditSpanLinkTest.xml | wc -l
```

Expected: `EXIT=0`, and **5** testcases in `AuditSpanLinkTest`'s XML. The second command is the
guard that the named class actually ran; a `-Dtest` pattern that matches nothing produces no XML.

- [ ] **Step 6: Full gate and commit**

```bash
./mvnw verify > /tmp/t4.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t4.log
git add src/main/resources/application-full.properties \
        src/main/java/com/ffroliva/tinyledger/audit/adapter/in/events/TraceparentRef.java \
        src/main/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListener.java \
        src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java \
        src/test/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditSpanLinkTest.java \
        src/test/java/com/ffroliva/tinyledger/audit/adapter/in/events/AuditKafkaListenerTest.java
git commit -m "feat: the audit consumer's span links to the producer, never parents from it

Spec §6.6: fan-out uses span links, not parent-child. One write fans out to
balance, notification and audit concurrently — as children, the HTTP span would
appear to last until the slowest consumer finished, misreporting
http.server.duration to every dashboard built on it.

So spring.kafka.listener.observation-enabled is false EXPLICITLY, not by
omission: Spring Kafka's listener observation creates exactly the child this
refuses. AuditKafkaListener starts a new root and adds a link instead.

traceparent is parsed here rather than through Propagator.extract, because
extraction hands back a builder with a remote PARENT. Every parse failure is an
empty Optional and never an exception: this header is telemetry, the record is
a compliance entry, and losing an audit entry to a malformed tracing header
would invert their importance exactly.

Verified against micrometer-tracing-bridge-otel-1.7.0 rather than assumed:
OtelSpanBuilder really overrides addLink(Link) — the interface default is a
silent no-op."
```

---

## Task 5: The projection-apply span, `InMemorySpanExporter`, and §9.4's three assertions

This is **half one of the done-when**.

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/balance/adapter/in/events/LedgerEventsListener.java`
- Create: `src/test/java/com/ffroliva/tinyledger/testsupport/ObservabilityTestConfig.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java`
- Create: `src/test/java/com/ffroliva/tinyledger/observability/ObservabilityIT.java`

- [ ] **Step 1: The projection-apply span**

Replace the body of `LedgerEventsListener`:

```java
@Component
public class LedgerEventsListener {
    private final BalanceProjector projector;
    private final Tracer tracer;

    public LedgerEventsListener(BalanceProjector projector, Tracer tracer) {
        this.projector = projector;
        this.tracer = tracer;
    }

    /**
     * §6.6's "projection apply" span. A genuine CHILD, unlike the Kafka hop's link — and the trace
     * shape is the evidence for the claim §6.6 rests on: this listener is synchronous, on the
     * publishing thread, inside the write transaction, so its cost is part of the request's cost and
     * belongs inside the request's span. Projection lag here is structurally zero (ADR 0004), and a
     * span that turned out NOT to be nested would be the first observation to contradict that.
     */
    @EventListener
    void on(LedgerEvent event) {
        Span span = tracer.nextSpan().name("ledger.projection.apply").start();
        span.tag("ledger.account_id", event.accountId().value().toString());
        span.tag("ledger.stream_version", Long.toString(event.version()));
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            projector.on(event);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

with imports `io.micrometer.tracing.Span` and `io.micrometer.tracing.Tracer`. Keep the existing
class javadoc verbatim — it records the `@ApplicationModuleListener` decision and is still true.

`LedgerEventsListenerTest` boots the whole standalone context and autowires, so it needs no change.
Any test constructing the listener directly must pass `new SimpleTracer()`.

- [ ] **Step 2: The in-memory exporter, on the shared base**

Create `src/test/java/com/ffroliva/tinyledger/testsupport/ObservabilityTestConfig.java`:

```java
package com.ffroliva.tinyledger.testsupport;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * §9.4's {@code InMemorySpanExporter}, contributed to the ONE shared {@code full} context.
 *
 * <p><strong>Imported on {@link AbstractIntegrationTest} itself, never on a subclass.</strong> ADR
 * 0003 §1 and {@code AGENTS.md} trap 5: an {@code @Import} on a subclass forks the context by
 * definition, and CR13 — a second {@code AuditKafkaListener} joining the same consumer group and
 * stealing partitions — was that fork's symptom. On the base it moves the cache key uniformly, so
 * every IT still shares one context.
 *
 * <p>Boot collects {@code SpanExporter} beans and wraps them in a batch processor. That processor's
 * schedule delay is lowered by {@code AbstractIntegrationTest}'s property source rather than by
 * substituting a {@code SimpleSpanProcessor} here: registering a processor AND an exporter would
 * export every span twice, which reads as a duplicated span rather than as a configuration mistake.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ObservabilityTestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }
}
```

In `AbstractIntegrationTest`, add `@Import(ObservabilityTestConfig.class)` beneath
`@AutoConfigureMockMvc`, and add to the existing `@DynamicPropertySource` method:

```java
        // §14 step 9 part 2. Boot silences telemetry export in tests: TracingContextCustomizerFactory
        // injects `management.tracing.export.enabled=false` unless `spring.test.tracing.export` is set
        // (read from the bytecode of spring-boot-micrometer-tracing-test-4.1.0, not from docs). Set as
        // a PROPERTY through this existing source rather than with @AutoConfigureTracing, because the
        // annotation would be a per-class declaration and ADR 0003 §1 requires properties here.
        registry.add("spring.test.tracing.export", () -> "true");
        // The batch span processor's default delay is 5s. Lowered so ObservabilityIT's Awaitility
        // window is spent waiting for the Kafka hop, not for a scheduler.
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");
```

- [ ] **Step 3: Write the failing IT**

Create `src/test/java/com/ffroliva/tinyledger/observability/ObservabilityIT.java`:

```java
package com.ffroliva.tinyledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec §9.4's observability assertions — half one of §14 step 9's done-when.
 *
 * <p>Runs on the shared {@code full} context and forks nothing: the exporter arrives by an
 * {@code @Import} on {@code AbstractIntegrationTest} and the two properties through the
 * {@code @DynamicPropertySource} that was already there (ADR 0003 §1).
 *
 * <p>Each test clears the exporter first. The context is shared, so a span left by an earlier class
 * would make an assertion pass for the wrong reason — the same hazard {@code AuditLagIT} records for
 * the outbox gauge.
 */
class ObservabilityIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemorySpanExporter spans;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void clearSpans() {
        spans.reset();
    }

    private String openAccount() throws Exception {
        String body = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"observability\",\"currency\":\"GBP\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return body.replaceAll(".*\"accountUid\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
    }

    private List<SpanData> finished() {
        return spans.getFinishedSpanItems();
    }

    @Test
    void aWithdrawalProducesTheExpectedSpanTreeWithTheDomainAttributes() throws Exception {
        String account = openAccount();
        mockMvc.perform(put("/api/v1/accounts/" + account + "/deposits/" + UUID.randomUUID())
                        .header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":5000}}"))
                .andExpect(status().isOk());
        spans.reset();

        mockMvc.perform(put("/api/v1/accounts/" + account + "/withdrawals/" + UUID.randomUUID())
                        .header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":2000}}"))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            SpanData movement = finished().stream()
                    .filter(s -> "ledger.record-movement".equals(s.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no ledger.record-movement span; saw " + finished().stream().map(SpanData::getName).toList()));

            assertThat(movement.getAttributes().asMap())
                    .hasToString(movement.getAttributes().asMap().toString());
            assertThat(attribute(movement, "ledger.account_id")).isEqualTo(account);
            assertThat(attribute(movement, "ledger.movement_type")).isEqualTo("WITHDRAWAL");
            assertThat(attribute(movement, "ledger.stream_version")).isNotBlank();

            SpanData projection = finished().stream()
                    .filter(s -> "ledger.projection.apply".equals(s.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no ledger.projection.apply span"));
            assertThat(projection.getParentSpanId())
                    .as("the projection is synchronous inside the write (§4.3), so its span nests")
                    .isEqualTo(movement.getSpanId());
            assertThat(projection.getTraceId()).isEqualTo(movement.getTraceId());
        });
    }

    @Test
    void theAuditConsumersSpanLinksBackToTheProducerAndIsNotADetachedRoot() throws Exception {
        String account = openAccount();
        mockMvc.perform(put("/api/v1/accounts/" + account + "/deposits/" + UUID.randomUUID())
                        .header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":1500}}"))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            SpanData consume = finished().stream()
                    .filter(s -> "ledger.audit.record".equals(s.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no ledger.audit.record span; saw " + finished().stream().map(SpanData::getName).toList()));

            assertThat(consume.getLinks())
                    .as("§6.6: the consumer LINKS back to the producer — a child would misreport request latency")
                    .isNotEmpty();
            assertThat(consume.getParentSpanId())
                    .as("...and it is a new root, so it must have no parent")
                    .isEqualTo("0000000000000000");
            assertThat(consume.getLinks().getFirst().getSpanContext().getTraceId())
                    .as("...but it is not detached: the link carries the producing trace")
                    .isNotEqualTo(consume.getTraceId())
                    .isNotEqualTo("00000000000000000000000000000000");
        });
    }

    @Test
    void aRejectedMovementIncrementsTheCounterUnderItsReason() throws Exception {
        String account = openAccount();
        double before = rejections("insufficient-funds");

        mockMvc.perform(put("/api/v1/accounts/" + account + "/withdrawals/" + UUID.randomUUID())
                        .header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":9999}}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(rejections("insufficient-funds")).isEqualTo(before + 1.0);
    }

    private double rejections(String reason) {
        var counter = meters.find("ledger.movements")
                .tag("outcome", "rejected")
                .tag("reason", reason)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String attribute(SpanData span, String key) {
        return String.valueOf(span.getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey(key)));
    }
}
```

**Note on the request bodies and the exact response shape:** they must match `docs/api/openapi.yaml`
and the existing `SecurityConfigIT` call sites. Open `SecurityConfigIT` and copy the deposit and
withdrawal payloads verbatim rather than trusting the sketch above — the account-uid extraction in
particular should reuse whatever helper that class already has. **If `SecurityConfigIT` has an
`openAnAccountAs` helper, extract it to `AbstractIntegrationTest` and use it in both**, rather than
writing a second copy.

- [ ] **Step 4: Recount the rate-limit budget — this is not optional**

`AbstractIntegrationTest`'s two constants are sized against an enumerated call count, and
`ObservabilityIT` adds requests to the shared `ip-backstop:127.0.0.1` bucket and to `alice`'s
write bucket.

`ObservabilityIT` issues **7** requests, all as `alice`, **6** of them charged writes: three
`openAccount`, two deposits, one withdrawal (a refused write still charges — the limiter runs ahead
of authorisation), plus one refused withdrawal.

Update the javadoc arithmetic on `LOWERED_WRITE_LIMIT` and `RAISED_IP_BACKSTOP_LIMIT`: alice moves
from 11 to **18** charged writes against the limit of 150, and the enumerated ip-backstop total moves
from ~325 to ~**332** against 1000. Both still have wide margins, so **neither constant changes** —
but the count must be written down, because the next person to add a test recounts from it.

- [ ] **Step 5: Push and read CI — do NOT run `-Pit` locally**

```bash
git add -- src/main/java/com/ffroliva/tinyledger/balance/adapter/in/events/LedgerEventsListener.java \
           src/test/java/com/ffroliva/tinyledger/testsupport/ObservabilityTestConfig.java \
           src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java \
           src/test/java/com/ffroliva/tinyledger/observability/ObservabilityIT.java
./mvnw verify > /tmp/t5.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t5.log
git commit -m "test: §9.4's observability assertions on the shared full context"
git push
gh run watch
```

Expected: `verify` green with **0** containers locally; the integration job green on CI.

Read the count from the failsafe XML **paired with the run's conclusion** (AGENTS trap 3):

```bash
gh run view <id> --log | grep -c "Creating container for image"    # control: MUST be non-zero
```

- [ ] **Step 6: Three red proofs, one per assertion, run on CI**

Each on a throwaway branch off this one, with the PR left closed afterwards and the branch deleted.
For each: confirm from the failsafe XML that **`ObservabilityIT` ran** and that the failure is the
named assertion.

| # | Revert | Must redden |
|---|---|---|
| 1 | Delete `span.tag("ledger.account_id", …)` from `TracedUseCases.Movements#record` | `aWithdrawalProducesTheExpectedSpanTreeWithTheDomainAttributes`, on `ledger.account_id` |
| 2 | Replace `.setNoParent().addLink(…)` with a plain `tracer.nextSpan()` in `AuditKafkaListener#consumeSpan` | `theAuditConsumersSpanLinksBackToTheProducerAndIsNotADetachedRoot`, on `getLinks()` being empty |
| 3 | Delete the `count(...)` call from the settled path of `TracedUseCases.Movements#record` | `aRejectedMovementIncrementsTheCounterUnderItsReason` |

**Check what reddened, not merely that something did.** Part 1 produced two reds that failed for the
wrong reason — a context-startup error and a JDBC hang. A `NoSuchBeanDefinitionException` or a
timeout is *not* a discharged proof.

- [ ] **Step 7: Commit the proofs into the message**

Amend or add a commit whose message quotes the three exact failure lines from the CI logs.

---

## Task 6: JSON logs in `full`

**Files:**
- Modify: `src/main/resources/application-full.properties`
- Create: `src/test/java/com/ffroliva/tinyledger/platform/StructuredLoggingTest.java`

- [ ] **Step 1: One property**

Append to `application-full.properties`:

```properties
# §6.6's Logs row. Boot's BUILT-IN structured logging, so no logstash-logback-encoder dependency and
# no logback-spring.xml — the encoder the spec names is a format here, not a jar.
#
# `full` ONLY, and that is a narrowing of §6.6 recorded at v3.41 rather than an oversight. `full` is
# the production-shaped mode; `standalone` is the mode a human runs and reads, and turning its console
# into JSON would be paid on every local `./mvnw verify` and every CI failure log for a benefit taken
# in production. Correlation is NOT lost in standalone: Boot's tracing log pattern puts the
# application name, trace id and span id on every line in both modes. Only the encoding differs.
logging.structured.format.console=logstash
```

- [ ] **Step 2: Pin it with a test**

```java
package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * A properties-file assertion, not a context test, and deliberately so: the claim is about which
 * FILE declares the key. §6.6 records JSON logs as a `full`-only narrowing, and the way that decision
 * gets silently reversed is someone moving this line into the base file — where it would turn every
 * local run and every CI failure log into JSON. The base-file assertion is the half that matters.
 */
class StructuredLoggingTest {

    private static Properties read(String name) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources", name))) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    void fullLogsJsonAndTheBaseProfileDoesNot() throws IOException {
        assertThat(read("application-full.properties").getProperty("logging.structured.format.console"))
                .isEqualTo("logstash");
        assertThat(read("application.properties").getProperty("logging.structured.format.console"))
                .as("§6.6 v3.41: JSON is a `full`-only narrowing; in the base file it would hit every verify")
                .isNull();
        assertThat(read("application-standalone.properties").getProperty("logging.structured.format.console"))
                .isNull();
    }
}
```

- [ ] **Step 3: Red proof**

```bash
git stash push src/main/resources/application-full.properties
./mvnw -q test -Dtest=StructuredLoggingTest 2>&1 | tail -15; echo "EXIT=$?"
ls target/surefire-reports/TEST-*StructuredLoggingTest.xml
git stash pop
```

Expected: non-zero exit; the XML file **exists**; the failure is `expected: "logstash" but was: null`.

- [ ] **Step 4: Green, then commit**

```bash
./mvnw verify > /tmp/t6.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t6.log
git add src/main/resources/application-full.properties \
        src/test/java/com/ffroliva/tinyledger/platform/StructuredLoggingTest.java
git commit -m "feat: JSON logs in full, human-readable console in standalone

§6.6's Logs row, narrowed deliberately and recorded at v3.41. Boot's built-in
structured logging, so no logstash-logback-encoder and no logback-spring.xml.

full is the production-shaped mode; standalone is the one a human runs and
reads. JSON in the base file would be paid on every local verify and every CI
failure log for a benefit taken in production. Correlation is not lost either
way — Boot's tracing log pattern stamps trace id and span id on every line in
both modes; only the encoding differs.

The test asserts the BASE file does not declare the key, because that is how
this decision gets silently reversed."
```

---

## Task 7: The two audit blind spots

§6.6: *"Consumer lag and the dead-letter topic are unobserved… Both belong to step 9 part 2, where
an exporter exists to carry them."*

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java:145-159`
- Modify: `src/test/java/com/ffroliva/tinyledger/config/KafkaOutageIT.java` *(or a new IT — see step 3)*

- [ ] **Step 1: Count what lands on the dead-letter topic**

Replace `FullAdapterConfig#auditListenerErrorHandler`'s recoverer with a counting wrapper:

```java
    @Bean
    public DefaultErrorHandler auditListenerErrorHandler(
            ProducerFactory<?, ?> producerFactory, MeterRegistry meterRegistry) {
        KafkaTemplate<String, String> deadLetters = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(), new StringSerializer(), new StringSerializer()));
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetters, (consumed, exception) -> new TopicPartition(LEDGER_EVENTS_DLT, -1));

        // §6.6 / §14 step 9 part 2. This handler's own javadoc says it exists to prevent "a silent,
        // permanent hole in the compliance trail" — and nothing counted what it parked, so it produced
        // one. Untagged on purpose: the account id and the exception type are both unbounded, and this
        // is a meter (§6.6's cardinality rule). Which record and why is answerable from the span and
        // the log line the recoverer already writes.
        Counter deadLettered = Counter.builder("ledger.audit.dead_lettered")
                .description("Records parked on " + LEDGER_EVENTS_DLT + " because the audit consumer could not process them")
                .register(meterRegistry);

        return new DefaultErrorHandler(
                (consumed, exception) -> {
                    deadLettered.increment();
                    recoverer.accept(consumed, exception);
                },
                new FixedBackOff(1_000L, 9));
    }
```

with `import io.micrometer.core.instrument.Counter;`. Keep the existing javadoc above the method; add
the paragraph explaining the counter.

- [ ] **Step 2: Establish whether consumer lag is free — by looking, not by hoping**

```bash
unzip -p ~/.m2/repository/org/springframework/boot/spring-boot-micrometer-metrics/4.1.0/spring-boot-micrometer-metrics-4.1.0.jar \
  META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports | grep -i kafka
```

Control the search first — run the same command with `grep -ic autoconfiguration` and confirm it
returns a non-zero count, so an empty Kafka result is an absence rather than a broken pipe
(AGENTS trap 7).

**If a `KafkaMetricsAutoConfiguration` is listed:** consumer lag arrives as
`kafka.consumer.records.lag.max` with no code at all. Record the meter name in §6.6 and add an
assertion to the IT in step 3.

**If it is not listed:** wire it explicitly with a `MicrometerConsumerListener` on the consumer
factory in `FullAdapterConfig`, or — if that turns out to need more than a few lines — record the
row as **not delivered**, with this measurement as the reason. Do not leave it ambiguous, and do not
quietly drop it.

- [ ] **Step 3: Assert the dead-letter counter moves**

`KafkaOutageIT` already exercises the broker. A record the consumer cannot process is easier to
manufacture than an outage: publish a record to `ledger.events` whose `occurred-at` header is
unparseable, which makes `AuditKafkaListener#on` throw at `Instant.parse` and exhausts the nine
retries onto the DLT.

Add to the observability package, `src/test/java/com/ffroliva/tinyledger/observability/DeadLetterMetricIT.java`,
extending `AbstractIntegrationTest` (**not** a new context):

```java
    @Test
    void aRecordTheAuditConsumerCannotProcessIsCountedAsWellAsParked() {
        double before = count();
        kafkaTemplate.send(new ProducerRecord<>(
                "ledger.events",
                null,
                UUID.randomUUID().toString(),
                "{}".getBytes(StandardCharsets.UTF_8),
                List.of(
                        new RecordHeader("event-type", "MoneyDeposited".getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader("stream-version", "1".getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader("occurred-at", "not-an-instant".getBytes(StandardCharsets.UTF_8)))));

        // Nine retries at one second apart, then the publish: 30s is the floor, not a guess.
        await().atMost(Duration.ofSeconds(45))
                .untilAsserted(() -> assertThat(count()).isEqualTo(before + 1.0));
    }

    private double count() {
        var counter = meters.find("ledger.audit.dead_lettered").counter();
        return counter == null ? 0.0 : counter.count();
    }
```

**Two hazards to check before accepting this test.** First, `FixedBackOff(1_000L, 9)` means this test
takes at least ten seconds and is the slowest in the suite — if that is unacceptable, lower the
backoff for the test through the property source rather than shortening the wait. Second, a record
this listener cannot parse is a record the *whole shared context* sees; confirm no other IT asserts
on the total audit-entry count, or this test will break it.

- [ ] **Step 4: Gate, push, read CI, commit**

```bash
./mvnw verify > /tmp/t7.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t7.log
git add src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java \
        src/test/java/com/ffroliva/tinyledger/observability/DeadLetterMetricIT.java
git commit -m "feat: count what lands on the dead-letter topic

FullAdapterConfig's error handler says in its own javadoc that it exists to
prevent 'a silent, permanent hole in the compliance trail'. Nothing counted
what it parked, so it produced one. §6.6 records this as belonging to step 9
part 2, where an exporter exists to carry it.

Untagged on purpose: the account id and the exception type are both unbounded,
and this is a meter. Which record and why stays answerable from the span and
the log line the recoverer already writes.

Consumer lag: <record here what step 2 measured, and either the meter name or
the reason it is not delivered>."
```

---

## Task 8: `OtlpExportIT` — telemetry leaves the process

**This is half two of the done-when**, and it is the only test that can prove it.

**Files:**
- Create: `src/test/resources/otel-collector-test.yaml`
- Create: `src/test/java/com/ffroliva/tinyledger/observability/OtlpExportIT.java`

- [ ] **Step 1: The test Collector's configuration**

Create `src/test/resources/otel-collector-test.yaml`:

```yaml
# The CI-side Collector: OTLP in, a file out, nothing else. No credential, no third party — which is
# exactly why §14 step 9's gate is "a Collector receives OTLP" and not "Grafana Cloud receives OTLP".
# A fork's build passes with no secret at all.
#
# Deliberately NOT docker/otel-collector.yaml: that one tail-samples and forwards to Grafana Cloud.
# Tail sampling would make this test wait for a sampling decision and would drop the very traces it
# asserts on. The two configs differ on purpose and neither is generated from the other.
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  batch:
    timeout: 200ms

exporters:
  file:
    path: /tmp/otel-out.json

service:
  telemetry:
    logs:
      level: warn
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [file]
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [file]
```

- [ ] **Step 2: The test**

Create `src/test/java/com/ffroliva/tinyledger/observability/OtlpExportIT.java`:

```java
package com.ffroliva.tinyledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.springframework.web.client.RestClient;

/**
 * §14 step 9's second done-when half: <strong>a Collector container receives real spans and metrics
 * over OTLP.</strong> The nearest a build can get to "the dashboard works" — a hosted backend cannot
 * be asserted by any build, which is why §14's original wording was withdrawn at v3.40.
 *
 * <p><strong>{@code standalone}, and one container.</strong> Nothing asserted here needs Postgres,
 * Redis, Kafka or Keycloak. This is a separate profile context in the same category as
 * {@code CucumberSpringConfig} and {@code LedgerEventsListenerTest} — it does not fork the shared
 * {@code full} context, so ADR 0003's forking conditions never arise. §9.4 described this test as
 * forking {@code full} deliberately and as "the only fork in the suite"; both were written before it
 * existed and are corrected at v3.41.
 *
 * <p><strong>Two defaults would make this test lie, and both are overridden below.</strong> Boot
 * silences telemetry export in tests ({@code spring.test.tracing.export} /
 * {@code spring.test.metrics.export}, read from the shipped jars' bytecode), and the OTLP metrics
 * registry's step interval defaults to <em>sixty seconds</em> — a test that does not lower it waits a
 * minute or fails for no reason connected to its subject.
 *
 * <p>The file is read with {@code copyFileFromContainer}, which goes through the Docker API. The
 * contrib Collector image carries no shell and no {@code cat}, so {@code execInContainer} would fail
 * with something that looks like a Collector problem.
 */
@SpringBootTest(classes = TinyLedgerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OtlpExportIT {

    private static final String OUTPUT = "/tmp/otel-out.json";

    static final GenericContainer<?> COLLECTOR = new GenericContainer<>(
                    DockerImageName.parse("otel/opentelemetry-collector-contrib:0.115.1"))
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("otel-collector-test.yaml"), "/etc/otelcol/config.yaml")
            .withCommand("--config=/etc/otelcol/config.yaml")
            .withExposedPorts(4318)
            .waitingFor(Wait.forListeningPort());

    static {
        COLLECTOR.start();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void otlp(DynamicPropertyRegistry registry) {
        String base = "http://" + COLLECTOR.getHost() + ":" + COLLECTOR.getMappedPort(4318);
        // Two different property SHAPES, and this is where an afternoon goes: tracing takes a full
        // signal `endpoint`, the Micrometer metrics registry takes a `url`.
        registry.add("management.tracing.export.otlp.enabled", () -> "true");
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> base + "/v1/traces");
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");
        registry.add("management.otlp.metrics.export.enabled", () -> "true");
        registry.add("management.otlp.metrics.export.url", () -> base + "/v1/metrics");
        registry.add("management.otlp.metrics.export.step", () -> "1s");
        // Boot silences both in tests unless these are set.
        registry.add("spring.test.tracing.export", () -> "true");
        registry.add("spring.test.metrics.export", () -> "true");
        // application.properties pins 9090; a random port keeps this from colliding with any other
        // test's management listener.
        registry.add("management.server.port", () -> "0");
    }

    @Test
    void aRealDepositReachesTheCollectorAsBothASpanAndAMetric() {
        RestClient http = RestClient.create();
        String created = http.post()
                .uri("http://127.0.0.1:" + port + "/api/v1/accounts")
                .header("Content-Type", "application/json")
                .body("{\"name\":\"otlp\",\"currency\":\"GBP\"}")
                .retrieve()
                .body(String.class);
        String account = created.replaceAll(".*\"accountUid\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");

        http.put()
                .uri("http://127.0.0.1:" + port + "/api/v1/accounts/" + account + "/deposits/" + UUID.randomUUID())
                .header("Content-Type", "application/json")
                .body("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":2500}}")
                .retrieve()
                .toBodilessEntity();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            String received = collectorOutput();
            assertThat(received)
                    .as("the use-case span must reach the Collector over OTLP")
                    .contains("ledger.record-movement");
            assertThat(received)
                    .as("...carrying its domain attribute")
                    .contains("ledger.account_id");
            assertThat(received)
                    .as("...and the meter must arrive on the same pipeline")
                    .contains("ledger.movements");
            assertThat(received)
                    .as("...under the resource identity part 1 declared (§6.6, ADR 0005)")
                    .contains("service.namespace");
        });
    }

    private static String collectorOutput() {
        return COLLECTOR.copyFileFromContainer(OUTPUT, in -> new String(in.readAllBytes()));
    }
}
```

**CORRECTED DURING EXECUTION — the file exporter above is not what shipped.** It cost two CI rounds,
neither of them about telemetry, and both are now recorded in `src/test/resources/otel-collector-test.yaml`:

1. The contrib image is **distroless and has no `/tmp`**, so the file exporter exited at startup —
   while `Wait.forListeningPort()` passed, because Docker had published the mapping against a process
   that had already given up. That wait strategy is now `Wait.forLogMessage("Everything is ready")`,
   which requires the config at log level `info`.
2. With a tmpfs at `/tmp` the Collector started and received everything, and then **`docker cp` could
   not read back through the tmpfs mount**.

What shipped is the **debug exporter at `verbosity: detailed`**, asserted against `COLLECTOR.getLogs()`.
No filesystem, no mount, no copy — and §14's gate asks only that a Collector receive OTLP. The image
tag also changed: `0.158.0`, the current release, after checking that the plan's guessed `0.115.1` was
a real but two-year-old tag.

**The process lesson, which is the one worth carrying:** both rounds would have been caught by one
`docker run` against the config file before pushing. A container config is code, and it was the only
piece of this work not exercised locally first.

- [ ] **Step 3: Two things to verify on the first CI run, before trusting a green**

1. **The image tag.** `otel/opentelemetry-collector-contrib:0.115.1` is written as a pinned version,
   for the reason every other version in this repository is pinned. **Confirm the tag exists**
   before pushing (`docker manifest inspect otel/opentelemetry-collector-contrib:0.115.1`); if it
   does not, pick a real released tag and correct this plan in the same commit.
2. **`copyFileFromContainer` on a file the Collector is still writing.** If it returns a truncated
   last line, the assertions above are `contains` and unaffected — but if it fails because the file
   does not exist yet, the Awaitility block must catch that and retry rather than erroring out. Wrap
   `collectorOutput()` in a `try/catch` returning `""` if the first CI run shows this.

- [ ] **Step 4: Red proof — the test must fail with export off**

On a throwaway branch, flip the two `registry.add(..., "true")` export lines to `"false"`:

```
must redden: OtlpExportIT#aRealDepositReachesTheCollectorAsBothASpanAndAMetric
             on "the use-case span must reach the Collector over OTLP"
```

Confirm from `target/failsafe-reports/TEST-*OtlpExportIT.xml` that the class ran. A
`ConditionTimeoutException` naming that assertion is the correct red; a container-startup error is
not, and means the proof has to be redone.

- [ ] **Step 5: Push, read CI, commit**

```bash
./mvnw verify > /tmp/t8.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t8.log
```

`verify` must still report **0** containers — `OtlpExportIT` is an `*IT` and Surefire's default
includes skip it. This is the differential check that matters most in this task, because it is the
first task to add a container to the tree at all.

```bash
git add src/test/resources/otel-collector-test.yaml \
        src/test/java/com/ffroliva/tinyledger/observability/OtlpExportIT.java
git commit -m "test: a Collector container receives real spans and metrics over OTLP

§14 step 9's second done-when half, and the nearest a build can get to 'the
dashboard works' — no build can assert a hosted backend, which is why §14's
original wording was withdrawn at v3.40.

standalone and ONE container: nothing here needs Postgres, Redis, Kafka or
Keycloak. A separate profile context, in the same category as
CucumberSpringConfig — it does not fork the shared full context, so ADR 0003's
forking conditions never arise. §9.4 said this test forks full deliberately and
is 'the only fork in the suite'; both sentences predate the test and are
corrected at v3.41.

Two defaults that would have made it lie, both overridden: Boot silences
telemetry export in tests, and the OTLP metrics step interval is sixty seconds.
The output file is read through the Docker API — the contrib image ships no
shell, so execInContainer would fail as something that looks like a Collector
problem.

CI holds no Grafana credential and gains no third party: the gate is a
Collector receiving OTLP, which a file exporter satisfies."
```

---

## Task 9: Part 3 — the opt-in Collector service

**Files:**
- Create: `docker/otel-collector.yaml`
- Modify: `docker/docker-compose.yml`
- Modify: `README.md`

- [ ] **Step 1: The Collector configuration**

Create `docker/otel-collector.yaml`:

```yaml
# The opt-in Collector (spec §6.6, ADR 0005). ONE container, off unless the `observability` profile is
# started, forwarding to hosted Grafana Cloud. There is deliberately NO local
# Prometheus/Grafana/Tempo/Loki stack: five containers most runs never open is a cost paid on every
# `up` for a benefit taken occasionally — the same posture the `load` profile already takes.
#
# NO GATE COVERS THIS FILE. Nothing in CI starts it, and OtlpExportIT uses its own config
# (src/test/resources/otel-collector-test.yaml) so it can assert against a file instead of a hosted
# backend. This one is proven by being run by hand, and that is stated rather than implied.
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
      grpc:
        endpoint: 0.0.0.0:4317

processors:
  # §6.6: "parent-based, 100% in standalone and CI, tail-sampled in full". The application samples
  # everything, because only this processor can see how a trace ENDED. A head sampler discards errors
  # at exactly the same rate as successes, which is backwards for a ledger — and keeping 100% of a
  # successful-12ms-deposit stream fills a quota with the least interesting traffic there is.
  tail_sampling:
    decision_wait: 10s
    policies:
      - name: keep-errors
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: keep-slow
        type: latency
        latency: { threshold_ms: 150 }
      - name: sample-the-rest
        type: probabilistic
        probabilistic: { sampling_percentage: 5 }
  batch:
    timeout: 5s

exporters:
  otlphttp/grafana:
    # Base form: the exporter appends /v1/traces and /v1/metrics itself. The endpoint is not secret;
    # the headers value is, because it embeds the access-policy token as Basic
    # base64(instanceID:token). Both come from the environment — .env.grafana, gitignored, never a
    # committed value (AGENTS.md, "Configuration": a .env configures your shell, not the application).
    endpoint: ${env:OTEL_EXPORTER_OTLP_ENDPOINT}
    headers:
      Authorization: ${env:OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION}

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [tail_sampling, batch]
      exporters: [otlphttp/grafana]
    metrics:
      receivers: [otlp]
      # No tail sampling on metrics — sampling a counter does not thin it, it corrupts it.
      processors: [batch]
      exporters: [otlphttp/grafana]
```

- [ ] **Step 2: The Compose service**

Add to `docker/docker-compose.yml`, before the `volumes:` block:

```yaml
  # §6.6 / §14 step 9 part 3. OFF by default: `profiles` means a plain `docker compose up` still
  # starts exactly four containers and this one is not among them. Turn it on with
  #     set -a; . ./.env.grafana; set +a
  #     docker compose -f docker/docker-compose.yml --profile observability up -d
  # and point the application at it with
  #     -Dspring-boot.run.arguments="--management.tracing.export.otlp.enabled=true \
  #        --management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces \
  #        --management.otlp.metrics.export.enabled=true \
  #        --management.otlp.metrics.export.url=http://localhost:4318/v1/metrics"
  #
  # The variables are read from the SHELL, exactly as .mcp.json's are — nothing here loads a dotenv
  # file. A missing variable makes the Collector fail to start, which is the honest failure: a
  # Collector that starts and silently drops everything is worse.
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.115.1
    profiles: [observability]
    command: ["--config=/etc/otelcol/config.yaml"]
    volumes:
      - ./otel-collector.yaml:/etc/otelcol/config.yaml:ro
    environment:
      OTEL_EXPORTER_OTLP_ENDPOINT: ${OTEL_EXPORTER_OTLP_ENDPOINT}
      OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION: ${OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION}
    ports:
      - "4317:4317"
      - "4318:4318"
```

- [ ] **Step 3: Fix `.env.example` to match**

`.env.example` currently carries `OTEL_EXPORTER_OTLP_HEADERS`, which is the SDK's comma-separated
form. The Collector config above reads a single `Authorization` value. Rename the entry and keep the
explanation, so the file and the Compose service agree:

```
# The Collector reads a single Authorization header value, not the SDK's comma-separated
# OTEL_EXPORTER_OTLP_HEADERS form — docker/otel-collector.yaml names this variable directly.
OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION=
```

- [ ] **Step 4: Prove it by running it — the only proof there is**

```bash
set -a; . ./.env.grafana; set +a
docker compose -f docker/docker-compose.yml --profile observability up -d otel-collector
docker compose -f docker/docker-compose.yml logs otel-collector | tail -20
```

Expected: no `Error` lines, and `Everything is ready` in the log. Then confirm the default is
unchanged — this is the assertion the `profiles` key exists for:

```bash
docker compose -f docker/docker-compose.yml config --services | wc -l          # 5 (all, incl. profiles)
docker compose -f docker/docker-compose.yml config --services --profiles ""    # must NOT list otel-collector
```

Then send a real trace through it and read it back from Grafana Cloud with the MCP server. **Do not
paste the token, the response headers, or any credential into the chat or into a commit.**

```bash
docker compose -f docker/docker-compose.yml --profile observability down
```

- [ ] **Step 5: README**

Add a short section under the existing run-mode instructions: what the profile is, the two commands
above, that the credential lives in `.env.grafana` and never in the repository, and that **nothing in
CI runs this**.

- [ ] **Step 6: Commit**

```bash
./mvnw verify > /tmp/t9.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t9.log
git add docker/otel-collector.yaml docker/docker-compose.yml .env.example README.md
git commit -m "feat: one opt-in Collector, behind a Compose profile

Spec §6.6 / ADR 0005: a single container forwarding to hosted Grafana Cloud,
never a local Prometheus/Grafana/Tempo/Loki stack. \`profiles: [observability]\`
means a plain \`docker compose up\` still starts exactly four containers.

It tail-samples, which is what makes §6.6's 'tail-sampled in full' true rather
than aspirational: the application samples 100% because only the Collector can
see how a trace ended, and a head sampler discards errors at the same rate as
successes. Metrics are not sampled — sampling a counter does not thin it, it
corrupts it.

NO GATE COVERS THIS FILE. Nothing in CI starts it and OtlpExportIT uses its own
config so it can assert against a file instead of a hosted backend. Proven by
being run by hand, and the documentation says so instead of implying otherwise.

.env.example's OTEL_EXPORTER_OTLP_HEADERS is renamed to
OTEL_EXPORTER_OTLP_HEADERS_AUTHORIZATION: the Collector takes a single header
value, not the SDK's comma-separated form, and the two files now agree."
```

---

## Task 10: The record — spec v3.41, and only then §14 green

**Do not start this task until both halves of the done-when are green on CI**: `ObservabilityIT`
passing and `OtlpExportIT` passing, both read from the failsafe XML paired with the run's conclusion.

**Files:**
- Modify: `docs/spec.md` §6.6, §9.4, §14, revision history (**two** version sites — `grep -n 'at v3\.' docs/spec.md`)
- Modify: `CHANGELOG.md`, `docs/INDEX.md`, `AGENTS.md` (only if it cites a spec version)

- [ ] **Step 1: §6.6 — five edits**

1. **Add the span data-classification rule** as a new paragraph after the Logs row's gap sentence, and
   change that sentence from "A data-classification rule covering spans is owed (§14 step 9 part 2)"
   to a statement that it is now stated below. The rule: span attributes are limited to the
   enumerated `ledger.*` set plus OTel semantic conventions; no name, email, `owner`, token, amount or
   balance; the identifiers that are there are opaque server-generated UUIDs, present because
   correlation is why spans exist; they are still personal data when linkable, which is why the
   backend is Grafana Cloud's **UK** region — telemetry carrying `ledger.account_id` stays
   in-country; **and no gate enforces any of it.**
2. **Narrow the Spans row**: there is no separate event-append span. `MovementResult` carries
   `version`, `type` and `rejectionReason`, so the append attributes live on the use-case span. Name
   the four spans that do exist: HTTP (auto), `ledger.record-movement`, `ledger.projection.apply`,
   `ledger.audit.record`.
3. **Correct the resource-attribute paragraph** — `management.opentelemetry.resource-attributes.*`,
   not `management.observations.key-values.*`, with Task 2's cardinality reasoning.
4. **Close the two gaps**: `ledger.audit.dead_lettered` exists; consumer lag is either delivered with
   its meter name or recorded as not delivered with Task 7 step 2's measurement as the reason.
5. **Sampling**: 100% at the application, tail-sampled at the Collector, and **no gate covers the
   Collector config**.

**Exemplars stay exactly as they are** — specified-and-not-delivered — unless Task 1 showed that
Boot 4.1's `management.tracing.exemplars.include` reaches the OTLP registry. If it does, §6.6's
claim that "there is no flag that turns them on along that path" is **false** and must be corrected
the same day, with a revision-history row. Nothing is built for exemplars either way.

- [ ] **Step 2: §9.4 — correct the Collector-test paragraph**

The current text says the test *"forks the Spring context deliberately… and this is the only fork in
the suite"*. Replace with: it runs `standalone` and starts one container; it is a separate profile
context in the same category as `CucumberSpringConfig` and `LedgerEventsListenerTest`, so it does not
fork the shared `full` context and ADR 0003's forking conditions do not arise; the observability
assertions that *do* run on the `full` context reach it through an `@Import` on the shared base and a
property on the existing `@DynamicPropertySource`, which is what ADR 0003 §1 prescribes.

- [ ] **Step 3: §14 step 9 — PARTIAL to done**

Replace the row's body with what was built and the evidence for each half, citing the CI run id for
both `ObservabilityIT` and `OtlpExportIT`. Keep the two withdrawn wordings visible — a reader must be
able to see that "dashboards render live traffic" and "readiness gates on projection lag" were
retired for stated reasons, not quietly dropped.

- [ ] **Step 4: Revision history**

One row, `3.41`, covering: parts 2 and 3 delivered; the part-1 resource-attribute defect corrected
(observation key-values → resource attributes, and the unbounded `service.instance.id` meter tag);
§9.4's fork claim corrected; JSON logs narrowed to `full`; the span data-classification rule stated;
consumer lag and the DLT closed (or the lag row's honest status).

**`grep -n 'at v3\.' docs/spec.md` before committing — the version appears in TWO places.**

- [ ] **Step 5: `CHANGELOG.md`, `docs/INDEX.md`, `AGENTS.md`**

`docs/INDEX.md` carries the spec version and must move. `AGENTS.md` has been stale on the version
before; check it. `CHANGELOG.md` gets one entry for step 9 parts 2 and 3.

- [ ] **Step 6: Final gate, push, and stop**

```bash
./mvnw verify > /tmp/t10.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t10.log
git add docs/spec.md CHANGELOG.md docs/INDEX.md AGENTS.md
git commit -m "docs: spec v3.41 — step 9 parts 2 and 3 delivered, and four claims corrected"
git push
gh pr ready 15
gh run watch
```

**Then stop and ask.** `main` is protected and merging is the user's decision, never the agent's.

---

## Self-review

**Spec coverage.** Every section of the design maps to a task: dependencies → 1; resource attributes
(found during planning, added) → 2; use-case decorator and `ledger.movements` → 3; Kafka produce and
the linked consume span → 4; projection span, `InMemorySpanExporter` and §9.4's three assertions → 5;
JSON logs → 6; the two blind spots → 7; the Collector test → 8; the Compose Collector and tail
sampling → 9; every document → 10. The span data-classification rule is a documentation deliverable
and lands in Task 10 step 1, cross-referenced from `TracedUseCases`' javadoc in Task 3.

**Known soft spots, stated rather than hidden.** Three steps ask the executor to *measure and then
decide*, because guessing would be worse than looking:

- Task 5 step 3's HTTP payloads must be copied from `SecurityConfigIT` rather than trusted from the
  sketch — the exact JSON shapes are not reproduced here and a wrong body fails as a 400, which reads
  like a tracing defect.
- Task 7 step 2's consumer-lag row genuinely depends on whether Boot binds `KafkaClientMetrics` on
  this classpath. Both branches are written out; neither is "TODO".
- Task 8 step 3's Collector image tag must be confirmed to exist before the first push.

**Two things every task inherits and none may skip:** `./mvnw -q verify` green with **zero**
containers before each commit, and a red proof whose XML shows the named test ran and reddened on its
assertion rather than on a startup error or a hang.
