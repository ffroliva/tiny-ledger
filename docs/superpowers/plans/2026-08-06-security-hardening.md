# Security hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four security gaps spec v3.10 records as open — an unvalidated `x-fapi-interaction-id` that forges log lines, Boot's `/error` leaking the request path, no token audience validation, and a rate limiter that §6.1 describes at length and `src/main` does not contain — plus the CI security stage that spec §12.1 specifies and CI does not run.

**Architecture:** Everything lands in `platform` (framework guards) or `config` (the composition root). No domain change, no application-layer change, no new module. Three of the five are single filters or single properties; rate limiting is the one real build, and it is deliberately last.

**Tech Stack:** Java 25, Spring Boot 4.1.0 → **Spring Security 7.x**, Bucket4j, Redis (`lettuce`, already a dependency), Testcontainers, GitHub Actions.

## Global Constraints

- **Boot 4.1.0 brings Spring Security 7.x**, not 6.5. Check every Security API against 7.x; 6.x documentation dominates training data and this repository has been wrong six times reasoning about frameworks and right every time it measured.
- **Never run `./mvnw -Pit` locally.** Push and read CI (`gh run watch`, `gh run view --log-failed`). `./mvnw -q verify` stays local — fast, zero containers.
- **Never run two Maven builds in this tree.**
- **`verify` must start ZERO containers.** Load-bearing: it is what lets the no-Docker CI job exist (ADR 0003).
- **One Spring test context.** Add to `AbstractIntegrationTest`'s single `@DynamicPropertySource`; a per-class `@Import`/`@TestConfiguration`/`@TestPropertySource` forks the `full` context. **`missCount = 1`.**
- **Baseline, measured in CI at `654ee56` with a green conclusion: 154 unit / 48 integration.** State any count with its run conclusion; a count alone is not evidence.
- **Prove every gate by deliberately violating it.** `-Dtest` takes **commas**; a pattern matching nothing exits **0**.
- **Both run modes must keep working.** §9.2b: if a change makes `standalone` and `full` differ, that is a defect unless the spec says otherwise. Rate limiting is explicitly *specified* to differ (Redis-backed in `full`, in-memory in `standalone`).
- Commit per logical change, explicit pathspecs. **Never `git add -A`.** Push freely; **never merge**.
- **`git commit -F - <<'EOF' … EOF` bash heredoc.** Never a PowerShell here-string. Verify with `git log -1 --format='%s'`. **Do not pipe Maven through `tail`/`head` when you need its exit code** — `$?` reports the pipe's.

## What is actually there — measured at `654ee56`, do not re-derive

| Gap | Measured state |
|---|---|
| Rate limiting | **`ErrorCode.java:21` `RATE_LIMIT_EXCEEDED(429, …)` is the only occurrence in `src/main`.** No producer, no filter, no Bucket4j dependency |
| `aud` | `application-full.properties:15` configures `issuer-uri` **only**. No audience validator anywhere |
| `x-fapi-interaction-id` | `FapiInteractionIdFilter:40-44` takes `request.getHeader(HEADER)` and writes it **verbatim** into both the response header and the MDC. No validation, no length bound |
| `/error` | No `ErrorController` implementation, no `ErrorMvcAutoConfiguration` exclusion, no `server.error.*` property. **Boot's `BasicErrorController` is live** |
| CI security stage | Spec §12.1 specifies twelve stages; `.github/workflows/ci.yml` implements seven. **Stage 11 — `gitleaks`, `detect-secrets`, Trivy, `dependency-check` — does not run on any push** |

## File Structure

**Created**
- `src/main/java/com/ffroliva/tinyledger/platform/RateLimitFilter.java`
- `src/main/java/com/ffroliva/tinyledger/platform/RateLimitProperties.java`
- `src/main/java/com/ffroliva/tinyledger/config/RateLimitConfig.java` — the two bucket suppliers, per profile
- `src/test/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilterTest.java`
- `src/test/java/com/ffroliva/tinyledger/platform/RateLimitFilterTest.java`
- `src/test/java/com/ffroliva/tinyledger/config/RateLimitIT.java`
- `src/test/java/com/ffroliva/tinyledger/config/AudienceValidationIT.java`

**Modified**
- `FapiInteractionIdFilter.java` (Task 1), `SecurityConfig.java` (Task 3), `application-full.properties` (Task 3), `application.properties` + `application-standalone.properties` (Task 4), `pom.xml` (Task 4), `.github/workflows/ci.yml` (Task 5), `docs/spec.md` (Task 5).

---

### Task 1: Bound and validate `x-fapi-interaction-id`

First because it is a **live log-forging vector** and is self-contained.

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilter.java:40-44`
- Create: `src/test/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilterTest.java`

**Interfaces:** Produces nothing other tasks consume.

- [ ] **Step 1: See the defect before fixing it**

```bash
sed -n '34,50p' src/main/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilter.java
```

Expected: `supplied` goes straight into `response.setHeader` and `MDC.put`. **A value containing `\n` writes a second log line**, and an unbounded value is a memory and log-volume amplifier. FAPI requires an RFC 4122 UUID.

- [ ] **Step 2: Write the failing tests**

```java
class FapiInteractionIdFilterTest {

    private final FapiInteractionIdFilter filter = new FapiInteractionIdFilter();

    private String echoed(String supplied) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (supplied != null) {
            request.addHeader(FapiInteractionIdFilter.HEADER, supplied);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getHeader(FapiInteractionIdFilter.HEADER);
    }

    @Test
    void aValidUuidIsEchoedUnchanged() throws Exception {
        String supplied = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
        assertThat(echoed(supplied)).isEqualTo(supplied);
    }

    @Test
    void aNewlineBearingValueIsReplacedNotEchoed() throws Exception {
        String forged = "abc\nWARN  forged log line";
        assertThat(echoed(forged)).doesNotContain("\n").isNotEqualTo(forged);
    }

    @Test
    void anOverlongValueIsReplaced() throws Exception {
        assertThat(echoed("x".repeat(4096))).hasSize(36);
    }

    @Test
    void aNonUuidValueIsReplacedWithAMintedUuid() throws Exception {
        assertThat(echoed("not-a-uuid")).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void anAbsentHeaderStillMints() throws Exception {
        assertThat(echoed(null)).hasSize(36);
    }
}
```

**`aNewlineBearingValueIsReplacedNotEchoed` is the security test.** The others are its supporting cast.

- [ ] **Step 3: Run them and watch them fail**

```bash
./mvnw -q test -Dtest=FapiInteractionIdFilterTest
echo "EXIT=$?"
```

Do **not** pipe through `tail` — you need Maven's own exit code. Expected: failures on the newline, overlong and non-UUID cases; the valid-UUID and absent cases already pass. **Confirm the log names `FapiInteractionIdFilterTest`** — a `-Dtest` pattern matching nothing exits 0.

- [ ] **Step 4: Implement**

Replace the assignment at `:41-42` with a validated version:

```java
    private static final Pattern RFC_4122 =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static String sanitise(String supplied) {
        return supplied != null && RFC_4122.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }
```

and call it. **Validate by full match against the UUID shape rather than stripping newlines** — an allowlist cannot be defeated by an encoding the stripper did not anticipate, and FAPI requires a UUID anyway, so the stricter rule is also the correct one. Do not log the rejected value; that reintroduces the injection.

Update the class javadoc to say what it now does: a non-conforming value is **replaced, not echoed**.

- [ ] **Step 5: Green, then full verify**

```bash
./mvnw -q test -Dtest=FapiInteractionIdFilterTest; echo "EXIT=$?"
./mvnw -q verify; echo "VERIFY_EXIT=$?"
```

Expected `0` and **159** unit tests (154 + 5).

- [ ] **Step 6: Commit and push**

```bash
git add src/main/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilter.java \
        src/test/java/com/ffroliva/tinyledger/platform/FapiInteractionIdFilterTest.java
git commit -F - <<'EOF'
fix: a forged x-fapi-interaction-id can no longer write log lines

The filter echoed the client's header verbatim into both the response and the
MDC. A value containing a newline therefore wrote a second line into the log,
attributed to this service - log forging, from an unauthenticated request,
since the filter is @Ordered ahead of the security chain. An unbounded value
was also a log-volume amplifier.

Validated by full match against RFC 4122 rather than by stripping newlines: an
allowlist cannot be defeated by an encoding the stripper did not anticipate,
and FAPI requires a UUID, so the stricter rule is the correct one anyway. A
non-conforming value is replaced with a minted one, exactly as an absent header
already was. The rejected value is not logged - that would reintroduce it.
EOF
git log -1 --format='%s'
git push
```

---

### Task 2: Close Boot's `/error`

**Files:**
- Modify: `src/main/resources/application.properties`
- Create/extend: an IT asserting `/error` no longer echoes the path

**Interfaces:** none consumed or produced.

- [ ] **Step 1: Confirm the leak exists**

`SecurityProblemHandler`'s own javadoc already records it: *"the default 403 is `BasicErrorController`'s shape, which echoes the request `path` — an internal identifier §6.5 forbids crossing the boundary."*

```bash
grep -rn 'server.error' src/main/resources/ || echo "NO server.error PROPERTY — defaults apply"
```

Boot's defaults are `server.error.include-message=never` and `include-stacktrace=never`, but **`path` is always included** and there is no property to suppress it.

- [ ] **Step 2: Decide by measuring, not by reasoning**

Two candidate fixes. **Measure which is true before choosing:**

1. `spring.autoconfigure.exclude=…ErrorMvcAutoConfiguration` — removes `BasicErrorController` entirely.
2. A `@RestControllerAdvice`-based catch-all already covering these paths.

**Trap 6 applies and it is exactly the shape that has bitten here before:** `spring.autoconfigure.exclude` **replaces, it does not append**, and `application-standalone.properties` already sets three entries. If you add an exclusion in a profile file, you silently drop those three. Put it in `application.properties` (the base, true in both modes) or extend the existing list in full — and prove the original three are still excluded afterwards.

- [ ] **Step 3: Prove the fix by violating it**

Write the IT first, watch it fail, then apply the property. The assertion is that an error response body **does not contain the request path**. Push and read CI: the red run must fail on that assertion specifically.

- [ ] **Step 4: Confirm both modes still answer the catalogue**

`SecurityProblemHandler` produces the catalogued 401/403. Removing `BasicErrorController` must not remove those. Run `verify` locally, and confirm in CI that `SecurityConfigIT#theRefusalCarriesTheCataloguedProblem` still passes — that test exists precisely because a status-only assertion would not notice a lost body.

- [ ] **Step 5: Commit and push** with a message stating which fix was chosen and what the measurement showed.

---

### Task 3: Validate the token audience

Now testable, because Plan 2 put a real Keycloak behind the IT suite.

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java`, `src/main/resources/application-full.properties`
- Create: `src/test/java/com/ffroliva/tinyledger/config/AudienceValidationIT.java`

- [ ] **Step 1: Confirm the gap and the mechanism**

With only `issuer-uri`, **any token the realm issues is accepted — including one minted for a different client.** Read how Boot assembles validators: `JwtDecoderConfiguration#getValidator` adds a `JwtIssuerValidator` on `getIssuerUri() != null`. Adding an audience validator means supplying a `JwtDecoder` bean or post-processing the default one. **Whichever you choose, it must not fork the test context** — see the Global Constraints.

- [ ] **Step 2: Realm work**

The realm at `docker/keycloak/realm-tiny-ledger.json` has one public client, `ledger-test`. To prove audience rejection you need a token carrying a **different** `aud`. Add a second client to the realm, and mint from it in the negative test. **Keep the fixture marker and the `dev-only` convention.**

- [ ] **Step 3: The differential proof**

Two tests, and both are required:
- a token minted for the expected audience is **accepted** (200)
- a token minted for the *other* client is **refused** (401)

Only the pair proves it. A single positive would pass with no validator at all.

- [ ] **Step 4: Configure, verify, push.** The audience belongs in `application-full.properties` as configuration, not a constant. `standalone` is unauthenticated and must be unaffected — assert that.

---

### Task 4: Rate limiting

The real one, and deliberately last: it is the largest build and the other three are live defects.

**Files:**
- Create: `RateLimitFilter.java`, `RateLimitProperties.java`, `RateLimitConfig.java`, `RateLimitFilterTest.java`, `RateLimitIT.java`
- Modify: `pom.xml`, `application.properties`, `application-standalone.properties`

- [ ] **Step 1: Read §6.1 in full before writing anything**

It specifies more than "a rate limiter", and the details are contractual:

| Scope | Limit |
|---|---|
| Write endpoints, per principal | 100 / minute, burst 20 |
| Read endpoints, per principal | 1000 / minute |
| Unauthenticated, per IP | 20 / minute |
| Any traffic, per IP (backstop) | 300 / minute |

Also required: **token bucket per principal *and* per IP, whichever is more restrictive**; Bucket4j backed by **Redis (`lettuce`) in `full`** so limits are shared across instances, falling back to a **local in-memory bucket in `standalone`**; `429` with **`Retry-After`** and the `RateLimitExceeded` problem detail; and **"limits are configuration, not constants"** — so `RateLimitProperties`, not literals.

**Two production details §6.1 names explicitly:** client IP is **`getRemoteAddr()`, never a raw `X-Forwarded-For`** — the forwarded-header strategy is enabled only behind a trusted proxy. Read the rest of that paragraph and honour it.

- [ ] **Step 2: The error already exists — wire to it, do not invent one**

`ErrorCode.RATE_LIMIT_EXCEEDED(429, "/errors/rate-limit-exceeded", "Rate limit exceeded")` is already in the catalogue with no producer. **Use it.** `ErrorCodeTest` pins each `type` URI; adding a producer must not change the code.

- [ ] **Step 3: Unit-test the bucket logic without Redis**

The decision — which bucket, which limit, is it exhausted — must be testable with no container. Keep the storage behind an interface so `RateLimitFilterTest` runs under `verify` with **zero containers**.

- [ ] **Step 4: Prove the 429 by exhausting a bucket in an IT**

`RateLimitIT` drives one endpoint past its limit and asserts `429`, the `Retry-After` header, and the catalogued problem `type`. **Set the limit low via configuration for the test** rather than issuing a thousand requests — that is what "limits are configuration" buys.

- [ ] **Step 5: Prove `verify` still starts zero containers**, differentially: 0 locally, non-zero under `-Pit`.

- [ ] **Step 6: Commit and push.** Expect a substantial count rise; report it with the run conclusion.

---

### Task 5: The CI security stage, and spec v3.11

**Files:**
- Modify: `.github/workflows/ci.yml`, `docs/spec.md`

- [ ] **Step 1: Add the security stage spec §12.1 specifies**

Stage 11 is `gitleaks`, `detect-secrets`, a Trivy image scan and `dependency-check`. **Start with secret scanning** — it is the one whose absence matters most right now: this repository is on a remote and `docker/keycloak/realm-tiny-ledger.json` contains six `dev-only` password literals. The fixture is harmless; the missing gate is not.

**Prove the gate by violating it**: add a plausible-looking fake secret on a throwaway branch, watch the job fail, delete the branch. A scanner that has never failed is not known to work. If the fixture's `dev-only` literals trip it, that is a **finding, not a nuisance** — decide deliberately between an allowlist entry and changing the fixture, and record which and why.

- [ ] **Step 2: Update §12.1 to say what CI actually runs**

The table lists twelve stages; CI implements seven, and this task adds one. **Do not claim the others.** Mark each unimplemented stage as such, in the same Built / Specified-not-yet-built style §1 and §12 already use.

- [ ] **Step 3: Spec v3.11 — delete the rows this plan closed**

Remove the `x-fapi-interaction-id`, `/error`, `aud` and rate-limiting gap rows **only as each is genuinely closed**. Verify each against the code before deleting it. **One revision row.**

- [ ] **Step 4: Sweep for twins, by claim and not by document**

This lineage has produced ~21 stale statements across ~8 sites, and the worst batch was missed because a sweep was scoped by document. `docs/agentic-workflow.md:267` records that one stopgap was named in five places. **For each thing you close, ask where else the repository asserts it is open** — `docs/spec.md`, `docs/api/openapi.yaml`, `README.md`, `CHANGELOG.md`, `AGENTS.md`, `docs/INDEX.md`, `docs/adr/*`, `docs/agentic-workflow.md`, and javadoc in the files you touched. Report every hit and your judgement.

---

## Review focus

1. **Is the `x-fapi-interaction-id` fix an allowlist, not a denylist?** A stripper is defeatable; a UUID full-match is not.
2. **Did the `/error` exclusion silently drop the three existing `spring.autoconfigure.exclude` entries?** Trap 6.
3. **Does audience validation have a negative test with a genuinely different `aud`?** A positive alone proves nothing.
4. **Is the rate limiter's decision testable without Redis, and does `verify` still start zero containers?**
5. **Was the secret scanner proven by a deliberate violation?**
6. **Does any document still say these four gaps are open?**
