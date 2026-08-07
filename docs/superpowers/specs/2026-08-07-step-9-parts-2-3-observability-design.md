# Design — §14 step 9 parts 2 and 3: tracing, OTLP export, JSON logs, the opt-in Collector

**Date:** 2026-08-07 · **Spec at time of writing:** v3.40 · **Base:** `379cb8e`

Part 1 (PR #12) delivered the Actuator probes, the outbox gauge, graceful shutdown and the
resource-attribute identity. This design covers what remains of §14 step 9: the signals themselves
and the container that carries them off the machine.

**Done when** — unchanged from §14, and both halves must hold:

1. §9.4's `InMemorySpanExporter` assertions pass, and
2. a Collector container receives real spans and metrics over OTLP.

## What is already decided and is NOT re-opened here

From spec v3.32, §6.6 and ADR 0005. Listed so that a reader can see the design's inputs rather than
mistake them for its conclusions:

- Micrometer Tracing over the OpenTelemetry **bridge**, never the OTel Java agent.
- **One** opt-in Collector behind a Compose `profiles: [observability]` key, forwarding to hosted
  Grafana Cloud. Never a local Prometheus/Grafana/Tempo/Loki stack.
- OTLP export **off by default**, so an inactive profile costs no failed-export noise.
- Fan-out uses span **links**, not parent-child.
- Domain spans come from a use-case **decorator**, so §9.2's framework-free application layer
  survives instrumentation.
- CI gets **no** Grafana credential. The gate is a Collector receiving OTLP, which a file or debug
  exporter satisfies — a fork's build still passes and CI gains no third party.

## Four decisions taken in this design

Each was a genuine fork, and each is recorded with the option not taken.

| # | Decision | Rejected alternative |
|---|---|---|
| 1 | **JSON logs in `full` only.** `standalone` keeps a human-readable console. Both modes stamp `trace_id`/`span_id` on every line. Logs are **not** exported over OTLP | JSON in both modes — turns every `./mvnw verify` and every CI failure log into JSON, paid on every run for a benefit taken in production. And OTLP log export — a third signal beyond the done-when, for a backend nothing yet reads |
| 2 | **The Collector test boots `standalone`.** One container, not five | Booting `full`, as §9.4 describes today. Nothing the test asserts needs a database, and it would put a new test inside the delicate shared `full` context (ADR 0003, CR13) for no benefit. §9.4 is corrected instead |
| 3 | **Both audit blind spots are closed** — consumer lag and a dead-letter counter | Leaving them named-but-open. §6.6 already says they "belong to step 9 part 2, where an exporter exists to carry them"; deferring a second time would make that sentence false |
| 4 | **The Compose Collector tail-samples.** App head-samples at 100%; the Collector keeps every error and every slow trace and samples the rest | Forwarding everything. Simpler by one file, but §6.6's "tail-sampled in `full`" would have to be narrowed to "not delivered", and a 100%-forwarded stream fills a free-tier quota with successful 12 ms deposits |

## Architecture

### Dependencies — one line, not four

```xml
<dependency><groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-opentelemetry</artifactId></dependency>
<dependency><groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-sdk-testing</artifactId><scope>test</scope></dependency>
```

Read out of the published POM, not assumed: `spring-boot-starter-opentelemetry` (Boot 4.1.0) brings
`micrometer-registry-otlp`, `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`, plus
`spring-boot-starter-micrometer-metrics`, `spring-boot-micrometer-tracing-opentelemetry` and
`spring-boot-opentelemetry`. Every version is managed by the Boot BOM
(`micrometer 1.17.0`, `micrometer-tracing 1.7.0`, `opentelemetry 1.62.0`).

`spring-boot-starter-opentelemetry-test` is **not** used: it carries `@AutoConfigureTracing` and
`@AutoConfigureMetrics` but no `InMemorySpanExporter`, and both annotations change a test's context
cache key (AGENTS trap 5). The same effect is available as a property — see *Testing* below.

### Where spans come from

| Span | Source | New code |
|---|---|---|
| HTTP server | Boot observation, already on the classpath | none |
| Use-case execution | `TracedUseCases` decorator in `config` | one class |
| Projection apply | `LedgerEventsListener` | ~8 lines |
| Kafka produce, and the `traceparent` header | `spring.kafka.template.observation-enabled=true` | one property |
| Kafka consume, **linked** to the producing span | `AuditKafkaListener`, built by hand | ~15 lines |

**`TracedUseCases` mirrors `TransactionalUseCases`** — same package, same shape, same reason. That
is what keeps `..application..` free of Micrometer types exactly as it is free of `@Transactional`.
It carries `ledger.account_id`, `ledger.movement_type`, `ledger.stream_version` and
`ledger.rejection_reason`, all read off the returned `MovementResult`.

**It is the outermost decorator** — `traced → transactional → service` — so the span covers the
commit rather than ending before it. Consequence: `FullAdapterConfig`'s two transactional beans give
up `@Primary` and declare their concrete types, and the traced beans in `UseCaseConfig` become
`@Primary` and select the transactional delegate through an `ObjectProvider` (present in `full`,
absent in `standalone`). Two `@Primary` beans of one type is a context-startup failure, so this is
not optional bookkeeping.

**The Kafka consume span is built by hand because it must be a link, not a child.** Spring Kafka's
listener observation produces a child span, which is precisely what §6.6 refuses: a child makes the
producing request appear to last until the slowest consumer finishes and misreports
`http.server.duration` to every dashboard. So listener observation stays off and
`AuditKafkaListener` extracts `traceparent`, starts a new root span, and adds a link back.

**One row of §6.6 is narrowed, deliberately: there is no separate event-append span.**
`MovementResult` already carries `version`, `type` and `rejectionReason`, so every attribute §9.4
asserts sits on the use-case span. A second span inside the store adapters would add a row to a
diagram and nothing to an assertion — and it would have to be written twice, once per adapter.
§6.6's Spans row is edited to say where the append attributes actually live.

### Metrics — one new meter, plus two gap-closers

| Meter | Tags | Why |
|---|---|---|
| `ledger.movements` (counter) | `type` (DEPOSIT/WITHDRAWAL), `outcome` (settled/rejected/conflict), `reason` (`none` when settled) | Covers §6.6's "movements by type", "rejection rate by reason" and "concurrency-conflict rate" in one meter. ~16 series, permanently bounded |
| `ledger.audit.dead_lettered` (counter) | none | `FullAdapterConfig`'s recoverer parks unprocessable records on `ledger.events.DLT` to prevent "a silent, permanent hole in the compliance trail". Nothing counted them, so it produced one |
| Kafka consumer lag | Spring's own client metrics | Delivered **if** Boot binds `KafkaClientMetrics` on this classpath. Verified by looking, not assumed. If it does not, the row records "not delivered" and why — it is not quietly dropped |

`ledger.outbox.pending.age.seconds` is untouched, and still aggregates with `max`, never `sum`.

**Cardinality is a one-way door.** Account ids, movement UIDs and interaction ids appear on spans and
logs and on **no** meter. **No gate enforces this** — it is a review rule, and it looks harmless in a
diff.

### The span data-classification rule — the gap part 2 owes

§6.6 states a no-PII rule for logs and not for spans, while mandating `ledger.account_id` on every
span and sending spans to a third-party backend. §6.6 already calls that "a gap, not a decision".
This design closes it with a rule, not with an ADR — it is a constraint on what may be written, not
an architecture choice with rejected alternatives:

1. Span attributes are limited to the enumerated `ledger.*` set plus OTel semantic conventions.
   Anything else is a review failure.
2. No name, no email, no `owner`, no bearer token, no amount and no balance goes on a span. Account
   and movement identifiers are server-generated opaque UUIDs, and they are on spans because
   correlation is the reason spans exist.
3. Those UUIDs are still **personal data when linkable**, which is why the backend is Grafana Cloud's
   **UK** region (`prod-gb-south-1`) — telemetry carrying `ledger.account_id` stays in-country.
4. **No gate enforces any of the three.**

### Export configuration

`application.properties`, all off by default so an inactive Collector profile costs nothing:

```properties
management.tracing.export.otlp.enabled=false
management.otlp.metrics.export.enabled=false
management.tracing.sampling.probability=1.0
```

Boot 4.1 renamed these. The `management.otlp.tracing.*` spelling most documentation shows is the
deprecated alias; the current names are `management.tracing.export.otlp.enabled` and
`management.opentelemetry.tracing.export.otlp.endpoint`. Read out of each jar's
`spring-configuration-metadata.json`, not from memory.

Sampling is 100% at the application and is thinned at the Collector (decision 4). Spans are still
*created* with export off, so `trace_id` and `span_id` reach every log line and §9.4's assertions
have something to read.

`application-full.properties` gains `logging.structured.format.console=logstash` — Boot's built-in
structured logging, so no `logstash-logback-encoder` dependency and no `logback-spring.xml`.

### Part 3 — the Collector

- `docker/otel-collector.yaml` — OTLP receiver, `batch` and `tail_sampling` processors (keep errors,
  keep slow, probabilistic base), OTLP/HTTP exporter to Grafana Cloud addressed by `${env:…}`.
- `docker/docker-compose.yml` — one `otel-collector` service behind `profiles: [observability]`, so
  a plain `docker compose up` still starts exactly four containers.
- `.env.example` already carries the names (`OTEL_EXPORTER_OTLP_ENDPOINT`,
  `OTEL_EXPORTER_OTLP_HEADERS`); values live only in `.env.grafana`, which is gitignored.
- README documents turning it on.

**No automated check covers `docker/otel-collector.yaml`.** Nothing in CI starts it, and the
integration test uses its own config so it can assert against a file rather than a hosted backend.
It is proven by being run by hand, and the documentation says so instead of implying a gate.

## Testing

Two tests, and they are the two halves of the done-when.

### 1. `ObservabilityIT` — on the existing shared `full` context

An `InMemorySpanExporter` bean is imported on `AbstractIntegrationTest` itself, so the cache key
moves **uniformly** and the suite still builds one `full` context. `spring.test.tracing.export=true`
goes into the `@DynamicPropertySource` that is already there — which is exactly what ADR 0003 §1
prescribes: "supplied as a property through the existing `@DynamicPropertySource`, never via an
`@Import` on a subclass".

Asserts:

- a withdrawal produces the expected span tree, with `ledger.account_id` and `ledger.stream_version`
  populated;
- the audit consumer's span carries a **link** back to the producing span and is not a detached root;
- a `MovementRejected` increments `ledger.movements` with `outcome=rejected` and the right `reason`.

The meter registry needs no new bean: Boot supplies a simple registry in tests
(`management.simple.metrics.export.enabled=true`).

### 2. `OtlpExportIT` — `standalone`, one container

Starts an `otel/opentelemetry-collector-contrib` container configured with an OTLP receiver and a
**file** exporter, points the application at it, performs a real deposit over HTTP, and reads the
file back. It must contain our span and our metric. This is the only test that proves telemetry
leaves the process.

`standalone` is a separate profile context, in the same category as `CucumberSpringConfig` and
`LedgerEventsListenerTest` — it does not fork the shared `full` context, so ADR 0003's forking
conditions do not arise. §9.4 currently describes this test as forking `full` deliberately and being
"the only fork in the suite"; that sentence is corrected as part of this work.

### Traps this test suite will hit, named in advance

- **The OTLP metrics step interval defaults to 60 s.** A test that does not set
  `management.otlp.metrics.export.step` waits a minute or fails.
- **Boot silences telemetry export in tests.** `TracingContextCustomizerFactory` injects
  `management.tracing.export.enabled=false` and `MetricsContextCustomizerFactory` injects
  `management.defaults.metrics.export.enabled=false`, each overridable by `spring.test.tracing.export`
  / `spring.test.metrics.export`. Read from the bytecode of the shipped jars.
- **`./mvnw -q verify` must still start zero containers.** Both new tests are `*IT` and run under
  `-Pit` only. This is checked differentially, as AGENTS trap 7 requires.
- **`Tracer` bean availability.** Every context that constructs a span-emitting bean must have one.
  If any context does not, the injection point takes an `ObjectProvider` rather than the context
  failing to start.

### Evidence standard

Every assertion gets a red proof: the production change is reverted, the test is run by name, and the
run's surefire/failsafe **XML** is checked to confirm that the named test actually executed and
reddened **for the stated reason**. Part 1 shipped two red proofs that failed for the wrong reason
and one test that stayed green at 28/28 while the endpoint under test was wide open; a `-Dtest`
pattern matching nothing exits 0.

## Documents this changes

| Document | Change |
|---|---|
| `docs/spec.md` §6.6 | The span data-classification rule; the narrowed append-span row; sampling split between app and Collector; consumer lag and the DLT closed; exemplars still specified-and-not-delivered |
| `docs/spec.md` §9.4 | The Collector test runs `standalone`; the "forks the context / only fork in the suite" sentence corrected |
| `docs/spec.md` §14 | Step 9 row PARTIAL → done, once and only once both halves of the done-when hold |
| `docs/spec.md` revision history | One row, v3.41 |
| `CHANGELOG.md`, `README.md`, `.env.example`, `docs/INDEX.md` | The Collector opt-in, and the spec version |

## Out of scope, stated so it is not mistaken for an omission

- **Exemplars remain specified-and-not-delivered.** A Micrometer *Prometheus*-registry feature,
  unreachable on the OTLP path. Boot 4.1 does expose `management.tracing.exemplars.include`; whether
  that reaches the OTLP registry is checked during implementation, and if it does, §6.6's claim is
  false and gets corrected the same day. Nothing is built for it either way.
- **Dashboards and alerts.** A build cannot assert a hosted backend. §14's done-when was rewritten at
  v3.40 for exactly that reason.
- **Two gaps part 1 recorded as unproven stay unproven:** no gate enforces that
  `application.properties` declares `management.server.port`, and no test observes readiness going
  DOWN on a real Postgres outage. Neither is re-attempted here.
