# ADR 0003 — Test context topology and CI parallelisation

**Status:** Accepted — one shared context per profile; CI split by infrastructure need, not by test count
**Date:** 2026-08-05
**Context:** spec §9 (verification), CR13, Plan 3 planning

> **Numbering note.** `docs/adr/` contains only `0001`. "ADR-002" is referenced by `docs/spec.md:337`,
> `:423` and `docs/agentic-workflow.md:218` as the Postgres-event-store decision, but that file was
> never written. This ADR takes **0003** rather than silently occupying a number the spec already
> points elsewhere. Writing the missing 0002 is a backlog item.

## Context

`@SpringBootTest` caches contexts keyed by the *merged* configuration — the classes, the active
profiles, `@Import`s, `@TestPropertySource`, `@MockitoBean` declarations and the `@DynamicPropertySource`
contributions. Change any of them and you get a **different key**, which means a whole new
`ApplicationContext`: fresh bean graph, fresh auto-configuration, and — in this repository — a fresh set
of Kafka consumers and connection pools.

That cost is not theoretical here. Today the suite holds **four** contexts:

| Context | Keyed by |
|---|---|
| `AbstractIntegrationTest` | `full` + its own `@DynamicPropertySource` — shared by 4 IT classes |
| `PostgresEventStoreIT` | the *same* annotations but a **second** `@DynamicPropertySource` → a separate context |
| `CucumberSpringConfig` | `RANDOM_PORT`, default profile |
| `LedgerEventsListenerTest` | `standalone` |

The second row is the cautionary one. `PostgresEventStoreIT` forks a second `full` context **only because
Java has single inheritance** — it already extends `EventStoreContract`, so it cannot extend
`AbstractIntegrationTest` and re-declares the property source instead. **CR13** — a second
`AuditKafkaListener` joining the `tiny-ledger-audit` group and taking partitions from
`KafkaAuditModuleIT` — was that fork's symptom. The fix applied (`spring.kafka.listener.auto-startup=false`)
treats the symptom. The cause is the extra context.

## Decision

### 1. One context per profile. Subclasses derive; forks are exceptional and justified in writing.

`AbstractIntegrationTest` owns the whole `full` stack — containers, properties, and (from Plan 3) the
Keycloak `issuer-uri`, supplied as a **property through the existing `@DynamicPropertySource`, never
via `@Import`**, because an `@Import` on a subclass forks the context by definition.

Any test that genuinely needs a modified context must (a) say why in a comment, and (b) set
`spring.kafka.listener.auto-startup=false`, or it silently re-enters CR13.

`EventStoreContract` should become an **interface with default methods** so `PostgresEventStoreIT` can
extend the shared base. That collapses two contexts into one and makes CR13's workaround unnecessary
rather than load-bearing.

### 2. CI parallelises across isolated runners, and the split follows infrastructure, not test count.

The practical CI model is multiple VMs, each fully isolated, each running a shard. **CI is billed by the
minute, summed over every runner** — so sharding does not buy throughput for free:

```
cost  ≈ Σ over shards of (fixed overhead + execution time)
wall  ≈ max over shards of (fixed overhead + execution time)
```

Sharding buys **wall-clock** and pays for it in **duplicated fixed overhead**: checkout, JDK setup,
Maven resolution, OpenAPI generation, compilation, image pull, container start, and context start. A
shard is only worth adding when the execution time it removes exceeds the fixed overhead it adds.

For this repository the dominant fixed cost is starting Postgres, Redis and Kafka. So the first and
largest saving is not sharding at all — it is **not paying that cost where it is not needed**:

- **`./mvnw verify` starts ZERO containers**, by design, and an existing gate asserts it. That job needs
  no Docker and can run on a plain runner.
- **`./mvnw verify -Pit`** is the only job that needs the stack.

These two run **in parallel on separate runners**. Splitting the integration job further is a judgement
call against the arithmetic above, not a default.

### 3. Cheap gates fail fast, before expensive minutes are spent.

`spotless:check` and the docs-governance script take seconds. They gate the expensive jobs, so a
formatting slip never burns container minutes.

### 4. If integration tests are ever sharded, build once and test many.

Compile and generate sources in one job, publish the artifact, and have the shards consume it. Otherwise
every shard re-pays code generation and compilation — often more than the test time it saved.

### 5. Context proliferation is a CI cost, multiplied.

Every extra context is paid **per runner that touches it**. Under sharding, a stray `@Import` on a test
class is not a local annoyance; it is a recurring line item on every shard that runs that class. This is
why decision 1 is a cost decision as much as a hygiene one, and why the two decisions in this ADR are one
ADR rather than two.

### 6. Targeted local runs; CI remains the backstop.

`-Dit.test=ClassName` under `-Pit` runs a single integration class against the shared containers for the
developer loop. That is a local convenience only — **CI runs everything**, so a targeted local run is
never a claim about the suite.

## Consequences

> **Superseded 2026-08-06 — the paragraph below is the state at the time of writing, not now.** The
> two-job split landed: `.github/workflows/ci.yml` declares an `integration` job that runs
> `./mvnw -q verify -Pit` on every push (`:55-67`) with a zero-test-count guard (`:69-75`). Its test
> count is whatever that run's own failsafe XML reports beside its exit code; the "26" below was never
> paired with one (AGENTS trap 3) and is not a current figure.

**A gap this ADR exposes, and which invalidates the "CI will catch it" assumption:** `.github/workflows/ci.yml`
currently runs `spotless:check`, `./mvnw -q verify` and the docs-governance script — and **never runs
`-Pit` at all**. The 26 integration tests, the entire `full` stack, are gated by nothing. Until the
two-job split above exists, "CI is the gate" is false, and any reasoning that relies on it is unsound.
Fixing this is the first task of the Plan 3 CI work.

**Testcontainers reuse does not help in CI.** `testcontainers.reuse.enable` relies on a container
surviving between runs; an ephemeral runner has no previous run. Image **pull** time can be cut with a
layer cache or a pre-pulled image; start time cannot.

**The two strategies trade off.** Sharding integration tests across VMs destroys the shared-container and
shared-context saving *within* each shard. The right shape is few shards, each with a warm shared
context, split along infrastructure lines — not many shards split by test count.
