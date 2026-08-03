# Tiny Ledger — Technical Specification

**Author:** Flávio Oliva
**Version:** 3.0
**Status:** Contract for implementation
**Supersedes:** Event-Sourced Banking Ledger PoC V2

---

## 1. Purpose and dual delivery

An event-sourced, double-entry-capable banking ledger, built as a modular monolith and delivered
production-ready: containerised, observable, secured, rate-limited, cached, and tested at every
level from unit to load.

The origin is Teya's *"Build a tiny ledger"* take-home. That brief asks for three features in a few
hours with in-memory storage and explicitly excludes auth, monitoring and atomic operations. This
specification deliberately goes far beyond it, so the repository ships **two run modes from one
codebase**:

| Mode | Command | What runs | Purpose |
|---|---|---|---|
| **`standalone`** (default) | `./mvnw spring-boot:run` | In-memory event store, in-memory cache, no auth, no broker. **JDK 25** is the only prerequisite. | Satisfies the take-home brief exactly: clone, one command, curl the APIs. |
| **`full`** | `docker compose up` | PostgreSQL, Kafka, Redis, Keycloak, OTel Collector, Prometheus, Grafana. | The production-shaped system. |

Both modes run the **same domain code and the same API**. The difference is which adapter
implementations are active — which is the point of the hexagonal boundaries in §4. The default mode
is the submittable artefact; the full mode is the depth story for the follow-up conversation.

**Design rule that follows from this:** no infrastructure concern may leak into the domain. If a
domain class imports Kafka, Redis, JPA or Spring Security, the design has failed and the
architecture tests in §9.2 fail the build.

---

## 1.5 Stack and conventions

Versions are governed by **`dr-jskill`'s `versions.json`**, not chosen ad hoc.

| Component | Version | Note |
|---|---|---|
| Java | **25** (LTS, Corretto) | Records, sealed interfaces, pattern matching and virtual threads are all load-bearing below |
| Spring Boot | **4.1.0** (Spring Framework 7.0) | `ProblemDetail`, Modulith integration and `@ServiceConnection` come free |
| Spring Modulith | Boot-4 line | Module verification, event publication registry, `@Externalized` (§4.3) |
| PostgreSQL | **18** | Event store + projections |
| Hibernate | **7.4** | Outbound persistence adapter only |
| Testcontainers | **2.0.5** | Integration and e2e |
| Maven wrapper | 3.8+ | `./mvnw` — a JDK is the only prerequisite |

**Jackson 3** ships with Boot 4; annotation imports differ from Jackson 2 and the DTO layer must be
written against it from the start rather than migrated.

### Conventions adopted from `dr-jskill`

`dr-jskill` (jdubois, Apache-2.0) is vendored at `.claude/skills/dr-jskill`. It is the JHipster
author's opinionated Spring Boot baseline; adopting it means the boring decisions are already made
and internally consistent.

**Adopted as-is:**

- **Bump `versions.json` first**, then propagate. Never edit a version directly in `pom.xml`.
- **`.properties`, not YAML.** `@ConfigurationProperties` for type safety.
- **`.env` is the single local secret store — never read, never printed.** Only `.env.sample`, with
  placeholder values, is displayed or committed.
- **A startup banner printing access URLs** once the app is ready.
- Testing: Mockito + `@WebMvcTest` for unit, Testcontainers with **`@ServiceConnection`** for
  integration, Given-When-Then with AssertJ.
- Docker asset set: standard, AOT, native and CRaC variants; devcontainer; `.editorconfig`;
  `.gitattributes`.
- **Ask before running git commands.** The human reviews before anything is committed.

**Deliberately overridden — the package layout (ADR-003).** `dr-jskill` mandates a flat, layer-first
structure — `config/`, `controller/`, `service/`, `repository/`, `domain/` — with the explicit rule
that *"the service layer is only included if it adds value… for simple CRUD applications, the
controller can directly call the repository."*

That rule is right, and it identifies precisely why it does not apply here. This is not a CRUD
application: there is no repository for a controller to call, because the write model is an event
stream behind `EventStorePort` and the read model is a projection owned by a different module. A flat
`controller/service/repository` layout cannot express a Modulith boundary, and collapsing the service
layer would put orchestration into a controller, which §4 forbids.

So the layout is §3.1's — module-first, hexagonal layers within. Everything else from `dr-jskill`
stands. This is recorded as an ADR because departing from an adopted convention must never look like
an accident.

---

## 2. Domain

### 2.1 Ubiquitous language

| Term | Meaning |
|---|---|
| **Account** | The aggregate root. Owns a stream of ledger events and enforces every monetary invariant. |
| **Money** | Value object: signed amount in **minor units** (`long`) plus `Currency`. Never a `double`. |
| **Movement** | A single recorded change of funds — a deposit or a withdrawal. |
| **LedgerEntry** | The immutable, persisted record of a movement, carrying the resulting balance. |
| **Balance** | A *projection* of the event stream. Never an independently writable field. |
| **Stream version** | Monotonic sequence number per account; the optimistic-concurrency token. |

### 2.2 Aggregate: `Account`

Invariants, enforced inside the aggregate and nowhere else:

1. A withdrawal may not take the balance below zero (no overdraft in this PoC).
2. Movement amounts are strictly positive and in the account's currency.
3. Every applied event increments `version` by exactly one.
4. Balance is recomputed only by applying events, never assigned.

### 2.3 Domain events

Immutable Java `record`s, versioned by schema:

| Event | Emitted when |
|---|---|
| `AccountOpened` | Account created with an initial currency. |
| `MoneyDeposited` | Funds credited. |
| `MoneyWithdrawn` | Funds debited after invariant checks pass. |
| `MovementRejected` | A command failed a business invariant. Recorded, not thrown away — rejections are audit-relevant. |

Events are the write model's source of truth. Nothing else is.

### 2.4 Commands

`OpenAccount`, `Deposit`, `Withdraw`. Each carries an **idempotency key**; see §6.3.

---

## 3. Module structure (Spring Modulith)

One deployable, one Maven module, package-per-application-module under
`com.flaviooliva.ledger`. Boundaries are verified at build time by
`ApplicationModules.of(LedgerApplication.class).verify()`.

**Four application modules, one open kernel, one non-module platform package.**

An earlier draft of this spec listed seven modules, including `security`, `ratelimit` and
`observability`. That was wrong. A Spring Modulith application module is a **business capability**;
those three are technical aspects applied uniformly to every module, and promoting them to modules
inflates the count while saying nothing about the domain. They now live in `platform`, which is
excluded from the module graph.

| # | Module | Type | Responsibility | May depend on |
|---|---|---|---|---|
| — | `shared` | **open kernel** | `Money`, `AccountId`, `Currency`. Value semantics only, no behaviour, no growth. | — |
| 1 | `ledger` | closed | **Write side.** The `Account` aggregate, commands, domain events, event store port, command use cases, write API. | `shared` |
| 2 | `balance` | closed | **Read side.** Balance and history projections, their own query API. Subscribes to `ledger` events. | `shared`, `ledger::events` |
| 3 | `audit` | closed | Compliance trail, retention, auditor-facing API. Subscribes to all events. | `shared`, `ledger::events` |
| 4 | `notification` | closed | Outbound signalling on threshold and rejection events. Optional; present to prove a third subscriber costs nothing. | `shared`, `ledger::events` |
| — | `platform` | not a module | Security, rate limiting, observability, composition root. `@Configuration` and filters only — no domain logic. | — |

**Why `account` is not a fifth module.** Account lifecycle and money movement look like separate
capabilities, but the `Account` *is* the aggregate that owns the event stream — splitting them puts a
consistency boundary through the middle of a single transactional invariant. Opening an account emits
`AccountOpened` onto the same stream as every movement. One aggregate, one module.

**Why `balance` is separate from `ledger`.** This is the CQRS boundary made structural — see §4.0.
The read side subscribes to events and serves its own queries; it never calls into the write side,
and the write side does not know it exists. Deleting `balance` would break reads and leave writes
working, which is the test of whether the split is real.

`shared` is an **open** module so value objects can cross boundaries without ceremony. It is capped
at value types deliberately: a shared kernel that accumulates behaviour becomes the coupling it was
meant to prevent, and an ArchUnit rule keeps services and repositories out of it.

Cross-module communication is **domain events only** — no direct service calls between closed
modules. Each module exposes a `package-info.java` declaring its named interface; internals live in
sub-packages that Modulith enforces as private.

### 3.1 Reconciling Modulith with hexagonal layering

Spring Modulith organises **feature-first** (a package per bounded context). Hexagonal architecture
organises **layer-first** (domain / application / adapters). These are orthogonal, and the
combination is: *module at the top level, layers inside each module.*

```
com.flaviooliva.ledger
├── shared/                              ← open module
├── ledger/                              ← closed module
│   ├── package-info.java                ← @ApplicationModule, allowedDependencies
│   ├── domain/                          ← zero framework imports. Enforced by ArchUnit.
│   │   ├── Account.java                 ← aggregate root
│   │   ├── LedgerEvent.java             ← sealed interface + record variants
│   │   └── policy/OverdraftPolicy.java
│   ├── application/
│   │   ├── port/in/                     ← inbound ports (use-case contracts)
│   │   │   ├── OpenAccountUseCase.java
│   │   │   ├── RecordMovementUseCase.java
│   │   │   └── QueryBalanceUseCase.java
│   │   ├── port/out/                    ← outbound ports (capabilities the app needs)
│   │   │   ├── EventStorePort.java
│   │   │   ├── EventPublisherPort.java
│   │   │   ├── BalanceCachePort.java
│   │   │   ├── IdempotencyStorePort.java
│   │   │   ├── ClockPort.java
│   │   │   └── IdGeneratorPort.java
│   │   └── usecase/                     ← plain classes; orchestration only
│   │       ├── RecordMovementService.java
│   │       └── OpenAccountService.java
│   └── adapter/
│       ├── in/web/LedgerController.java     ← implements the generated OpenAPI interface
│       └── out/
│           ├── inmemory/InMemoryEventStore.java
│           ├── postgres/PostgresEventStore.java
│           ├── kafka/KafkaEventPublisher.java
│           └── redis/RedisBalanceCache.java
└── config/                              ← composition root (§4.5)
```

**Ports model capabilities, not technologies.** `EventStorePort` exposes
`append(streamId, expectedVersion, events)` and `read(streamId)` — nothing in that signature reveals
whether the implementation is a `ConcurrentHashMap` or Postgres, which is precisely what makes §1's
two run modes possible.

`ClockPort` and `IdGeneratorPort` exist because an event-sourced aggregate stamps every event with a
timestamp and an identity. If those come from `Instant.now()` and `UUID.randomUUID()` inside the
domain, the domain is no longer deterministic and its tests must either sleep or assert loosely.
Injected, event application is a pure function and the unit tests in §9.1 assert exact values.

---

## 4. Architecture: hexagonal, event-sourced, CQRS

### 4.0 Where CQRS sits relative to ports and adapters

**Question asked during design:** should CQRS go *in front of* the ports and adapters?

**Answer: no — CQRS is not a layer, it is a split, and it is expressed *as* the port structure.**
The two patterns act on different axes and compose rather than stack:

| | Axis | Question it answers |
|---|---|---|
| **Hexagonal** | inside ↔ outside | What may depend on what? |
| **CQRS** | write ↔ read | Which model serves this request? |

Putting a command bus or mediator in front of the adapters — a `CommandDispatcher` that resolves
handlers from a registry — is the usual way this goes wrong. It buys a runtime lookup in exchange for
compile-time wiring, readable stack traces, and IDE navigation. In a modular monolith with a
composition root (§4.5), there is nothing left for it to do: dispatch is a constructor argument.

CQRS instead appears in three places, all structural:

1. **Two families of inbound ports.** `RecordMovementUseCase` and `OpenAccountUseCase` are commands;
   `QueryBalanceUseCase` and `QueryHistoryUseCase` are queries. Different interfaces, different
   transaction semantics, different failure modes.
2. **Two families of outbound ports.** Writes go through `EventStorePort` and its optimistic
   concurrency check. Reads go through `BalanceProjectionPort` and never touch the aggregate — a query
   that rehydrates an aggregate has silently abandoned CQRS.
3. **Two modules.** `ledger` (write) and `balance` (read), coupled only by domain events (§3).

So the layering is: **adapter → inbound port (command *or* query) → use case → domain and outbound
ports.** CQRS chooses *which* path; hexagonal governs the direction of every arrow on both paths.
Neither wraps the other.

The read side owning its own controller follows directly. `GET /v1/accounts/{id}/balance` is served
by an adapter inside `balance`, not by the `ledger` module reaching across a closed boundary.

```mermaid
flowchart TB
  REST["REST controller"] --> IN
  CLI["Python CLI"] --> REST
  GAT["Gatling"] --> REST
  IN["Inbound ports<br/>RecordMovementUseCase, QueryBalanceUseCase"] --> UC
  UC["Use-case services<br/>orchestration only, no I/O"] --> DOM
  UC --> OUT
  DOM["Domain<br/>Account aggregate, Money, LedgerEvent<br/>zero framework imports"]
  OUT["Outbound ports<br/>EventStorePort, EventPublisherPort,<br/>BalanceCachePort, IdempotencyStorePort,<br/>ClockPort, IdGeneratorPort"]
  OUT -.implemented by.-> ADP
  ADP["Outbound adapters<br/>InMemory │ Postgres · InMemory │ Kafka<br/>Map │ Redis · Fixed │ System clock"]
  ADP --> INFRA["Postgres · Kafka · Redis · Keycloak"]
```

Dependency direction is always inward. The domain depends on nothing; use cases depend only on
ports; adapters depend on the application. **No adapter calls another adapter** — the cache is
invalidated by an event handler reacting to a domain event, not by the Postgres adapter reaching
sideways into the Redis adapter.

Errors are translated at the boundary: a `SQLException` or a Redis timeout never escapes an outbound
adapter. It becomes an application-level failure (`EventStoreUnavailable`, `ConcurrencyConflict`)
which the inbound adapter maps to the problem details in §6.5. The domain has no vocabulary for
infrastructure failure and should not acquire one.

### 4.1 Write path

1. Command arrives, validated at the boundary.
2. Idempotency key checked (§6.3).
3. Aggregate rehydrated by replaying its event stream (snapshot every 100 events).
4. Command applied; the aggregate emits events or rejects.
5. Events appended to the store **with an optimistic-concurrency check on stream version**;
   a conflict returns `409` and the caller retries.
6. Events published to subscribers via the transactional outbox (§4.3).

### 4.2 Event store — Postgres, not Kafka

**Decision (ADR-002):** PostgreSQL is the event store and system of record. Kafka is the
publication bus, fed by a transactional outbox.

Kafka is not a system of record for an aggregate that needs per-stream optimistic concurrency: it
offers no conditional append, no read-your-writes on a stream, and unbounded retention is a
compliance liability rather than a feature. Postgres gives an `append(streamId, expectedVersion,
events)` that is atomic with a `UNIQUE (stream_id, version)` constraint — which *is* the concurrency
control. Kafka then does what it is genuinely good at: fanning events out to consumers that must not
share a database.

```sql
CREATE TABLE ledger_event (
    stream_id       UUID        NOT NULL,
    version         BIGINT      NOT NULL,
    event_type      TEXT        NOT NULL,
    schema_version  INT         NOT NULL,
    payload         JSONB       NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    idempotency_key TEXT,
    PRIMARY KEY (stream_id, version)
);
CREATE UNIQUE INDEX ON ledger_event (idempotency_key) WHERE idempotency_key IS NOT NULL;
```

### 4.3 Outbox

**Do we need to hand-write Kafka producers and consumers to move events between Modulith modules?
No — and mostly we do not need Kafka for that at all.** Two distinct mechanisms exist and each has
one job:

| Mechanism | Carries events | Guarantee | Used by |
|---|---|---|---|
| **Spring Modulith event publication registry** — `@ApplicationModuleListener` | *Within* the deployable | Transactional. The publication row commits with the event append; incomplete publications are retried on restart | `balance`, `notification` |
| **Kafka**, via Modulith **`@Externalized`** | *Out of* the deployable | At-least-once, ordered per partition key | `audit` |

Inside one deployable, Kafka between modules is a network hop, a serialisation round-trip and a loss
of transactional coupling, bought in exchange for nothing. `@ApplicationModuleListener` already gives
asynchronous, decoupled, retryable delivery with the module boundary enforced at build time.

**So why is Kafka here at all?** Because it is the seam where a module becomes a service, and
demonstrating that seam is the entire value proposition of a modular monolith. `audit` is therefore
wired through Kafka *deliberately*, as the worked example of the extraction path: different retention,
different scaling profile, compliance isolation, and no shared database — the four reasons an audit
trail is the realistic first module to leave the monolith.

**No hand-rolled outbox poller.** Spring Modulith's event externalisation does exactly this job:

```java
@Externalized("ledger.movements::#{#this.accountId()}")
public record MoneyWithdrawn(AccountId accountId, Money amount, long version, Instant at)
        implements LedgerEvent {}
```

The publication registry row and the event append commit in one transaction; the externaliser
publishes afterwards and marks the publication complete. That *is* the transactional outbox, already
written, already tested, keyed by `accountId` so a single account's events stay ordered on one
partition. Writing a bespoke poller here would be re-implementing a supported framework feature —
and getting the incomplete-publication retry subtly wrong.

**Where the Kafka code actually lives.** Under hexagonal rules a Kafka consumer is simply another
*inbound adapter*, no different in kind from the REST controller:

```
audit/
├── application/port/in/RecordAuditEntryUseCase.java
└── adapter/in/kafka/LedgerEventKafkaListener.java   ← @KafkaListener, maps to the use case
```

The listener deserialises, maps the Kafka payload to a use-case input, and calls the port. It holds
no business logic, so the same use case is driven by a unit test with no broker present. The producer
side is `@Externalized` and therefore not code we own at all.

### 4.4 Read path

Projections are updated from events and served from Redis with Postgres as the fallback:

- `GET /balance` → Redis (`ledger:balance:{accountId}`), miss → replay/read projection → cache.
- `GET /transactions` → Postgres projection, keyset-paginated. Not cached; histories grow.

**Consistency:** the write path is strongly consistent (the aggregate is authoritative); read models
are eventually consistent. Every projection response carries `asOf` and `streamVersion` so a client
can detect staleness, and `GET /balance?consistency=strong` bypasses the cache and reads through the
aggregate. This trade-off is documented, not hidden.

### 4.5 Composition root — the mechanism behind the two run modes

Wiring lives in exactly one place, `com.flaviooliva.ledger.config`, as Spring `@Configuration`
classes selected by profile. Nothing else in the codebase constructs an adapter, and no use-case or
domain class carries a Spring stereotype annotation — use cases are plain classes instantiated by the
composition root with constructor injection.

```java
@Configuration
@Profile("standalone")
class StandaloneAdapterConfig {
    @Bean EventStorePort eventStore()          { return new InMemoryEventStore(); }
    @Bean EventPublisherPort publisher(ApplicationEventPublisher p) { return new SpringEventPublisher(p); }
    @Bean BalanceCachePort balanceCache()      { return new MapBalanceCache(); }
    @Bean IdempotencyStorePort idempotency()   { return new InMemoryIdempotencyStore(); }
    @Bean ClockPort clock()                    { return Instant::now; }
    @Bean IdGeneratorPort ids()                { return UUID::randomUUID; }
}

@Configuration
@Profile("full")
class FullAdapterConfig { /* Postgres, Kafka, Redis equivalents */ }

@Configuration
class UseCaseConfig {                                   // profile-independent
    @Bean RecordMovementUseCase recordMovement(
            EventStorePort store, EventPublisherPort publisher,
            IdempotencyStorePort idem, ClockPort clock, IdGeneratorPort ids) {
        return new RecordMovementService(store, publisher, idem, clock, ids);
    }
}
```

**This is the whole trick of §1.** `UseCaseConfig` never changes between modes; only the adapter
configuration does. The take-home-compliant run and the full production stack execute *the same
compiled domain and application code*. If a behaviour differs between modes, an adapter is at fault,
and §9.2b is the test that catches it.

Auditing the wiring is reading two files. That is deliberate: dependency wiring scattered across
sixty `@Component` annotations is a service locator with extra steps, and it is how framework
concerns leak inward without anyone noticing.

---

## 5. Spec-driven design

The contract precedes the code, in three artefacts, all under version control and all enforced:

1. **`docs/api/openapi.yaml`** — OpenAPI 3.1, hand-written first. The build generates request/response
   DTOs and server interfaces from it; controllers implement generated interfaces, so a controller
   that drifts from the contract fails compilation. `springdoc` validates the live app against it.
2. **`src/test/resources/features/*.feature`** — Gherkin. Every functional requirement in §2 and §6
   has a scenario. These are executable specification, not documentation (§9.3).
3. **`docs/adr/*.md`** — Architecture Decision Records. Every non-obvious choice in this document has
   one, with context, decision, consequences and the alternatives rejected.

**Rule:** no endpoint is implemented before its OpenAPI operation and its `.feature` scenario exist.

---

## 6. Cross-cutting requirements

### 6.1 Rate limiting

Token bucket per principal *and* per IP, whichever is more restrictive. Bucket4j backed by Redis
(`lettuce`) so limits are shared across instances; in `standalone` mode it falls back to a local
in-memory bucket.

| Scope | Limit |
|---|---|
| Write endpoints, per principal | 100 / minute, burst 20 |
| Read endpoints, per principal | 1000 / minute |
| Unauthenticated, per IP | 20 / minute |

Exceeding returns `429` with `Retry-After` and a `RateLimitExceeded` problem detail. Limits are
configuration, not constants.

### 6.2 Caching

| Cache | Store | TTL | Invalidation |
|---|---|---|---|
| `balance` | Redis | 60 s | Evicted on `MoneyDeposited` / `MoneyWithdrawn` for that account |
| `account-metadata` | Redis | 10 min | Evicted on account mutation |
| Aggregate snapshots | Postgres | — | Written every 100 events |

Cache is a Spring Cache abstraction (`@Cacheable` / `@CacheEvict`), so `standalone` swaps Redis for
`ConcurrentMapCacheManager` with no code change. **Event-driven eviction, never write-through** —
the cache must never be a second source of truth.

### 6.3 Idempotency

Every write accepts an `Idempotency-Key` header (required in `full` mode). The key is stored with a
unique constraint alongside the event. A replay returns the original response with
`Idempotency-Replayed: true` rather than double-crediting. Keys expire after 24 hours.

### 6.4 Security

Keycloak as OAuth2/OIDC provider; the app is a resource server validating JWTs.

| Role | May |
|---|---|
| `ledger:reader` | Read balance and history for owned accounts |
| `ledger:writer` | Record movements on owned accounts |
| `ledger:auditor` | Read the audit trail across all accounts; no writes |

Method-level `@PreAuthorize` on application services, not only on controllers — authorisation is a
use-case concern. Ownership is checked against the JWT subject.

#### Test users

Provisioned by `docker/keycloak/realm-tiny-ledger.json`, imported on container start. The realm file
is committed; these are fixtures, not credentials — passwords are `dev-only` throughout and the realm
is never deployed anywhere but a laptop and CI.

| User | Roles | Owns | Exists to prove |
|---|---|---|---|
| `alice` | `ledger:writer`, `ledger:reader` | `ACC-001` | The positive path: deposit, withdraw, read own balance and history |
| `bob` | `ledger:writer`, `ledger:reader` | `ACC-002` | A second independent stream — that aggregates are isolated and concurrency is per-account |
| `carol` | `ledger:reader` | `ACC-003` | **403 on write.** A reader may not move money |
| `dave` | `ledger:auditor` | — | Reads the audit trail and raw event streams across all accounts; **403 on every write** |
| `mallory` | `ledger:writer`, `ledger:reader` | `ACC-004` | **403 on cross-account access.** Valid token, correct role, wrong owner — the authorisation bug that role checks alone miss |
| `ledger-cli` | service account, `ledger:writer`, `ledger:reader` | `ACC-900` | Client-credentials flow for the Python CLI and the e2e suite |

`mallory` is the one that earns its place. Role-based checks pass for her on every endpoint; only the
ownership check against the JWT subject stops her reading `ACC-001`. A test suite without a
`mallory` proves authentication and nothing about authorisation.

### 6.5 Error handling

RFC 7807 `ProblemDetail` throughout, via Spring's built-in support.

| Condition | Status | `type` |
|---|---|---|
| Insufficient funds | 422 | `/errors/insufficient-funds` |
| Invalid amount / currency | 400 | `/errors/invalid-amount` |
| Concurrent modification | 409 | `/errors/version-conflict` |
| Rate limit exceeded | 429 | `/errors/rate-limit-exceeded` |
| Unauthorised | 403 | `/errors/forbidden` |

Problem responses carry a `traceId` correlating to the tracing backend. No stack traces, no internal
identifiers, no SQL fragments cross the boundary.

### 6.6 Observability

**OpenTelemetry is the single instrumentation API for all three signals.** Traces, metrics and logs
are emitted via OTLP to an OTel Collector, which fans out to Tempo, Prometheus and Loki. The
application knows only OTLP; swapping a backend is Collector configuration, not a code change.

Instrumentation is Micrometer + Micrometer Tracing with the OTel bridge — the Spring Boot-native
path — rather than the standalone Java agent, so domain spans are written explicitly and stay
reviewable in the source.

#### Trace context across async boundaries — the part that usually breaks

A ledger write is `HTTP → command → event append → async projection → Kafka → audit consumer`. Three
of those arrows cross a thread or process boundary, and on each one an unconfigured setup silently
starts a fresh trace. The result is the classic failure: four disconnected traces and no way to answer
*"which request caused this audit entry?"*

| Boundary | Loses context because | Fix |
|---|---|---|
| `@ApplicationModuleListener` → new thread | Context is thread-local | `ContextPropagatingTaskDecorator` on the Modulith async executor |
| Event publication registry → retry after restart | The original thread is long gone | Trace id persisted on the publication row; the retry emits a span **linked** to the original |
| Producer → Kafka → consumer | Different process | W3C `traceparent` in Kafka headers; Spring Kafka propagates it both ways |

**Fan-out uses span links, not parent-child.** One write produces `balance`, `notification` and
`audit` work concurrently. Modelling those as children of the HTTP span makes the request span appear
to last until the slowest consumer finishes, which misreports latency to every dashboard. Each
consumer starts a new trace **linked** to the producing span — the correct OTel semantic for
asynchronous fan-out, and it keeps `http.server.duration` honest.

#### What is instrumented

| Signal | Content |
|---|---|
| **Spans** | HTTP (auto), use-case execution, event append with `expectedVersion`, projection apply, Kafka produce/consume, Redis, Postgres. Domain attributes on every one: `ledger.account_id`, `ledger.stream_version`, `ledger.movement_type`, `ledger.rejection_reason` |
| **Metrics** | RED per endpoint, USE per resource. Domain: movements/sec by type, **rejection rate by reason**, **concurrency-conflict rate**, **projection lag (seconds)**, cache hit ratio, rate-limit rejections, idempotent-replay count |
| **Logs** | Structured JSON via `structlog`-equivalent (Logstash encoder), no PII, every line carrying `trace_id` and `span_id` |
| **Exemplars** | Prometheus exemplars link a latency histogram bucket directly to a sampled trace — click the p99 spike, land on the request that caused it |

Semantic conventions are the OTel standard ones (`messaging.*`, `db.*`, `http.*`); domain attributes
use a `ledger.*` prefix so they never collide with a future convention.

**Sampling:** parent-based, 100% in `standalone` and CI, tail-sampled in `full` — always keep traces
containing an error, a `409`, a `422` or a duration over p99. Sampling that discards the interesting
traces is the same as no tracing.

**Health:** liveness and readiness are separate. Readiness gates on event-store reachability **and
projection lag under threshold**, so an instance whose read models have fallen behind stops taking
traffic instead of serving stale balances.

**Observability is tested, not assumed** (§9.4): integration tests assert with an
`InMemorySpanExporter` that a withdrawal produces the expected span tree, that `traceparent` survives
the Kafka hop, and that a `MovementRejected` increments the rejection counter with the right reason
tag. Untested instrumentation rots into dashboards full of zeroes.

---

## 7. API

Full contract in `docs/api/openapi.yaml`. Summary:

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/accounts` | Open an account. `201` + `Location`. |
| `POST` | `/api/v1/accounts/{id}/deposits` | `Idempotency-Key` honoured. |
| `POST` | `/api/v1/accounts/{id}/withdrawals` | `422` on insufficient funds. |
| `GET` | `/api/v1/accounts/{id}/balance` | `?consistency=strong` bypasses cache. |
| `GET` | `/api/v1/accounts/{id}/transactions` | Keyset pagination, newest first. |
| `GET` | `/api/v1/accounts/{id}/events` | Raw event stream. `ledger:auditor` only. |

Money is serialised as a string decimal with explicit currency
(`{"amount": "100.00", "currency": "GBP"}`) — never a float, in JSON or anywhere else.

---

## 8. Documentation

**Documentation is a first-class citizen**, which is a claim with four testable consequences. Docs
that are merely *encouraged* rot; the only documentation that survives contact with a deadline is
documentation the build refuses to ship without.

1. **Docs are gated by CI.** Stage 6 of §12.1 fails the build on a governance violation.
2. **Docs are generated wherever generation is possible**, so they cannot drift.
3. **Docs are executable wherever execution is possible** — the README's examples are run by the
   test suite.
4. **Docs have a lifecycle** — an index, an archive, revision history, and an owner.

### 8.1 Structure — Diátaxis

`docs/` is organised by *what the reader is trying to do*, not by what the writer happened to write.

| Quadrant | Directory | Answers |
|---|---|---|
| **Tutorial** — learning | `README.md`, `docs/tutorial/` | "I have never seen this before. Get me to a working ledger." |
| **How-to** — a task | `docs/runbook.md`, `docs/how-to/` | "Projection lag is alerting. What do I do?" |
| **Reference** — facts | `docs/api/openapi.yaml`, `docs/api.md`, `docs/database.md`, `docs/generated/` | "What exactly does this endpoint return?" |
| **Explanation** — why | `docs/spec.md`, `docs/adr/`, `docs/agentic-workflow.md` | "Why Postgres and not Kafka as the event store?" |

The most common documentation failure is a single file trying to be all four. The split is load-bearing.

### 8.2 Generated, not written

Anything derivable from the code is generated by a **test**, so a stale diagram fails the build
rather than misleading a reader.

| Artefact | Generated from | By |
|---|---|---|
| C4 component diagrams, module canvas | The module graph | Spring Modulith `Documenter` |
| API reference + Swagger UI | `openapi.yaml` | springdoc |
| CLI reference | `openapi.yaml` | Pydantic model generation (§11) |
| Traceability matrix rows | `REQ-NNN` tags in tests | governance script |
| Coverage tables | JaCoCo | report merge |
| Dependency inventory / SBOM | The build | CycloneDX |

**Handwritten prose is reserved for what cannot be derived: intent, trade-offs, and rejected
alternatives.** That is exactly what ADRs are, and it is why §5 requires one per non-obvious
decision.

### 8.3 Executable documentation

The README's `curl` examples are extracted and executed by the e2e suite (§9.6). An example that
stops working fails the build.

This closes the single most common documentation lie — a quickstart that no longer runs — and it
costs one test. The same applies to the Gherkin in §9.3: those files are simultaneously the
specification and the acceptance suite, so the specification cannot describe behaviour the system
does not have.

### 8.4 Governance — the ISO 15289 section contract

Every governed document under `docs/` carries seven literal markers, enforced by
`scripts/test_docs_governance.py` from the **`iso-compliance`** skill (§10):

```
**Version:**   ·   ## Table of contents   ·   ## Scope & purpose   ·   ## Glossary & acronyms
## Traceability   ·   ## Open issues / known gaps   ·   ## Revision history
```

`Not applicable — [reason]` under a heading is acceptable. **Omitting the heading is not** — silence
is indistinguishable from an oversight, which is the whole point of the check.

Working papers, routers and registries (`docs/adr/`, `docs/generated/`, `docs/source/`, `INDEX.md`)
are explicitly exempt: ADRs have their own canonical format and should not be forced into a shape
built for operational documents.

### 8.5 Lifecycle

- **`docs/INDEX.md`** routes every document — rebuilt once per release, never hand-maintained
  incrementally.
- **`docs/_archive/`** holds superseded documents, dated. Deleting documentation destroys the record
  of why a decision was made; leaving it live makes it a lie. Archiving is the third option.
- **`CHANGELOG.md`** — Keep a Changelog, **mandatory per change**. Earlier history stays in git and
  is never retro-documented.
- **Versioning** — document version derives from `versions.json`; pre-release strings never appear
  in a governed document.
- **Ownership** — every document names an owner in its metadata block. An unowned document is a
  registered gap, not a document.

### 8.6 Docs travel with the code

A pull request touching `src/**` and touching neither `docs/**` nor `CHANGELOG.md` fails stage 6 with
a prompt rather than a hard block — the escape hatch is a `docs: n/a — <reason>` line in the PR body,
which is recorded and reviewable. The goal is to make skipping documentation a *deliberate, visible*
act rather than the default.

---

## 9. Testing strategy

Seven levels. Every level has a distinct question it answers; none is ceremony.

### 9.1 Unit — JUnit 5 + AssertJ
Domain in isolation, zero Spring context. `Account` invariants, `Money` arithmetic and rounding,
event application. Includes a concurrency test asserting the balance never goes negative under
parallel withdrawals. **Target: 90% line, 85% branch on `domain` packages, enforced by JaCoCo.**

### 9.2 Architecture — ArchUnit + Spring Modulith
- `ApplicationModules.verify()` — no illegal cross-module access.
- Domain packages import no `org.springframework`, `jakarta.persistence`, `kafka` or `redis`.
- Controllers never touch repositories directly.
- No cyclic package dependencies.

Additional rules that follow from §3.1 and §4.5:

- Nothing outside `config` may instantiate a class from an `adapter.out` package.
- No class in `application.usecase` carries a Spring stereotype annotation.
- `adapter.out.*` packages do not depend on each other — adapters never call adapters.
- `domain` does not import `java.time.Instant.now` or `java.util.UUID.randomUUID`; time and identity
  arrive through ports.

A build that violates the architecture fails, so §1's design rule is mechanically enforced.

### 9.2b Port contract tests — the guarantee that both run modes agree

For every outbound port with more than one implementation, a **single abstract contract suite**
defines the port's semantics, and each adapter runs it:

```java
abstract class EventStoreContract {
    abstract EventStorePort subject();

    @Test void appendsAtExpectedVersion() { … }
    @Test void rejectsStaleExpectedVersion() { … }   // optimistic concurrency
    @Test void readsBackInAppendOrder() { … }
    @Test void isIdempotentForARepeatedKey() { … }
    @Test void concurrentAppendsYieldExactlyOneWinner() { … }
}

class InMemoryEventStoreTest extends EventStoreContract { … }
class PostgresEventStoreTest extends EventStoreContract { … }   // Testcontainers
```

Same for `BalanceCachePort` (map vs Redis), `EventPublisherPort` (Spring events vs Kafka) and
`IdempotencyStorePort`.

This is the test that makes the dual delivery in §1 honest rather than a marketing claim. Without it,
"the same code runs in both modes" is an assertion; with it, the in-memory store is held to the same
concurrency semantics as Postgres, and a reviewer running `./mvnw spring-boot:run` gets a system that
demonstrably behaves like the deployed one. It is also the cheapest possible defence against the
classic event-sourcing bug: an in-memory store that silently accepts a stale `expectedVersion`.

### 9.3 BDD / acceptance — Cucumber-JVM
Gherkin scenarios in business language, run against the real application. Example:

```gherkin
Feature: Withdrawals respect the available balance

  Scenario: A withdrawal larger than the balance is refused
    Given an account "ACC-1" in GBP with a balance of 50.00
    When a withdrawal of 100.00 is requested
    Then the request is refused with "insufficient-funds"
    And the balance of "ACC-1" is still 50.00
    And a "MovementRejected" event is recorded
```

Steps drive the HTTP API, not internal classes — the specification must not depend on the design.

#### Scenario catalogue

Every scenario below is a committed `.feature` file, run by Cucumber in-process (§9.3) and by
pytest-bdd against the composed stack (§9.6). The same Gherkin, two runners, two levels.

**Positive — `deposits.feature`, `withdrawals.feature`, `history.feature`**

| # | Scenario | Asserts |
|---|---|---|
| P1 | `alice` deposits 100.00 into `ACC-001` | `201`; balance 100.00; `MoneyDeposited` on the stream at version 2 |
| P2 | `alice` withdraws 30.00 | `201`; balance 70.00; `MoneyWithdrawn` at version 3 |
| P3 | `alice` withdraws her exact balance | `201`; balance 0.00. The boundary is allowed — only *exceeding* is refused |
| P4 | `alice` reads history | Newest first; each entry carries the correct `balanceAfter`; the sequence reconciles to the balance |
| P5 | `bob` deposits into `ACC-002` while `alice` transacts | Streams are independent; neither balance is affected by the other |
| P6 | `alice` replays a deposit with the same `Idempotency-Key` | `200` not `201`; `Idempotency-Replayed: true`; **balance credited once** |

**Negative — `insufficient-funds.feature`, `concurrency.feature`, `authorisation.feature`, `rate-limit.feature`**

| # | Scenario | Asserts |
|---|---|---|
| N1 | Single withdrawal exceeds balance | `422` `insufficient-funds`; **balance unchanged**; `MovementRejected` recorded with a reason |
| N2 | **Concurrent withdrawals, individually affordable, collectively over balance** — 10 parallel withdrawals of 20.00 against a balance of 100.00 | Exactly 5 succeed. **The balance never goes negative at any observed point.** The rest get `422` or `409`. Stream versions are contiguous with no gaps and no duplicates |
| N3 | Two writers race on the same aggregate with the same `expectedVersion` | Exactly one wins; the loser gets `409` `version-conflict` and succeeds on retry |
| N4 | Deposit of `0.00`, a negative amount, or `10.001` | `400` `invalid-amount`; nothing appended to the stream |
| N5 | Movement in a currency the account does not hold | `400`; no partial application |
| N6 | `carol` (reader) attempts a withdrawal | `403`; no event |
| N7 | `mallory` reads `ACC-001` | `403`. Valid token, correct role, wrong owner |
| N8 | `dave` (auditor) attempts a deposit | `403`; auditors observe, never mutate |
| N9 | `alice` exceeds 100 writes in a minute | `429` with `Retry-After`; the accepted writes are all durably applied |
| N10 | Unauthenticated request to any endpoint | `401`; no information about whether the account exists |

**Eventual consistency — `eventual-consistency.feature`**

The read side lags the write side by design (§4.4). Untested, that design decision is
indistinguishable from a bug, so the lag is asserted rather than hoped away.

| # | Scenario | Asserts |
|---|---|---|
| E1 | **The stale window exists.** Pause the `balance` listener, deposit 100.00, read the projected balance | `201` from the write; the projection still reports the old value; the response carries an `asOf` and a `streamVersion` behind the aggregate's — staleness is *visible to the client*, not silent |
| E2 | **Convergence.** Resume the listener | The projection reaches 100.00 within the SLO. Asserted with Awaitility and an explicit timeout |
| E3 | **Read-your-writes escape hatch.** During the stale window, read with `?consistency=strong` | Correct value immediately, bypassing cache and projection |
| E4 | **Duplicate delivery is harmless.** Deliver the same `MoneyDeposited` twice to the projection | Balance credited **once**. At-least-once transport demands an idempotent handler, keyed on `(stream_id, version)` |
| E5 | **Out-of-order delivery is rejected, not applied.** Deliver version 5 before version 4 | The projection does not apply 5; it either buffers or refuses and catches up in order. A projection that applies out of order produces a balance that never existed |
| E6 | **Consumer outage and catch-up.** Stop the `audit` consumer, write 50 movements, restart it | All 50 arrive; the audit trail matches the event stream exactly; no gaps, no duplicates |
| E7 | **Restart replays incomplete publications.** Kill the app mid-publication, restart | Spring Modulith's incomplete-publication retry completes the delivery; the projection converges without manual intervention |
| E8 | **Full rebuild from the log.** Drop the projection entirely and replay the stream | Rebuilt state is byte-identical to the state before the drop. This is the strongest guarantee event sourcing offers, and the one that makes the design worth its cost |
| E9 | **Lag gates readiness.** Hold the listener until projection lag exceeds the threshold | The readiness probe reports *not ready*; the instance stops receiving traffic rather than serving stale balances |

**Method:** never `Thread.sleep`. Convergence is asserted with **Awaitility** and a stated timeout;
the stale window is produced *deliberately* by pausing a listener, so the test observes the lag rather
than racing it. E1 and E2 are the same write examined on both sides of the boundary — which is the
only honest way to specify eventual consistency.

**N2 is the scenario this whole architecture exists for.** It is the only one that fails on a design
that stores the balance as a mutable field, and it is the reason for optimistic concurrency on
`(stream_id, version)` rather than a read-then-write. It runs in the `containers` group against real
Postgres, because an in-memory store can pass it for the wrong reason — which is exactly what the
port contract test in §9.2b is there to rule out.

### 9.4 Integration — Spring Boot Test + Testcontainers
Real Postgres, Kafka, Redis and Keycloak in containers. Event-store concurrency semantics, event
externalisation, projection updates, cache eviction on events, JWT validation, rate-limit
enforcement.

**Observability assertions** (§6.6), using `InMemorySpanExporter` and a `SimpleMeterRegistry`:

- A withdrawal produces the expected span tree with `ledger.account_id` and `ledger.stream_version`
  populated.
- `traceparent` survives the Kafka hop — the `audit` consumer's span carries a **link** back to the
  producing span, and is not a detached root.
- A `MovementRejected` increments the rejection counter tagged with the correct reason.
- Projection lag is reported as a gauge and drives the readiness probe (E9).

### 9.5 Use-case / validation testing
One test per use case in §2.4 asserting the *complete* observable outcome: response, emitted events,
projection state, audit record, cache state. This is the level that catches "the API returned 201 but
the projection never updated".

Validation testing covers the boundary: every field constraint, currency mismatch, negative and
zero amounts, excessive scale, malformed JSON, missing idempotency key, oversized payload.

### 9.6 End-to-end
`docker compose up`, then the **Python CLI (§11) drives real scenarios against the running stack** —
open account, deposit, withdraw, verify balance, exhaust the rate limit, confirm the 429, replay an
idempotent request, confirm no double credit. Run in CI on the composed stack.

### 9.7 Load and performance — Gatling + JMH
- **Gatling:** ramp to 500 concurrent users; assert p99 write latency < 150 ms, p99 cached read
  < 20 ms, error rate < 0.1%. Scenarios: steady state, burst, and hot-account contention (all
  traffic on one aggregate — the pathological case for optimistic concurrency).
- **JMH:** microbenchmarks on event replay, snapshot restore and `Money` arithmetic.
- Thresholds are assertions. A regression fails the pipeline.

---

## 10. ISO compliance

Documented as traceability matrices, each row pointing at the artefact that satisfies it. Claims
without evidence are worse than no claims.

**ISO/IEC 25010** (product quality) — `docs/compliance/iso-25010.md`. Each of the eight
characteristics mapped to concrete mechanisms and the tests that demonstrate them: functional
correctness → §9.3/9.5; performance efficiency → §9.7; security → §6.4 + §9.4; maintainability →
§9.2 + module structure; reliability → idempotency, optimistic concurrency, outbox.

**ISO/IEC 27001:2022 Annex A** — `docs/compliance/iso-27001-controls.md`. The applicable controls
only, with honest gaps marked: A.8.2 privileged access, A.8.5 secure authentication, A.8.15 logging,
A.8.16 monitoring, A.8.24 cryptography, A.8.28 secure coding, A.5.14 information transfer. Controls
that a PoC cannot satisfy (physical security, supplier management, HR screening) are listed as **out
of scope** rather than silently claimed.

---

## 11. Python CLI

`ledger-cli` — the e2e driver and a genuine operator tool.

**House style is gflow-cli's.** That project already settled these questions; re-deciding them here
would produce a second convention to maintain for no gain. Conventions adopted verbatim:

| Concern | Choice |
|---|---|
| Python | **3.11, 3.12, 3.13** — `requires-python = ">=3.11"`, all three in the classifiers and in the CI matrix |
| Packaging | `uv` + `pyproject.toml` (PEP 621), **hatchling** backend, `src/` layout, `uv.lock` committed |
| Dev deps | **PEP 735 `[dependency-groups]`** — `dev`, `containers` — installed with `uv sync --group` |
| CLI | **click ≥8.1** (`[project.scripts] ledger-cli = "ledger_cli.cli:main"`) |
| HTTP | **httpx** with **tenacity** retries and explicit timeouts |
| Output | **rich** — tables, progress, colour; `--json` for machine use |
| Logging | **structlog**. `print()` is banned in `src/` by ruff `T20`; `console.print()` is the exception |
| Config | **pydantic-settings** + **platformdirs** for config/cache locations |
| Validation | **Pydantic v2** models generated from `openapi.yaml` |
| Auth | OAuth2 client-credentials against Keycloak; token cached and refreshed |
| Lint/format | **ruff**, exact-pinned (`ruff==0.16.1`), `line-length = 100`, `target-version = "py311"`, `select = ["E","F","W","I","B","UP","N","T20"]` |
| Types | **pyright**, `strict` on `src/ledger_cli`, `pythonVersion = "3.11"` — not mypy |
| Testing | **pytest** + **pytest-bdd** + **pytest-cov** + **respx**; **testcontainers** in the `containers` group |
| Markers | `unit` (default), `integration`, `containers`, `e2e`, `live`, `smoke`; `addopts` excludes everything but `unit`/`integration` so the default run is fast and offline |
| Temp files | `--basetemp=tmp/pytest`, so tests never litter the repo root on Windows |
| Secrets | `detect-secrets` baseline + `gitleaks` + `pre-commit`, as in gflow-cli |

Dependency ranges get upper bounds **only where a bump is load-bearing**, with a comment stating what
broke and when — the gflow-cli convention. Unbounded elsewhere.

```bash
ledger-cli account open --currency GBP
ledger-cli deposit  --account ACC-001 --amount 100.00
ledger-cli withdraw --account ACC-001 --amount 30.00
ledger-cli balance  --account ACC-001 --watch
ledger-cli history  --account ACC-001 --limit 20
ledger-cli scenario run edge-cases     # drives §9.6 end-to-end
ledger-cli scenario run rate-limit     # exhausts the bucket, asserts 429
```

Pydantic models are **generated from `openapi.yaml`**, so the CLI cannot drift from the contract
either.

---

## 12. Docker and delivery

- **Multi-stage `Dockerfile`:** Maven build stage → `eclipse-temurin:25-jre` runtime. Non-root user,
  read-only root filesystem, no shell in the final image, JVM container-aware flags. `dr-jskill`'s
  AOT, native (GraalVM 25) and CRaC variants are carried alongside — startup time is a legitimate
  talking point for a payments service, and the assets already exist.
- **`docker-compose.yml`:** app, Postgres, Kafka (KRaft, no ZooKeeper), Redis, Keycloak with a
  pre-provisioned realm, OTel Collector, Prometheus, Grafana, Tempo. Healthchecks and dependency
  ordering so `docker compose up` reaches a working system unattended.
- **Migrations:** Flyway, versioned, applied on startup.
- **Config:** environment variables only; no secrets in images or compose files — `.env.example`
  documents every variable.
### 12.1 Pipeline (GitHub Actions)

Ordered cheapest-and-most-informative first, so a broken build fails in under two minutes rather than
after the load test.

| # | Stage | Gate | Runs on |
|---|---|---|---|
| 1 | Lint & format | `ruff` (pinned), `spotless:check` | every push |
| 2 | Compile + unit | JUnit, JaCoCo ≥90% line / 85% branch on `domain` | every push |
| 3 | **Architecture** | `ApplicationModules.verify()` + ArchUnit (§9.2) | every push |
| 4 | **Contract** | OpenAPI-generated interfaces compile; port contract suites (§9.2b) | every push |
| 5 | BDD in-process | Cucumber, full scenario catalogue against `standalone` | every push |
| 6 | **Documentation** | `test_docs_governance.py`: artefact presence, the seven ISO markers, no pre-release version strings, every `TODO(25010)` registered, no unlinked SoA gap row. Plus link check, generated-artefact freshness, and the §8.6 docs-travel-with-code prompt | **every push** |
| 7 | Integration | Testcontainers: Postgres, Kafka, Redis, Keycloak | every push |
| 8 | Python CLI | `pytest` matrix on **3.11, 3.12, 3.13**; `pyright` strict; `ruff` | on `ledger-cli/**` |
| 9 | E2E | `docker compose up`, then `ledger-cli scenario run` (§9.6) — **including the README's extracted `curl` examples** (§8.3) | PR + main |
| 10 | Load | Gatling; p99 write <150 ms, p99 cached read <20 ms, errors <0.1% | main + nightly |
| 11 | Security | `gitleaks`, `detect-secrets`, Trivy image scan, `dependency-check` | every push |
| 12 | Publish | Multi-arch image, CycloneDX SBOM, generated module diagrams to `docs/generated/` | main |

Stages 3, 4, 5 and 6 are the ones worth pointing at: they fail on a *design or documentation*
regression, not a behavioural one. An agent-assisted codebase moving at speed needs boundary and
documentation violations caught mechanically, because they are exactly the class of error that
reviews miss and tests otherwise tolerate.

**Stage 6 sits before integration deliberately.** Documentation failures are cheap to detect and
cheap to fix; discovering them after a twelve-minute Testcontainers run trains everyone to ignore
them. Position in a pipeline is a statement about priority, and this is the one that makes "first-class
citizen" true rather than aspirational.

`resolve-drift` job, borrowed from `gflow-cli`: install the Python CLI from declared ranges rather
than the lockfile, and smoke-import it. Catches the unbounded-dependency break that a committed
`uv.lock` hides until a user installs fresh.

---

## 13. Non-goals

Stated so their absence reads as a decision:

- Multi-currency arithmetic and FX. Accounts are single-currency; cross-currency movement is rejected.
- Double-entry bookkeeping across accounts. The model is single-entry per account; transfers between
  accounts (and the saga that makes them atomic) are the natural next increment.
- Interest, fees, statements, reconciliation.
- Horizontal scaling of the write path beyond a single instance per aggregate partition.
- Real KYC/AML. `audit` records what happened; it does not judge it.

---

## 14. Implementation order

Each step ends green and demonstrable.

| # | Step | Done when |
|---|---|---|
| 0 | **Docs scaffold first** — `docs/` Diátaxis tree, INDEX, CHANGELOG, `test_docs_governance.py` wired into `verify` | The governance test runs and **fails**, listing every missing artefact. That failing list is the documentation backlog, generated rather than guessed |
| 1 | Skeleton, pom, Modulith verification, CI | `mvn verify` green on an empty module graph |
| 2 | `shared` + `ledger` domain, in-memory event store | Unit + architecture tests green; `standalone` serves all six endpoints |
| 3 | OpenAPI contract + generated interfaces | Controller drift breaks the build |
| 4 | Cucumber feature suite | Every §2 requirement has a green scenario |
| 5 | Postgres event store + Flyway + outbox | Integration tests green on Testcontainers |
| 6 | Projections + Redis cache + event-driven eviction | Use-case tests assert projection and cache state |
| 7 | Kafka relay + `audit` module | Audit trail rebuilt from the stream |
| 8 | Keycloak + RBAC + rate limiting | Security and rate-limit integration tests green |
| 9 | Observability stack | Dashboards render live traffic; readiness gates on projection lag |
| 10 | Python CLI + e2e scenarios | `ledger-cli scenario run edge-cases` green against compose |
| 11 | Gatling + JMH + thresholds | Pipeline fails on regression |
| 12 | **JVM assessment with `jvm-pulse`** — once the system is stable under load | GC + JFR telemetry captured against the composed stack (`pulse attach --docker <container> --duration 30s`) during a Gatling run; `report.html` committed to `docs/profiling/`; a `compare` against the pre-tuning baseline; tuning conclusions recorded as an ADR. **Run last, deliberately** — profiling an unstable system measures the instability, not the system |
| 13 | Compliance run with the `iso-compliance` skill | Governance test green; SoA dispositions all 34 A.8 controls; 25010 coverage table complete; dated acceptance record in the ISO hub |

---

## 15. Documented assumptions

1. Single currency per account; the currency is fixed at opening.
2. No overdraft. Withdrawals beyond balance are refused and recorded as `MovementRejected`.
3. Balance is eventually consistent on the read path unless `consistency=strong` is requested.
4. Idempotency keys are client-generated UUIDs, valid for 24 hours.
5. Timestamps are server-assigned UTC `Instant`s; client-supplied times are ignored.
6. `standalone` mode loses all state on restart. This is intentional and documented in the README.
7. Amounts are stored as `long` minor units; the API speaks decimal strings. Conversion is validated
   at the boundary and rejects excessive scale rather than rounding silently.
