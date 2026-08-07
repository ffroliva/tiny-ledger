# ADR 0004 — Readiness does not gate on lag

**Status:** Accepted
**Date:** 2026-08-07
**Context:** spec §6.6 (Health), §9.3 case `E9`, §4.3; supersedes the health paragraph as written
through spec v3.31

## Context

Spec §6.6 said, from v3.0 through v3.31:

> Readiness gates on event-store reachability **and projection lag under threshold**, so an instance
> whose read models have fallen behind stops taking traffic instead of serving stale balances.

Case `E9` was written directly against that sentence: *"Hold the listener until projection lag exceeds
the threshold → the readiness probe reports not ready; the instance stops receiving traffic rather than
serving stale balances."* `E9` is the last open case in the §9.3 catalogue, and it stayed open through
a dozen revisions described first as *deferred by decision*, then — correctly, at v3.26 — as *unbuildable
because the feature is absent*.

Both descriptions were right about the outcome and wrong about the cause. The feature was indeed absent.
But building it would not have made `E9` pass, because **the behaviour `E9` describes cannot occur in
this architecture**.

## The finding

**Balance-projection lag is structurally zero, not merely small.**

`LedgerEventsListener` is a plain `@EventListener`. It runs synchronously, on the publishing thread,
inside the write transaction, in **both** run modes — ratified at spec v3.5 (§4.3, "Standalone caveat")
and unchanged since. The listener's own javadoc records why `@ApplicationModuleListener` was rejected,
and §6.6's trace-context table states the same fact four paragraphs above the claim it contradicts:

> Two of those arrows cross a thread or process boundary — *the projection does not, it runs on the
> publishing thread inside the same transaction (§4.3)* — and on each one an unconfigured setup silently
> starts a fresh trace.

So there is no window in which a **projection** is stale. A projection read either sees the write or the
write has not committed. `E9`'s stated method — holding the listener — would block the write itself
rather than produce lag behind it.

Corroborated independently, and before this ADR was written: `PausableListenerGate` — the test-support
class E1–E5 rest on — exists precisely because "a write is projected before the `PUT` returns and there
is no window to observe" (`PausableListenerGate:13-16`). It creates E1's stale window by substituting a
`@Primary` projector. **E1's window is test scaffolding; production has no equivalent.**

### Correction: "stale balances" is not impossible, only unreachable by projection lag

The first version of this ADR said `E9`'s stated harm — *"serving stale balances"* — had **no mechanism
at all**. That was an overstatement, and the mechanism is in production code.

`BalanceProjector:20-32` applies the event and then evicts the cache **inside the still-open append
transaction**, before commit. A concurrent read arriving between the eviction and the commit repopulates
the cache from the pre-write projection, and that entry is then stale for up to the 60 s TTL (§6.2). The
class carries this in a comment; it was known and accepted, not discovered here.

**This does not change the decision, and it strengthens the reason for it.** A stale cache entry lives
in **shared Redis** and is therefore visible to every replica identically. Taking one instance out of
service does not repair it, does not shorten it, and does not hide it — the next instance serves the same
entry from the same store. Readiness gating is not merely the wrong tool for outbox lag; it is useless
against the one staleness mechanism this system actually has.

The mechanism is bounded (60 s TTL), visible to clients (`asOf`/`streamVersion`), and escapable
(`?consistency=strong`, E3) — which is exactly the eventual-consistency contract §4.0 has always stated.
Closing the window would mean moving eviction to a post-commit `TransactionSynchronization`, which
`BalanceProjector`'s own comment names as the upgrade path. That is a §6.2 question, not a readiness one.

**The lag that does exist is somewhere else — and there are two of them, which the first version of
this ADR conflated.** The Kafka leg is the only asynchronous path: events reach the broker through the
Modulith event-publication registry (`event_publication`, migration `004`), and `AuditKafkaListener`
consumes them in the `audit` module.

Those are **two independent lags separated by the broker**, and only the first is measurable here:

| Lag | Measured by | Rises when |
|---|---|---|
| **Producer side** — event committed, not yet acked by Kafka | `ledger.outbox.pending.age.seconds` | The **broker** is slow or down |
| **Consumer side** — acked by Kafka, not yet in the audit trail | **nothing today** | The **consumer** is slow, stopped, or rebalancing |

The reason is `completion-mode=DELETE` (`application-full.properties:47-50`), whose own comment states
it: *"the publication row goes **the moment Kafka acknowledges** — the queue only ever holds in-flight
and failed work."* `AuditKafkaListener` is a `@KafkaListener` on its own consumer group, entirely
downstream of that ack. **Stopping the consumer therefore leaves this gauge reading `0.0`.**

So the gauge measures **outbox pending age**, not audit-trail lag. It was named
`ledger.audit.lag.seconds` in the first version of this ADR, which described a quantity it cannot
observe; renamed at spec v3.37. Neither lag makes balances stale.

**Consumer lag is a real and currently unobserved gap**, recorded rather than quietly left: nothing in
this repository watches consumer offsets, and `FullAdapterConfig` parks unprocessable records on
`ledger.events.DLT` with a javadoc saying it exists to prevent "a silent, permanent hole in the
compliance trail" — with nothing observing that topic either. Both belong to step 9 part 2, where an
exporter exists to carry them.

## Decision

**Readiness gates on event-store reachability only. Lag is measured and published as a gauge, and
nothing gates on it.**

| Probe | Contains | Reason |
|---|---|---|
| `liveness` | `livenessState` | A liveness probe that fails on a dependency restarts a process that was working |
| `readiness` | `readinessState` + `db` | In `full` the event store **is** Postgres. In `standalone` it is in-memory, so the group is `readinessState` alone |
| — | `redis`, `kafka` **excluded** | See below |

`ledger.outbox.pending.age.seconds` is a gauge over the age of the oldest incomplete publication. It exists in
`full` only, because `standalone` has no `event_publication` table. The spec's existing numbers survive
as **alerting** thresholds, relabelled to describe outbox pending age rather than projection lag: p99 < 2 s
steady-state, 5 s worth paging on.

## Why gating on the real lag would be worse than not gating

Because it would fail a case this suite already passes.

**E11** requires that with Kafka paused, writes still return `201`, `?consistency=strong` still returns
the correct balance, and the write does not block on the broker. It was measured on 2026-08-07 at 164 ms
— indistinguishable from a healthy write — which is the evidence behind ADR 0002's "Kafka is the courier,
Postgres is the record".

An instance that flipped `readiness` to DOWN when outbox lag crossed five seconds would remove *every*
instance from service during exactly that outage. The ledger would stop serving reads and writes it is
provably capable of serving correctly, because a downstream compliance consumer was behind. That inverts
the property ADR 0002 was written to protect.

The same argument excludes Redis from the readiness group. **Corrected at spec v3.37:** the first
version of this ADR said Boot "auto-configures a health indicator for each" of Redis and Kafka. That is
true of Redis and **false of Kafka** — `spring-boot-kafka-4.1.0.jar` ships no health contributor, while
`spring-boot-data-redis` and `spring-boot-jdbc` both do. So the Redis exclusion is a **guard**, and the
Kafka exclusion documents **intent** rather than restraining anything that would otherwise happen.

The distinction matters beyond pedantry: naming a contributor that does not exist is a startup failure,
not a no-op. `HealthContributorMembershipValidator` refuses to start when a group names an unknown
contributor — so an attempt to *add* `kafka` to the readiness group, whether to test the exclusion or by
mistake, fails at context creation rather than at an assertion.

**E10** (Redis paused: rate limiting fails open, the write still `201`s, strong reads stay exact) would
break against a readiness group that trusted Boot's defaults. **E11** is protected by the absence of a
Kafka contributor rather than by this decision — which is worth knowing, because that absence is a
property of the framework version and could change under an upgrade.

## Consequences

- **`E9` is rewritten, not closed.** It now asserts the honest behaviour: pause **the broker**, the
  gauge rises past the threshold, balances stay exact, readiness stays **UP**. It remains the one open
  case in §9.3 and closes when §14 step 9 lands. *The method was "pause the audit consumer" until spec
  v3.37 — an experiment that reads `0.0`, for the `completion-mode=DELETE` reason above. Pausing the
  broker is what makes publications accumulate.*
- **The gauge's SQL must exclude `FAILED` rows.** Modulith's mark-failed path sets `status = 'FAILED'`
  and never sets `completion_date`, and resubmission is restart-only
  (`republish-outstanding-events-on-restart=true`). Without the exclusion, one poison row pins
  `MIN(publication_date)` permanently: the alert fires forever and every genuine excursion after it is
  invisible behind the stuck value.
- **The 2 s / 5 s thresholds have no enforcement.** They are inputs to an alert this repository does not
  ship. Per `AGENTS.md` — *if you state a rule that this file does not enforce, say which gate enforces
  it, or say plainly that none does* — §6.6 now says plainly that none does.
- **The how-to quadrant stays empty.** Spec §8.1 wants a runbook answering *"lag is alerting, what do I
  do?"*. Step 9 gives the gauge a value but nothing pages on it, so there is still no trigger to write a
  procedure for, and §8.1 continues to name the absence rather than fill it.
- **This does not close the door on gating.** If the projection is ever moved off-thread — the listener's
  javadoc calls that "a Plan 3 question" — projection lag becomes real and this ADR should be revisited.
  The decision is about the architecture as built, not about the idea.

## Alternatives rejected

**Implement §6.6 literally.** Gate readiness on outbox lag at the 5 s threshold. Faithful to the spec and
to `E9` as written, and needs no spec change. Rejected: it contradicts `E11`, and `KafkaOutageIT` would
have to be changed to accommodate it — rewriting a passing test to match a sentence, rather than the
reverse.

**Move the projection off-thread so lag is real.** Make `LedgerEventsListener` asynchronous behind a flag,
then gate on the lag that results. The only option under which `E9` is literally true. Rejected: it
reverses a decision ratified at v3.5, breaks read-your-writes, makes the two run modes diverge in a way
§9.2b treats as a defect, and is far outside §14 step 9's scope. Producing a failure mode in order to
build a guard against it is not an improvement.

---

## Second decision: Actuator's endpoint exposure is assessed per endpoint, not defaulted

Added 2026-08-07 (spec v3.40), when §14 step 9 part 1 was built. It belongs here rather than only in the
execution plan for the reason `docs/INDEX.md` states: **plans are not contract, ADRs are** — and this is
the largest attack surface this repository has added since the resource server.

**Verdict: `health` only, and only its two probe groups are reachable.**

| Endpoint | Verdict | Why |
|---|---|---|
| `health/liveness`, `health/readiness` | **Open, unauthenticated** | A probe that needs a credential cannot report the outage that took the token issuer away |
| `health` (root) | **Closed** | The aggregate UP/DOWN tells an unauthenticated caller when the system is degraded — useful for timing an attack, useless to anyone else |
| `heapdump` | **Never** | Dumps live process memory: balances, bearer tokens, the Redis password. The worst single endpoint in the set |
| `env`, `configprops` | **Never** | Renders configuration including `issuer-uri` and the datasource URL. A direct §6.5 violation |
| `loggers` | **Never** | `POST` mutates log level at runtime — a write that could switch on payload logging |
| `httpexchanges` | **Never** | Recent request/response history, i.e. the PII §6.6 requires logs not to carry |
| `threaddump` | **Never** | Stack traces and internal paths — §6.5 forbids leaking these even from `/error` |
| `beans`, `mappings`, `conditions` | **Never** | Internal structure; §6.5's "no internal identifier crosses the API boundary" |
| `caches` | **Never** | Remote eviction of the balance cache |
| `shutdown` | **Never** | Remote kill. Off by default — stated so nobody turns it on believing it was an oversight |
| `info` | **Closed** | Inert today: no `build-info.properties`, no git properties, no `info.*` keys, so it would render `{}`. Closed anyway, because adding the build-info goal is a thing a release pipeline does routinely and would start publishing version and commit SHA with no second decision point |
| `liquibase`, `auditevents`, `sbom`, `startup`, `scheduledtasks` | **Closed** | No operational need in this system |
| `metrics`, `prometheus` | **Closed** | Metrics leave over OTLP in part 2. A scrape endpoint is a second path to the same data and a second thing to secure |

The table was **measured, not predicted**. Booting `standalone` with `exposure.include=*` maps twelve
endpoints — `beans`, `conditions`, `configprops`, `env`, `health`, `info`, `loggers`, `mappings`,
`metrics`, `sbom`, `scheduledtasks`, `threaddump`. `info` was absent from every draft of this list until
that run; eight entries above do not exist on this classpath at all and are kept as decisions in advance.

### Two layers, and they do not overlap where you would assume

1. `management.endpoints.web.exposure.include=health` — anything absent is never web-mapped.
2. `denyAll` on a management-scoped `SecurityFilterChain`, with only the two probe paths permitted.

Layer 1 is a configuration line someone will eventually edit; layer 2 is why that edit stays harmless.

**But layer 1 does not cover the health root.** Exposing `health` is exactly what maps `/actuator/health`,
and the probe groups are sub-paths of it — there is no exposure setting that yields the groups without the
root. Measured with layer 2 removed, the root answered `503` rather than `404`. So for the one endpoint
whose disclosure this ADR most cares about, **`denyAll` is the only defence, not the second one.**

That has a consequence for how it is tested, and it cost a false green to find. `ActuatorProbeTest`
asserts the other nineteen endpoints as merely *not-200*, correctly: layer 1 answers `404` and layer 2
answers `403`, and pinning either would turn the security rule into a test that the endpoint was never
enabled. Applying the same assertion to the root is **vacuous** — under `standalone` it aggregates a
`redis` contributor that can never be UP, so it answers `503` whether denied or rendered. The root is
therefore asserted as **`403` exactly**.

### `show-details=never`, for every caller

The probe body is `{"status":"UP"}` and nothing more. No caller — authenticated or not — sees which
component is down. That is answerable from `ledger.outbox.pending.age.seconds` and the logs, by people who
already have access to them. The rejected alternative was `when-authorized` behind a new `ledger:operator`
role: a fourth role, a Keycloak realm change and new authorization tests, for detail those people can
already reach.

### The separate management port is built, not deferred

An earlier draft of this ADR carried it as an upgrade path behind the trigger *"revisit at deployment to an
orchestrator"*. **That trigger fired**: ADR 0005 makes Kubernetes the production target, so the split is
built. Probes bind to `management.server.port=9090`, so a misconfigured endpoint is *unreachable* rather
than merely denied. Two qualifications, because the obvious version of this sentence claims too much:

- **"Unpublished" is enforced by nothing in this repository.** A NetworkPolicy would enforce it and does
  not exist.
- **The address is pinned to loopback in `standalone` only.** `ManagementWebServerFactoryCustomizer`
  applies `management.server.address` unconditionally, so declaring only the port overwrites the parent
  bind and would give `standalone` — whose entire safety argument is its loopback bind — a `0.0.0.0`
  listener. Pinning it in `full` would break every Kubernetes `httpGet` probe, because the kubelet dials
  the pod IP.

**No gate enforces that `application.properties` declares the port at all.** `ActuatorProbeTest` supplies
its own `management.server.port=0` to avoid port contention between parallel runs, so deleting the base
property leaves it green. Named rather than implied, per `AGENTS.md`.

### One guard is provable more cheaply than expected

`spring-boot-starter-data-redis` is an unconditional dependency, so Boot auto-configures
`RedisHealthIndicator` under `standalone` too — where no Redis exists and none is wanted,
`RateLimitConfig` using Caffeine there. Its contributor reads DOWN with no container running, which makes
the `redis`-excluded readiness decision above **provable by violation on the fast `verify` path**: adding
`redis` to the group reddens exactly two assertions. E10's own coverage needs a real outage under `-Pit`.

The `kafka` exclusion has no equivalent and cannot get one: there is no Kafka health contributor to
exclude, and naming a non-existent one is a startup failure rather than a no-op. **E11 is protected by a
framework absence that an upgrade could remove silently.**
