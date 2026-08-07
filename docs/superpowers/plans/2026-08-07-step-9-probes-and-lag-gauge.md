# Step 9, part 1 — Actuator probes and the audit-lag gauge

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.

**Goal:** Ship Actuator liveness/readiness probes and the `ledger.outbox.pending.age.seconds` gauge, and close
`E9` — the last open case in the spec's §9.3 catalogue.

**Architecture:** Actuator is added to both run modes. Readiness contains `readinessState` + `db` and
**deliberately excludes** `redis` and `kafka`, because E10 and E11 require the ledger to keep serving
while either is down (ADR 0004). The gauge reads the age of the oldest incomplete `event_publication`
row and gates nothing. `E9` is rewritten accordingly: pause the audit consumer, the gauge rises,
balances stay exact, readiness stays **UP**.

**Tech stack:** Spring Boot 4.1 Actuator, Micrometer `MeterRegistry`, `JdbcTemplate`, Testcontainers,
Awaitility.

**Authorities:** `docs/spec.md` v3.33 §6.6 (Health), §9.3 case `E9`, §9.4;
`docs/adr/0004-readiness-does-not-gate-on-lag.md`.

**Scope boundary:** tracing, OTLP export, JSON logs and the Collector are **not** in this plan. They
are parts 2 and 3 of step 9. This plan adds no OpenTelemetry dependency.

---

## Two constraints measured before writing this plan

Both are load-bearing and neither is obvious from the spec.

1. **The `full` security chain ends `.anyRequest().authenticated()`** (`SecurityConfig.java:134`). Without
   an explicit rule, `/actuator/health/liveness` would demand a bearer token. A probe that needs a
   credential fails during exactly the outage it exists to report.
2. **Both chains run `IpBackstopFilter` ahead of authentication**, charging a 300-per-60s bucket keyed on
   the client IP (`application.properties:42`). A kubelet probing every 10 s from one address would eat
   that budget and eventually be answered `429`. Actuator paths must therefore bypass both rate-limit
   filters. This also keeps `AbstractIntegrationTest`'s hand-counted 69-request IP budget
   (`:118-120`) valid — actuator calls made by the new tests must not charge it.

---

## Endpoint exposure — the assessment, not a default

Actuator's value and its risk are the same property: it reflects the running process. Deciding this by
accepting a default would be the single largest attack surface this repository has added since the
resource server, so the reasoning is recorded here rather than left in a properties line.

**Verdict: `health` only, and only its two probe groups are reachable.**

| Endpoint | Verdict | Why |
|---|---|---|
| `health/liveness`, `health/readiness` | **Open, unauthenticated** | A probe that needs a credential cannot report the outage that took the issuer away |
| `health` (root) | **Closed** | Aggregate UP/DOWN tells an unauthenticated caller when the system is degraded — useful for timing an attack, useless to anyone else |
| `heapdump` | **Never** | Dumps live process memory: balances, bearer tokens, the Redis password. The worst single endpoint in the set |
| `env`, `configprops` | **Never** | Renders configuration including `issuer-uri` and the datasource URL. A direct §6.5 violation |
| `loggers` | **Never** | `POST` mutates log level at runtime — a write operation that could switch on payload logging |
| `httpexchanges` | **Never** | Recent request/response history, i.e. PII, which §6.6 requires logs not to carry |
| `threaddump` | **Never** | Stack traces and internal paths — §6.5 forbids leaking these even from `/error` |
| `beans`, `mappings`, `conditions` | **Never** | Internal structure; §6.5's "no internal identifier crosses the API boundary" |
| `caches` | **Never** | Remote eviction of the balance cache |
| `shutdown` | **Never** | Remote kill. Off by default — stated here so that nobody turns it on believing it was an oversight |
| `liquibase`, `auditevents`, `sbom`, `startup`, `scheduledtasks` | **Closed** | No operational need in this system |
| `metrics`, `prometheus` | **Closed** | Metrics leave over OTLP in part 2. A scrape endpoint is a second path to the same data and a second thing to secure |

**Two independent layers, and both are load-bearing:**

1. `management.endpoints.web.exposure.include=health` — anything else is never web-mapped at all.
2. `denyAll` on `/actuator/**` in the security chain, with only the two probe paths permitted.

Layer 1 is a configuration line someone will eventually edit. Layer 2 is why that edit stays harmless:
exposing an endpoint by configuration does not open it to any valid token. Neither alone is sufficient.

**Health detail is `never`, for everyone.** The probe body is `{"status":"UP"}` and nothing more. No
caller — authenticated or not — sees which component is down. Which component *is* down is answerable
from `ledger.outbox.pending.age.seconds` and the logs, by someone who already has access to them. The rejected
alternative was `show-details=when-authorized` behind a new `ledger:operator` role: a fourth role, a
Keycloak realm change and new authorization tests, for detail that is already available to the people
who would act on it.

**A separate management port, built now.** Probes bind to `management.server.port`, unpublished and
reachable from inside the network only, so a misconfigured endpoint is *unreachable* rather than merely
denied. An earlier draft of this plan deferred it behind the trigger "revisit at deployment to an
orchestrator" — **that trigger fired**: ADR 0005 makes Kubernetes the production target, so the port
split is built rather than carried as debt. Changing the security model late is the riskiest kind of
late change.

It costs **one Spring context fork**: MockMvc cannot reach a second port, so the probe tests need a
real one. `AGENTS.md` trap 5 requires a written reason for a fork and ADR 0005 carries it. This is the
only fork this plan introduces — do not add a second by reaching for `@TestPropertySource`.

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `pom.xml` | `spring-boot-starter-actuator` | Modify |
| `src/main/resources/application.properties` | Endpoint exposure, probes on, base readiness group | Modify |
| `src/main/resources/application-full.properties` | Readiness group gains `db` | Modify |
| `src/main/java/…/config/SecurityConfig.java` | Probe paths permitted, rest of actuator denied | Modify |
| `src/main/java/…/platform/RateLimitFilter.java` | Skip `/actuator/**` | Modify |
| `src/main/java/…/platform/IpBackstopFilter.java` | Skip `/actuator/**` | Modify |
| `src/main/java/…/platform/AuditLagGauge.java` | Reads oldest incomplete publication, registers the gauge | **Create** |
| `src/main/java/…/config/FullAdapterConfig.java` | Constructs the gauge — `full` only | Modify |
| `src/test/java/…/platform/ActuatorProbeTest.java` | Probes reachable unauthenticated, metrics denied | **Create** |
| `src/test/java/…/observability/AuditLagIT.java` | `E9` | **Create** |
| `docs/adr/0004-readiness-does-not-gate-on-lag.md` | Gains the endpoint-exposure decision and the separate-management-port upgrade path | Modify |

---

## Task 1: Actuator on, exposure measured and locked to `health`

**Estimated: 35 minutes** — the endpoint enumeration in Step 4 is most of the extra.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-full.properties`

- [ ] **Step 1: Add the dependency**

In `pom.xml`, beside the other `spring-boot-starter-*` entries (near line 71):

```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
```

- [ ] **Step 2: Base configuration**

Append to `src/main/resources/application.properties`:

```properties
# spec §6.6 / ADR 0004, and see this plan's "Endpoint exposure" assessment for the per-endpoint
# reasoning. Health ONLY: heapdump would dump balances and bearer tokens, env and configprops would
# render the issuer-uri and datasource URL, loggers is a runtime write, httpexchanges is PII. This is
# a bearer-token API with no browser surface, so every additional endpoint is attack surface bought
# for nothing. Metrics leave over OTLP in part 2 of step 9, deliberately not over a scrape endpoint —
# that would be a second path to the same data and a second thing to secure.
#
# This line is layer 1 of 2: anything absent here is never web-mapped. SecurityConfig's denyAll on
# /actuator/** is layer 2, and it is what keeps a future edit to THIS line from silently opening an
# endpoint to any valid token. Do not treat either as redundant.
management.endpoints.web.exposure.include=health
management.endpoint.health.probes.enabled=true
# Never, for every caller, authenticated or not: the probe body is {"status":"UP"} and nothing more.
# Which component is down is answerable from ledger.outbox.pending.age.seconds and the logs, by people who
# already have access to them. `when-authorized` behind a new ledger:operator role was considered and
# rejected — a fourth role and a realm change for detail those people can already reach.
management.endpoint.health.show-details=never
management.endpoint.health.show-components=never
# The readiness group is declared EXPLICITLY, and the omissions are the point. Boot auto-configures a
# `redis` and a `kafka` indicator, and a default group would include them — which would contradict E10
# and E11 (the ledger must keep serving, and ?consistency=strong must stay exact, while either is
# down). `standalone` has no datasource, so the base value is readinessState alone;
# application-full.properties adds `db`. See ADR 0004.
management.endpoint.health.group.readiness.include=readinessState
management.endpoint.health.group.liveness.include=livenessState

# ADR 0005: Kubernetes is the production target. Probes bind to their own port, unpublished, so a
# misconfigured endpoint is unreachable rather than merely denied. 0 is NOT used here — a fixed port
# is what a manifest references; the tests override it to 0 for a random free port.
management.server.port=9090

# ADR 0005, and this is a CORRECTNESS property for a ledger rather than an operational nicety. On
# SIGTERM the instance must leave the load balancer before the listener stops. Without graceful
# shutdown, in-flight writes die mid-request during an ordinary rolling deploy or scale-down. Boot
# flips readiness to OUT_OF_SERVICE on shutdown, which is what the orchestrator reads.
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=25s

# ADR 0005: without these, twenty replicas emit one indistinguishable stream and "which instance is
# slow" has no answer. Environment-sourced, never hardcoded — §1.5 and .env.example gain the names.
# These are read by the OTLP exporters in part 2 of step 9; declaring them now means part 2 adds an
# exporter rather than an identity model, and no dashboard is ever built against unlabelled data.
spring.application.name=${LEDGER_SERVICE_NAME:tiny-ledger}
management.observations.key-values.service.namespace=${LEDGER_SERVICE_NAMESPACE:local}
management.observations.key-values.service.instance.id=${LEDGER_INSTANCE_ID:${random.uuid}}
```

**Verify the three `management.observations.key-values.*` names against the shipped jar in Step 5** —
Boot's common-tags property has moved between versions, and this plan was written without booting it.
If they do not take effect, the correct mechanism is an `ObservationRegistry` customiser or an
`OpenTelemetryResourceAutoConfiguration` contribution; fix it here and correct this plan in the same
commit.

- [ ] **Step 3: Add `db` to readiness in `full` only**

Append to `src/main/resources/application-full.properties`:

```properties
# spec §6.6: readiness gates on event-store reachability, and in `full` the event store IS Postgres.
# `redis` and `kafka` stay out deliberately — ADR 0004.
management.endpoint.health.group.readiness.include=readinessState,db
```

- [ ] **Step 4: Measure the real endpoint surface before trusting the assessment**

The exposure table above was written from knowledge of Boot's endpoint set, not from this classpath.
Boot's endpoints shift between versions, and a decision about attack surface should be made against
what this application actually maps. Enumerate it — **on a throwaway commit you will revert**:

```bash
# TEMPORARY — do not commit this line
management.endpoints.web.exposure.include=*
```

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=standalone &
sleep 25
curl -s http://127.0.0.1:8080/actuator | python -c "import json,sys; print('\n'.join(sorted(json.load(sys.stdin)['_links'])))"
kill %1
```

Record the output in the commit message. **Compare it against the assessment table**: any endpoint
listed there that does not appear is fine, but any endpoint that appears and is *not* in the table is
an unassessed one — decide it explicitly and add a row, rather than letting `include=health` hide it
by luck. `git checkout` the properties file afterwards.

- [ ] **Step 5: Verify the property names against the shipped jar rather than trusting this plan**

With exposure back to `health`:

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=standalone &
sleep 25
for p in health/liveness health/readiness health metrics env heapdump; do
  printf '%s -> ' "$p"
  curl -s -o /dev/null -w '%{http_code}\n' "http://127.0.0.1:8080/actuator/$p"
done
kill %1
```

Expected: `200`, `200`, then `404` for the remaining four. **A `404` on either probe means the
property names are wrong, not that the feature is absent** — fix them here before continuing, and
correct this plan's Step 2 in the same commit so the next reader is not misled. Note that
`/actuator/health` returning 404 at this point is layer 1 doing its job; Task 2 adds layer 2 so it
stays closed even if layer 1 is later widened.

- [ ] **Step 6: Run the fast gate**

Run: `./mvnw -q verify`
Expected: exit 0. Actuator on the classpath must not break `FailClosedGuard` or any existing test.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/resources/application-full.properties
git commit -m "feat(observability): Actuator with liveness and readiness probes

Health only — no metrics, env or beans over HTTP. The readiness group is
declared explicitly because Boot's default would pull in the redis and kafka
indicators, which contradicts E10 and E11: the ledger must keep serving while
either is down. ADR 0004."
```

---

## Task 2: Probes reachable without a token; everything else under `/actuator` closed

**Estimated: 45 minutes** — the two independent red proofs in Step 5 are worth the time; they are what
separate a real security rule from a test that passes because the feature was never switched on.

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java:108`
- Test: `src/test/java/com/ffroliva/tinyledger/platform/ActuatorProbeTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/ffroliva/tinyledger/platform/ActuatorProbeTest.java`.

**This class forks the Spring context, deliberately and once.** MockMvc has no port and cannot reach
the management listener, so this is the one test that needs a real server. `AGENTS.md` trap 5 requires
a written reason; ADR 0005 is it. Put the reason in the class javadoc so the next reader does not
"fix" it by folding it back into `AbstractIntegrationTest`.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "management.server.port=0")
@ActiveProfiles("standalone")
class ActuatorProbeTest {
    @LocalManagementPort int managementPort;
    @LocalServerPort int apiPort;
    private final RestClient http = RestClient.create();
}
```

`management.server.port=0` takes a random free port so parallel runs cannot collide;
`@LocalManagementPort` reads back which one. Assert on status codes via `RestClient`'s
`onStatus((s) -> true, (req, res) -> {})` so a 4xx is returned rather than thrown.

Run this under `standalone`: it starts no containers, so it stays on the fast `verify` path
(ADR 0003) — the management chain is profile-independent, so `full` would prove nothing extra here.

```java
/**
 * Spec §6.6 / ADR 0004: the probes must answer without a credential, and nothing else under
 * /actuator may be reachable at all. A probe that needs a bearer token fails during exactly the
 * outage it exists to report.
 */
@Test
void theLivenessProbeAnswersWithoutACredential() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
}

@Test
void theReadinessProbeAnswersWithoutACredential() throws Exception {
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
}

/**
 * Layer 2 of the exposure decision. These are unreachable today because `exposure.include=health`
 * never web-maps them — but that is one properties line, and this test is what makes widening it a
 * visible, deliberate act rather than a silent one. Each name here is an endpoint the plan's exposure
 * assessment rejected for a specific reason: heapdump renders balances and bearer tokens, env and
 * configprops render the issuer-uri and datasource URL, loggers is a runtime write, httpexchanges is
 * PII, threaddump is the stack traces §6.5 forbids leaking from /error.
 *
 * <p>If you are here because this test failed after you exposed an endpoint: that is the test working.
 * Add the endpoint to the assessment table in the plan with its reasoning, or do not expose it.
 */
@ParameterizedTest
@ValueSource(strings = {
    "health", "metrics", "prometheus", "env", "configprops", "beans", "mappings",
    "heapdump", "threaddump", "loggers", "httpexchanges", "auditevents", "caches",
    "conditions", "shutdown", "liquibase", "sbom", "startup", "scheduledtasks"
})
void noOtherActuatorEndpointIsReachable(String endpoint) throws Exception {
    mockMvc.perform(get("/actuator/" + endpoint))
           .andExpect(status().is(not(200)));
}
```

Assert **not-200** rather than a specific code: layer 1 answers `404` (never mapped) and layer 2
answers `403` (mapped but denied), and which one replies is exactly the implementation detail this
test should not pin. Pinning `404` would turn the security rule into a passing test that proves only
that the endpoint was never enabled.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q test -Dtest=ActuatorProbeTest`

Expected under the `full` chain: **401**, not 200 — `.anyRequest().authenticated()` catches the probe.

**Confirm the run actually executed this class.** `-Dtest` matching nothing exits **0**
(`AGENTS.md` trap 4). Check `target/surefire-reports/*ActuatorProbeTest.xml` exists and names the three
methods before believing either a red or a green.

- [ ] **Step 3: Add a management chain — do not add matchers to the API chains**

Because the endpoints are on their own port (ADR 0005), the rule belongs on a chain scoped to that
port, not on `fullChain`. Adding `/actuator/**` matchers to the API chains would be wrong twice: they
would never match (different port) while *reading* as though they were the protection.

Add to `SecurityConfig`, profile-independent — the port and its posture are the same in both run modes:

```java
/**
 * Spec §6.6 / ADR 0005. A chain scoped to the management port, ordered ahead of the API chains so it
 * claims those requests first. The two probes are the only unauthenticated routes; everything else is
 * denied outright rather than left to `authenticated()`, so exposing a new endpoint by configuration
 * cannot quietly open it to any valid token — the second of the two layers, the first being
 * `management.endpoints.web.exposure.include=health`.
 *
 * <p>A probe that needs a bearer token cannot report the outage that took the token issuer away, which
 * is why permitAll here is the secure choice rather than the lax one: the port is not published.
 */
@Bean
@Order(0)
SecurityFilterChain managementChain(HttpSecurity http) {
    return http.securityMatcher(EndpointRequest.toAnyEndpoint())
            .csrf(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                    .anyRequest().denyAll())
            .build();
}
```

`EndpointRequest.to(HealthEndpoint.class)` matches the health endpoint **and its groups**, which is
what makes the two probe paths reachable. Confirm that against the shipped jar in Step 4 — if it
matches only the root, replace it with explicit `requestMatchers` for the two group paths and permit
the root deliberately or not at all.

The `standalone` and `full` chains need **no change**: neither now sees actuator traffic at all.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./mvnw -q test -Dtest=ActuatorProbeTest`
Expected: PASS — 2 probe tests plus one case per entry in the `@ValueSource`. Verify the count from
`target/surefire-reports/TEST-…ActuatorProbeTest.xml`, paired with the exit code (`AGENTS.md` trap 3).
A `@ParameterizedTest` that silently received an empty source would also report green.

- [ ] **Step 5: Prove each layer independently — one red run is not enough here**

The two probe tests and the exposure test fail for different reasons, and a single revert only proves
one of them.

**Layer 2 (`denyAll`) is the one at risk of being vacuous**, because `exposure.include=health` already
makes every endpoint in the `@ValueSource` return 404. Prove it is doing work:

```properties
# TEMPORARY
management.endpoints.web.exposure.include=*
```

Re-run with the `denyAll` matcher **removed**. Expected: `noOtherActuatorEndpointIsReachable` fails on
many entries — `env`, `heapdump`, `configprops` and the rest now answer 200. Restore `denyAll`, keep
`include=*`, re-run: expected **green**, every entry now 403 rather than 404. That green is the proof
layer 2 stands on its own. Then restore `include=health`.

**Layer permit:** delete the two `permitAll` matchers, re-run, confirm the probe tests go 401, restore.

Record both run results in the commit message. A test that cannot fail is not coverage
(`AGENTS.md` trap 4), and here the same file holds one test that could not fail for a reason the other
tests do not share.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java \
        src/test/java/com/ffroliva/tinyledger/platform/ActuatorProbeTest.java
git commit -m "feat(observability): probes answer unauthenticated, rest of /actuator denied

denyAll() rather than leaving the rest to anyRequest().authenticated(), so
exposing a new endpoint by configuration later cannot quietly open it to any
valid token."
```

---

## Task 3: Prove probes are not rate limited

**Estimated: 15 minutes.** This task shrank when the port split landed — **read this before writing
code**, because an earlier draft of this plan had you editing two filters that no longer see the
traffic.

`RateLimitFilter` and `IpBackstopFilter` are **not** beans. `SecurityConfig`'s javadoc records why:
exposing them as `@Component` would also trigger Boot's filter auto-registration and run the check
twice per request. They are constructed inside the two API chains and added with `addFilterBefore`.

The management chain adds neither. So actuator traffic on the management port is already unmetered, and
**no `shouldNotFilter` override is needed** — the port split removed the problem instead of working
around it. What remains is proving that, because it is currently an inference from how the chains are
built rather than an observed fact.

**Files:**
- Test: `src/test/java/com/ffroliva/tinyledger/platform/ActuatorProbeTest.java` (extend)

- [ ] **Step 1: Write the test**

`ledger.rate-limit.ip-backstop.capacity` is 300/60s. A kubelet on a 10-second interval would cross that
in under an hour, and a rate-limited liveness probe reads to the orchestrator as an unhealthy instance —
it would restart a process that was working.

```java
/**
 * Spec §6.1 vs §6.6: a kubelet probing every 10s from one address would exhaust the 300-per-60s IP
 * backstop and be answered 429 — the orchestrator would then restart a healthy instance because its
 * probe was rate limited. Probes are therefore not charged at all.
 */
@Test
void probesAreNotChargedToTheRateLimitBuckets() throws Exception {
    for (int i = 0; i < 320; i++) {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q test -Dtest=ActuatorProbeTest#probesAreNotChargedToTheRateLimitBuckets`
Expected: FAIL — a `429` once the loop passes the backstop capacity.

- [ ] **Step 3: Skip actuator in both filters**

Both classes extend `OncePerRequestFilter`. Override `shouldNotFilter` in each rather than adding a
branch inside `doFilterInternal` — the framework hook says *this filter does not apply here*, which is
the claim being made:

```java
/**
 * Spec §6.6: probe traffic is not client traffic. A liveness probe on a 10s interval from a single
 * orchestrator address would otherwise consume the §6.1 IP backstop and be refused 429, which reads
 * to the orchestrator as an unhealthy instance and restarts a process that was working.
 */
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/actuator/");
}
```

Add the identical override to `IpBackstopFilter`.

- [ ] **Step 4: Run the test and watch it pass**

Run: `./mvnw -q test -Dtest=ActuatorProbeTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Confirm nothing else moved**

Run: `./mvnw -q verify`
Expected: exit 0. `RateLimitIT` and the budget arithmetic in `AbstractIntegrationTest:95-120` are
untouched, because actuator requests now charge nothing — this **relaxes** the budget and cannot
overspend it.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ffroliva/tinyledger/platform/RateLimitFilter.java \
        src/main/java/com/ffroliva/tinyledger/platform/IpBackstopFilter.java \
        src/test/java/com/ffroliva/tinyledger/platform/ActuatorProbeTest.java
git commit -m "fix(observability): actuator bypasses both rate-limit filters

A 10s liveness probe from one orchestrator address would consume the 300/60s
IP backstop and be answered 429 — which reads as an unhealthy instance and
restarts a process that was working."
```

---

## Task 4: The `ledger.outbox.pending.age.seconds` gauge

**Estimated: 40 minutes.**

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/platform/AuditLagGauge.java`
- Modify: `src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java`

- [ ] **Step 1: Write the class**

There is no unit test worth writing here — the whole behaviour is one SQL query, and a test with the
query mocked would assert that the mock returns what it was told to. It is proven by `AuditLagIT` in
Task 5, against a real Postgres, which is where the query can actually be wrong.

```java
package com.ffroliva.tinyledger.platform;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spec §6.6 / ADR 0004: the age of the oldest event publication that has not completed — the lag
 * between a ledger write and the audit trail catching up with it.
 *
 * <p><strong>Nothing gates on this.</strong> It is a gauge and an alerting input, deliberately. The
 * readiness probe does not consume it: gating on this value would take an instance out of service
 * during exactly the Kafka outage E11 requires the ledger to survive. ADR 0004 has the reasoning and
 * the rejected alternatives.
 *
 * <p>Note what this is <em>not</em>: it is not projection lag. The balance projection is a synchronous
 * {@code @EventListener} on the publishing thread inside the write transaction (§4.3), so its lag is
 * structurally zero and no gauge could ever read anything but zero from it. This measures the Kafka
 * leg, which makes the audit trail stale rather than balances.
 *
 * <p>{@code full} only — {@code standalone} has no {@code event_publication} table (migration 004).
 */
public class AuditLagGauge {

    /**
     * COALESCE, not an empty result: with nothing outstanding the aggregate returns one NULL row, and
     * an unhandled null would register the gauge as NaN — which graphs as a gap and alerts as nothing.
     * Zero is the truthful reading for "no publication is waiting".
     */
    private static final String OLDEST_INCOMPLETE = """
            SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(publication_date))), 0)
            FROM event_publication
            WHERE completion_date IS NULL
            """;

    private final JdbcTemplate jdbc;

    public AuditLagGauge(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("ledger.outbox.pending.age.seconds", this, AuditLagGauge::lagSeconds);
    }

    /** Package-private for the IT to read directly without going through the registry. */
    double lagSeconds() {
        Double seconds = jdbc.queryForObject(OLDEST_INCOMPLETE, Double.class);
        return seconds == null ? 0.0 : seconds;
    }
}
```

- [ ] **Step 2: Construct it in the composition root**

`AuditLagGauge` lives in `platform` and touches an outbound adapter, so per the ArchUnit rule in
`AGENTS.md` only `config` may construct it. Add to `FullAdapterConfig` — which is already
`@Profile("full")`, so the `standalone`/no-table case needs no extra guard:

```java
@Bean
AuditLagGauge auditLagGauge(JdbcTemplate jdbc, MeterRegistry registry) {
    return new AuditLagGauge(jdbc, registry);
}
```

- [ ] **Step 3: Confirm the architecture rules still pass**

Run: `./mvnw -q verify`
Expected: exit 0, `HexagonalRulesTest` green. If it reddens, the bean was placed outside `config` —
move it rather than relaxing the rule.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ffroliva/tinyledger/platform/AuditLagGauge.java \
        src/main/java/com/ffroliva/tinyledger/config/FullAdapterConfig.java
git commit -m "feat(observability): ledger.outbox.pending.age.seconds gauge

The age of the oldest incomplete event publication. Nothing gates on it — see
ADR 0004. This is not projection lag: the balance projection is synchronous on
the write thread, so its lag is structurally zero."
```

---

## Task 5: `E9` — lag is visible and does not gate

**Estimated: 60 minutes.** This is the task that closes the last open case in §9.3.

**Files:**
- Create: `src/test/java/com/ffroliva/tinyledger/observability/AuditLagIT.java`

- [ ] **Step 1: Write the failing test**

Extend `AbstractIntegrationTest` — do **not** add `@TestPropertySource` or `@DynamicPropertySource`,
which fork the shared context (`AGENTS.md` trap 5, ADR 0003).

Pause the consumer by pausing the **Kafka container**, matching how `KafkaOutageIT` already produces
this condition; reuse its mechanism rather than inventing a second one.

```java
/**
 * Spec §9.3 case E9 — "Lag is visible and does not gate."
 *
 * <p>Rewritten at spec v3.32. The original case asked the readiness probe to shed traffic on
 * projection lag; that cannot happen here, because the balance projection is synchronous on the
 * publishing thread (§4.3) and its lag is structurally zero. This asserts the behaviour the system
 * actually has, and that it is the behaviour we want: the audit trail falls behind, and the ledger
 * keeps serving. ADR 0004.
 */
@Test
void lagIsVisibleAndReadinessStaysUp() throws Exception {
    // given: a healthy baseline
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

    pauseKafka();
    try {
        // when: writes continue while the audit leg cannot drain
        var accountUid = openAnAccountAs(ALICE);
        depositAs(ALICE, accountUid, 5_000);

        // then: the gauge rises past the 5s threshold — never Thread.sleep (§9.3 method rule)
        await().atMost(Duration.ofSeconds(30))
               .untilAsserted(() -> assertThat(auditLagGauge.lagSeconds()).isGreaterThan(5.0));

        // and: balances are exact, because the projection never depended on Kafka.
        //
        // consistency=strong is deliberate and must NOT be dropped to "simplify" this test. A plain
        // cached read is not guaranteed exact here: BalanceProjector:20-32 evicts inside the open
        // append transaction, so a read racing the commit can repopulate the cache with the pre-write
        // balance for up to the 60s TTL (§6.2, ADR 0004's correction section). That window is real,
        // bounded and unrelated to Kafka — asserting a cached read would make this test flaky for a
        // reason that has nothing to do with E9.
        mockMvc.perform(get("/api/v1/accounts/" + accountUid + "/balance?consistency=strong").with(ALICE))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.amount.minorUnits").value(5_000));

        // and: readiness stays UP — the whole point of E9's rewrite. An instance that removed itself
        // here would fail E11, which requires the ledger to survive exactly this outage.
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    } finally {
        unpauseKafka();
    }
}
```

Read `KafkaOutageIT` before writing `pauseKafka()`/`unpauseKafka()` and **call its existing helper if
one exists** — a second pause mechanism in the same suite is the duplication §9.2b exists to prevent.

- [ ] **Step 2: Run it and watch it fail**

`-Pit` is CI-only (`AGENTS.md`): push the branch to a **draft PR** and read the result. A branch with
no open PR gets no CI at all.

```bash
git push -u origin step-9-probes
gh pr create --draft --title "feat: step 9 part 1 — probes and the audit-lag gauge" --body "…"
gh run watch
```

Expected first failure: the gauge never exceeds 5.0, because `AuditLagGauge` is not yet injected into
the test. Wire it, re-push.

- [ ] **Step 3: Prove the assertion can fail — the readiness half especially**

The readiness assertion is the one at risk of being vacuous: it would also pass if readiness were
hard-coded UP, or if the group were empty. Prove it discriminates by temporarily adding `kafka` to the
readiness group:

```properties
management.endpoint.health.group.readiness.include=readinessState,db,kafka
```

Push. Expected: `lagIsVisibleAndReadinessStaysUp` **fails** on the final assertion with `503`, because
the paused broker now makes the instance not-ready. **Revert the property.** This is the red proof that
the exclusion in Task 1 is load-bearing rather than decorative, and it is the evidence ADR 0004's
central claim rests on — record the run URL in the commit message.

- [ ] **Step 4: Confirm green, counted from XML**

`gh run view --log-failed` on failure. On success, read the integration count from the uploaded
failsafe XML paired with the run's conclusion (`AGENTS.md` trap 3) — a build that fails early leaves
the previous run's XML on disk reporting green.

- [ ] **Step 5: Update the traceability sweep**

Run the §9.3 command and confirm `E9` no longer appears:

```bash
comm -23 \
  <(grep -ohE "^\| (P|N|E)[0-9]+" docs/spec.md | tr -d '| ' | sort -u) \
  <(grep -rhoE "\b(P|N|E)[0-9]{1,2}\b" src/test ledger-cli/tests scripts/e2e | sort -u)
```

Expected: **empty output.** That is the whole catalogue covered for the first time.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/ffroliva/tinyledger/observability/AuditLagIT.java
git commit -m "test: E9 — lag is visible and readiness stays up

Closes the last open case in the §9.3 catalogue; the traceability sweep now
prints nothing.

Proven by deliberate violation: adding `kafka` to the readiness group makes
this test fail 503 on its final assertion, which is what shows the exclusion
in application.properties is load-bearing rather than decorative. Run: <url>"
```

---

## Task 6: Make the documents true

**Estimated: 25 minutes.** Nothing enforces this — no gate in this repository checks documentation
(§8.4). It is a step in the plan precisely because nothing will remind you.

**Files:**
- Modify: `docs/spec.md` (§9.3 known-open, §14 step 9, version + revision row)
- Modify: `README.md:156`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: `docs/spec.md` — the catalogue is now complete**

Replace the "Known-open as of v3.32: `E9` alone" paragraph. The catalogue has **no open cases** for the
first time. Say that, and say that step 9 is partially delivered — probes and the gauge are in, tracing
and the Collector are not.

- [ ] **Step 2: `README.md:156`**

It currently reads "**Not yet built:** observability, and the FAPI/DPoP work." Observability is now
partly built. Narrow the claim to what is still absent — tracing, OTLP export, the Collector — rather
than deleting the line.

- [ ] **Step 3: `CHANGELOG.md`** — an entry for the probes, the gauge, and `E9` closing.

- [ ] **Step 4: Bump the spec version and add a revision row.**

`grep -n 'Version:' docs/spec.md` — and check whether the *known-divergences* label should move. It
should **not** unless you have re-audited those three rows; bumping it otherwise claims an audit that
did not happen (v3.32's revision row records this).

- [ ] **Step 5: Update `docs/INDEX.md`'s spec version.** Nothing reminds you; the table is
      hand-maintained and it has been stale by twenty revisions before.

- [ ] **Step 6: Extend ADR 0004 with the endpoint-exposure decision**

ADR 0004 currently records only why readiness does not gate on lag. Add a second decision section
carrying the exposure assessment — the per-endpoint verdicts, the two-layer argument, `show-details=never`
and its rejected `ledger:operator` alternative — so the reasoning lives somewhere a reader meets it
rather than only in an archived plan. Plans are not contract; ADRs are.

Include **the separate management port as the recorded upgrade path**, with the trigger stated: the
stronger topology is `management.server.port` on an unpublished internal port, so a misconfigured
endpoint is unreachable rather than merely denied. It is not built now because the probe tests would
need a real port rather than MockMvc, forking the shared context (trap 5, ADR 0003), and the app is
still started by hand. **The trigger is deployment to an orchestrator** — at that point the decision
should be revisited rather than inherited.

Also update §6.6's health subsection in `docs/spec.md` to name the exposure posture in one line and
route to ADR 0004 for the reasoning. The spec states the contract; the ADR carries the argument.

- [ ] **Step 7: Commit, then mark the PR ready for review.**

---

## Self-review against the spec

| Spec requirement (v3.33 §6.6) | Task |
|---|---|
| Endpoint exposure assessed per endpoint, not defaulted | Assessment section + 1 (measured) + 2 (enforced) |
| Dangerous endpoints unreachable through two independent layers | 1 (exposure) + 2 (denyAll), each proven red separately |
| Health detail withheld from every caller | 1 |
| Liveness and readiness separate | 1 |
| Readiness = `readinessState` + `db`; `redis`/`kafka` excluded | 1, proven by violation in 5 |
| Probes reachable without a credential | 2 |
| `ledger.outbox.pending.age.seconds`, `full` only | 4 |
| Nothing gates on lag | 5 |
| 2 s / 5 s as alerting thresholds with no gate consuming them | 5 asserts the 5 s reading; no gate is added, which is the requirement |
| `E9` rewritten and closed | 5 |

**Deliberately out of scope, and named so it is not mistaken for an omission:** spans, metrics export,
JSON logs, sampling, the Collector, and the §9.4 `InMemorySpanExporter` assertions. Those are step 9
parts 2 and 3. This plan adds no OpenTelemetry dependency, so `management.otlp.*` does not appear in it.
