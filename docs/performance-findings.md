# Performance findings — §9.7 measurement pass

**Status:** measured 2026-08-06 on `step-11-load`. Every number here came from a run that was
executed; nothing is projected. The four defects in §3 were found by *running* code that had only
ever been read, which is the single most transferable lesson on this page.

**Read §2 first if you are short of time.** It contains the one finding that changes how the system
should evolve, and it contains a second finding that says *do not optimise something*, which is worth
as much.

---

## 1. What was measured, and how honestly

JMH microbenchmarks (`benchmarks/`) on the two targets §9.7 names: **event replay** and **`Money`
arithmetic**. Gatling (`loadtest/`) carries §9.7's three scenarios and its thresholds as assertions.

| | |
|---|---|
| Harness | JMH 1.37, `AverageTime` mode, single fork |
| Run parameters | `-f 1 -wi 1 -i 2 -r 1s -w 1s` |
| JDK | 25 (Corretto), same as CI |

**These are indicative, not publication-grade.** One fork and one warm-up iteration is enough to
establish shape and order of magnitude — which is what the conclusions below rest on — and not enough
to defend a 5% difference between two candidate implementations. A decision run should use JMH's
defaults (5 forks, 5 warm-up iterations). The conclusions in §2 are all order-of-magnitude or
shape-of-curve claims, which this configuration does support.

---

## 2. The findings that matter

### 2.1 Replay is linear, and it is not the bottleneck — the *fetch* is

| History length | `Account.rehydrate` | Cost per event |
|---|---|---|
| 10 | 0.036 µs | 3.60 ns |
| 100 | 0.296 µs | 2.96 ns |
| 1,000 | 3.999 µs | 4.00 ns |
| 10,000 | 47.511 µs | 4.75 ns |

**The fold is O(1) per event.** A thousand-fold increase in history produces a ~1,300-fold increase in
time. Per-event cost drifts from 2.96 ns to 4.75 ns across two orders of magnitude — a ~60% rise that
is consistent with cache pressure over a growing list, not with a quadratic term. If `rehydrate` were
accidentally quadratic, the 10,000-event row would be near 4 ms, not 47 µs.

**This is why the benchmark is parameterised across three orders of magnitude rather than fixed at one
size.** A single number would have told us the absolute cost and nothing about the curve, and the
curve is the whole question for an event-sourced aggregate.

**The consequence is the useful part, and it is counter-intuitive.** A plausible worry about event
sourcing is that replay cost grows without bound and eventually dominates reads. Measured, replaying
**10,000 events takes 47 microseconds** — 0.0475 ms against §9.7's 20 ms cached-read budget, about
0.2% of it. Extrapolating the linear fit, an account would need roughly **four million events** before
in-memory replay alone consumed that budget.

So **in-memory replay is not the scaling risk.** Whatever makes a strong read slow at scale is the
database round trip that *fetches* those events, plus deserialisation — not the fold over them. Any
optimisation effort aimed at making `apply()` faster would be aimed at 0.2% of the budget.

### 2.2 `Money` construction is not the hidden cost of a movement

| Operation | Cost |
|---|---|
| `Money.plus` | 4.111 ns |
| `Money.minus` | 4.206 ns |
| `Money.of(String, long)` — includes `Currency.getInstance` | 4.741 ns |
| Running balance over 10 movements | 42.392 ns |

`Currency.getInstance` is a map lookup and in some JDKs takes an internal lock, and `Money.of` calls it
on **every** construction from the API layer — so it was a reasonable suspect. Measured, it costs about
**one addition**. It is not worth caching, and a `Currency` cache would add mutable shared state to a
record that is currently trivially immutable.

The running-balance chain is 42.4 ns for ten `plus` calls — 4.24 ns each, matching the single-call
figure. Allocation of the intermediate `Money` records is therefore **not** accumulating a cost; escape
analysis and the allocator are handling it. **`Money` needs no optimisation, and this row is here so
that nobody spends a day discovering that independently.**

### 2.3 The rate limiter makes §9.7's own scenario impossible to measure by default

`ledger.rate-limit.ip-backstop.capacity` is **300/minute** and `ledger.rate-limit.exempt-ips` is
**empty** in the `full` profile — it is set only in `application-standalone.properties`. A load
generator is a single source IP, so **the entire application is capped at five requests per second**
during a load run. §9.7 asks for a ramp to 500 concurrent users.

Left undiscovered, the run would have drowned in 429s and produced a report describing the rate limiter
rather than the ledger — and it would have looked like a performance failure of the system under test.

Resolved with a `load` **overlay profile** (`application-load.properties`), activated as `full,load`,
which raises the buckets. Two deliberate choices inside that:

- **Raise limits rather than exempt the generator's IP.** `exempt-ips` is an exact string match against
  `getRemoteAddr()` (`RateLimitFilter:125-132`, no CIDR), so exempting a containerised generator means
  guessing a bridge address that differs per environment. A configured number is reproducible; a
  guessed IP is not.
- **Keep the limiter in the request path.** It still buckets and still spends its Redis round trips, so
  the measurement includes the limiter's own cost — which production pays too. An exemption would have
  measured a system that does not exist.

`load` **cannot run on its own**: alone it is neither `standalone` nor `full`, so `FailClosedGuard`
refuses to boot. A profile that raises rate limits must never be capable of running without the
security posture it raises them inside. Both halves are pinned by tests, and the pinning test was
proven by deliberate violation.

---

### 2.4 The §9.7 thresholds fail today — and the assertion mechanism is proven by that failure

A Gatling run at **20 users** (not 500) against a `full,load` application:

```
write:       99th percentile of response time is less than 150.0 : false (actual : 334.0)
cached read: 99th percentile of response time is less than 20.0  : false (actual : 141.0)
Global:      percentage of failed events is less than 0.1        : true  (actual : 0.0)
[INFO] BUILD FAILURE
```

**Two separate conclusions, and conflating them would be the easy mistake.**

**The mechanism works.** A missed percentile fails the Maven build — §9.7's "thresholds are assertions,
a regression fails the pipeline" is now literally true rather than aspirational. No deliberate
sabotage was needed to demonstrate it; the thresholds failed on their own. Note also that the
error-rate assertion **passed** (`KO=0`, 0.0%) in the same run, which is what shows the three
assertions are evaluated independently rather than one failure poisoning all of them.

**The numbers are NOT a verdict on the system.** This run had the load generator, the application, and
four Docker containers competing for one developer laptop. A generator sharing a CPU with the system
it measures inflates the very percentiles it reports. Request volumes were small (44 + 124 writes) so
a p99 is drawn from roughly two samples per group, which is not a percentile in any meaningful sense.

**What this genuinely establishes:**

- The full pipeline runs end to end: token → open account → write → cached read, `KO=0` throughout.
- The assertions bite, per request name, and fail the build.
- A baseline on dedicated infrastructure is now the missing piece, not the harness (candidate 4).

**What it does not establish:** whether this ledger meets §9.7 on representative hardware. Anyone
citing `334 ms` as this system's write latency would be quoting a laptop under self-inflicted
contention, not a measurement.

---

## 3. Four defects found by running code that had only ever been read

None of these is visible in a code review. Each was found the first time something actually executed.

### 3.1 A fixed host port silently connects to the wrong database — *severity: high*

An unrelated Postgres held host port 5432, so the project's own container never bound and stayed in
`Created` while the other three services reported healthy. The application — whose datasource URL is a
fixed `localhost:5432` — connected to **the other instance**.

It surfaced only as `FATAL: password authentication failed for user "ledger"`. **Different credentials
were the sole reason it was visible.** An unrelated Postgres carrying a `ledger`/`ledger` user would
have been read and written silently, and the e2e suite would have reported five passes against
somebody else's database.

Resolved by making the port overridable (`TINY_LEDGER_PG_PORT`) and, more importantly, by a guard in
`scripts/e2e/run-e2e.sh` that refuses to start against a stack that is not fully healthy and names a
taken host port as the likely cause.

### 3.2 A 250 ms fail-open timeout fails the *boot* instead — *severity: medium, design-relevant*

`RateLimitConfig` gives its Lettuce client a **250 ms** command timeout so that rate limiting can fail
**open** during a Redis outage — a deliberate, documented availability trade.

That same timeout also gates **connection initialisation**. On a host where `localhost` resolves to
`::1` first and the IPv6 path does not route (measured: `/dev/tcp/::1/6379` times out while
`/dev/tcp/127.0.0.1/6379` is open), the result is not a degraded limiter — it is an application that
**cannot start at all**:

```
RedisConnectionException: Unable to connect to localhost/<unresolved>:6379
Caused by: RedisCommandTimeoutException: Connection initialization timed out after 250 millisecond(s)
```

The integration suite cannot see this, because Testcontainers hands out an IP address rather than a
hostname. **A timeout chosen for one purpose was silently governing another**, and the failure mode it
produces is the opposite of the one it was designed for.

### 3.3 A Spring Boot application jar is not usable as a dependency — *severity: low, cost: high*

`spring-boot-maven-plugin`'s `repackage` goal *replaces* the main artifact with a jar whose classes
live under `BOOT-INF/classes/`, a layout `javac` cannot read. Anything depending on the project
compiles against nothing and fails with `cannot find symbol: class Money`.

Resolved with the `exec` classifier, so the plain jar remains the main artifact and the runnable one is
`tiny-ledger-<version>-exec.jar`.

### 3.4 JDK 23+ silently disables classpath annotation processors — *severity: low, fails late*

The long-standing JMH recipe puts `jmh-generator-annprocess` in `provided` scope. Since JDK 23, `javac`
no longer discovers annotation processors from the classpath, so the build **succeeds** and produces a
benchmark jar containing **no benchmarks**. It fails only at run time:

```
ERROR: Unable to find the resource: /META-INF/BenchmarkList
```

Resolved with an explicit `annotationProcessorPaths`. Filed here because it will bite any future
annotation-processor dependency in this project — Lombok, MapStruct, immutables — in exactly the same
silent way.

---

## 4. Optimisation candidates — carried forward, not closed

**These are the measurement's output and must not be lost.** Each states what would trigger the work,
so none becomes speculative.

| # | Candidate | Evidence | Trigger — do this work when… | Priority |
|---|---|---|---|---|
| 1 | **Snapshot long-lived aggregates** | Replay is linear at ~4.75 ns/event; the cost is the event *fetch*, not the fold (§2.1) | An account's history exceeds ~10⁵ events **and** a strong read is measured over budget. Measure the fetch first — snapshotting the fold optimises 0.2% of the budget | Medium |
| 2 | **Measure fetch-and-deserialise separately from replay** | §2.1 shows the fold is cheap, so the remaining strong-read cost is unattributed | Before candidate 1 is scheduled. It is the measurement that tells you whether candidate 1 is even the right fix | **High — do this first** |
| 3 | **Give the Redis connection its own timeout, separate from the command timeout** | §3.2 — one 250 ms value governs both fail-open behaviour and boot | Any environment where Redis is reachable but slow to connect. The current coupling makes a deliberate availability trade into a startup failure | Medium |
| 4 | **A real percentile baseline, stored and compared run over run** | Gatling asserts thresholds but nothing records history, so a 3× regression that stays under 150 ms passes silently | The load job runs on a schedule rather than on demand | Medium |
| 5 | ~~Cache `Currency.getInstance`~~ | §2.2 — costs about one addition | **Never, on current evidence.** Recorded so the idea is not re-proposed | Closed |

Row 5 is deliberately present. A candidate list that only grows is a backlog; one that also records
what measurement *ruled out* is a finding.

---

## 5. What is asserted, and what is not

- **Asserted, and the build fails on them:** p99 write < 150 ms, p99 cached read < 20 ms, error rate
  < 0.1% — as Gatling `assertions(...)`, scoped per request name. A global p99 would be dominated by
  the cheap reads and would pass while writes regressed.
- **Not asserted:** the strong read (`?consistency=strong`). §9.7 sets no threshold for it, and
  inventing one here would be a number nobody agreed to.
- **Not asserted:** any JMH result. There is no baseline to regress against yet — see candidate 4.
  Reporting a JMH number as a gate before a baseline exists would be a gate that fails on machine
  variance.
