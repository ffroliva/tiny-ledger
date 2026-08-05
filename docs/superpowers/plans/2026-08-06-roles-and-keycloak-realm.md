# Roles and the Keycloak realm — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the ledger the three roles the contract already advertises — `ledger:reader`, `ledger:writer`, `ledger:auditor` — enforced by the security filter chain, and move the whole integration suite onto a real Keycloak container so every IT exercises the **production `issuer-uri` decoder branch** instead of a committed test key.

**Architecture:** A `GenericContainer` running Keycloak, started once on `AbstractIntegrationTest`'s shared static block and wired through the one existing `@DynamicPropertySource`, so the `full` Spring context is still built exactly once. A realm JSON provisions six users with **pinned UUIDs**, so `sub` is deterministic. A `JwtAuthenticationConverter` maps Keycloak's nested `realm_access.roles` onto Spring authorities, and `SecurityConfig` gains per-endpoint role matchers — **replacing** Task 6b's `denyAll()`, never deleting it.

**Tech Stack:** Java 25, Spring Boot 4.1.0 → **Spring Security 7.x** (not 6.5), Testcontainers, Keycloak 26.4, JUnit 5, ArchUnit.

## Global Constraints

- **Boot 4.1.0 brings Spring Security 7.x.** `SecurityConfig.java:61` says so. Check every Security API against 7.x, **not** the 6.x documentation that dominates training data. Six framework claims in this repository were reasoned and wrong; every measured one was right.
- **Never run `./mvnw -Pit` locally.** The heavy suite runs in CI (`AGENTS.md`). Push the branch and read `gh run watch` / `gh run view --log-failed`. `./mvnw -q verify` stays local — it is fast and starts zero containers.
- **Never run two Maven builds in this tree.**
- **One Spring test context.** `AbstractIntegrationTest` has exactly one `@DynamicPropertySource`. A per-class `@Import`, `@TestConfiguration` or extra `@TestPropertySource` changes the context cache key and forks the `full` context (ADR 0003). **`missCount = 1` is the invariant.**
- **`verify` must start ZERO containers.** The split is load-bearing: it is what lets the `unit` CI job run without Docker. A Keycloak container must never be reachable from a non-`*IT` test.
- **Baseline is 148 unit / 36 integration**, measured in CI at `38b798e` against a green exit code. Both numbers will rise in this plan; state the new number and its exit code together, never alone.
- **Prove every gate by deliberately violating it.** `-Dtest` takes **commas**; a pattern matching nothing exits **0**.
- Commit per logical change with explicit pathspecs. **Never `git add -A`.** Push to `origin` freely; **never merge**.
- Write commit messages with `git commit -F - <<'EOF' … EOF` (**bash heredoc**). Never a PowerShell here-string — it leaks a literal `@` into the subject. Verify with `git log -1 --format='%s'`.

## The decision this plan takes, stated once

**`CallerPrincipal` returns the JWT `sub`, and that does not change.** Spec §6.4 says ownership is checked against the subject. With a real Keycloak, `sub` is a UUID rather than `"alice"`, so **account owners become UUIDs**. The alternative — switching to `preferred_username` — would be a contract change to make test narratives prettier, and would break the moment a username is reused.

Consequence: the realm **pins each user's `id`**, so `sub` is deterministic and tests can reference it. This is what makes the fixtures usable, and it is why the realm file exists rather than users being created at runtime.

## File Structure

**Created**
- `docker/keycloak/realm-tiny-ledger.json` — the realm: 3 realm roles, 6 users with pinned UUIDs, one confidential client for the CLI, one public client for tests.
- `src/main/java/com/ffroliva/tinyledger/platform/KeycloakRealmRolesConverter.java` — maps `realm_access.roles` to `GrantedAuthority`. `platform`, because it is a framework guard (§3).
- `src/test/java/com/ffroliva/tinyledger/testsupport/KeycloakTokens.java` — fetches real tokens from the container by password grant.
- `src/test/java/com/ffroliva/tinyledger/platform/KeycloakRealmRolesConverterTest.java`
- `src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java`

**Modified**
- `src/main/java/com/ffroliva/tinyledger/config/AuthorizedUseCases.java` — javadoc only (Task 1).
- `src/main/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditController.java` — javadoc only (Task 1).
- `src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java` — role matchers; `denyAll()` replaced.
- `src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java` — Keycloak container; `issuer-uri` points at it; `public-key-location` removed.
- `docs/spec.md` — v3.10, landing in the same commit as the code (Task 5).

**Not touched:** `src/test/java/.../testsupport/TestJwt.java` stays. `@WebMvcTest` slices still mint local tokens; only ITs move to the container.

---

### Task 1: Repair the two javadocs the v3.9 branch falsified

Opening scope, and it is deliberately first: the tree currently contains two comments asserting the spec is wrong when it is now right. Every later task in this plan reads those files.

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/config/AuthorizedUseCases.java:28-33`
- Modify: `src/main/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditController.java:40`

**Interfaces:** Consumes nothing. Produces nothing. Comment-only — no bytecode change, no test can move.

- [ ] **Step 1: Read what the comments claim, and confirm each clause is false**

```bash
sed -n '24,36p' src/main/java/com/ffroliva/tinyledger/config/AuthorizedUseCases.java
sed -n '36,44p' src/main/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditController.java
sed -n '4p' docs/spec.md
grep -n 'every authorisation decision is made by' docs/spec.md
```

Expected: the first says *"The spec does not yet say this … `docs/spec.md:672-675` still describes a single authorisation decorator … frozen at v3.8 … must be amended in the Plan 3 spec revision … this comment is the only honest record of the divergence."* The spec line 4 reads `**Version:** 3.9` and §6.4 now carries the principle sentence. **Every clause is false.** The second claims §6.4's `ledger:auditor` check "is the composition root's authorisation decorator"; §6.4 row 4 assigns those routes to the security filter chain.

If the spec does **not** say those things, stop and report — you are on the wrong branch.

- [ ] **Step 2: Replace the `AuthorizedUseCases` paragraph**

Replace the whole `<p><strong>The spec does not yet say this.</strong> … </p>` paragraph with:

```java
 * <p><strong>The spec records this split.</strong> §6.4 states the principle — every authorisation
 * decision is made by the component that holds the state the decision needs — and enumerates the four
 * enforcement sites, of which this decorator is one. The list is closed there against a fifth. No gate
 * enforces that closure; it is a review obligation, and §6.4 says so plainly.
```

- [ ] **Step 3: Replace the `AuditController` claim**

Its comment says the `ledger:auditor` check is the composition root's decorator. Replace that clause with:

```java
 * §6.4 row 4: these routes depend on role alone, with no account subject to compare, so they are
 * authorised by the security filter chain in {@code config} — not by a decorator. See SecurityConfig.
```

- [ ] **Step 4: Prove nothing but comments changed**

```bash
git diff --stat
./mvnw -q verify 2>&1 | tail -5; echo "VERIFY_EXIT=$?"
```

Expected: two files, comment lines only, `VERIFY_EXIT=0`. **The count must still be 148** — a comment cannot move it. If it moved, something else is in your diff.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ffroliva/tinyledger/config/AuthorizedUseCases.java \
        src/main/java/com/ffroliva/tinyledger/audit/adapter/in/web/AuditController.java
git commit -F - <<'EOF'
docs: the two comments v3.9 falsified now match the spec

AuthorizedUseCases said the spec does not describe the four-site split, that
docs/spec.md is frozen at v3.8, that §6.4 must still be amended, and that the
comment was the only honest record of the divergence. v3.9 made all four false
and the line citation drifted onto the role table.

AuditController said the auditor check is the composition root's decorator.
§6.4 row 4 assigns those routes to the security filter chain.

Comment-only. 148 unit tests unchanged, as a comment cannot move them.
EOF
git log -1 --format='%s'
```

---

### Task 2: A real Keycloak container behind the production decoder branch

The largest change in the plan and the one that closes the largest residual risk: **no integration test has ever authenticated against a real issuer.**

**Files:**
- Create: `docker/keycloak/realm-tiny-ledger.json`
- Create: `src/test/java/com/ffroliva/tinyledger/testsupport/KeycloakTokens.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java`
- Modify: `src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java`

**Interfaces:**
- Produces: `KeycloakTokens.accessToken(String baseUrl, String username)` → `String`, a raw JWT. `KeycloakTokens.SUBJECTS` → `Map<String,String>` of username → pinned `sub` UUID.
- Produces, on `AbstractIntegrationTest` so every IT inherits them (Task 4 depends on all three):
  - `public static final GenericContainer<?> KEYCLOAK`, already started
  - `protected static String issuerUri()` → `"http://" + host + ":" + mappedPort + "/realms/tiny-ledger"`
  - `protected static String bearer(String username)` → `"Bearer " + KeycloakTokens.accessToken(issuerUri(), username)`
- Produces: an `issuer-uri` already registered on the shared `@DynamicPropertySource`.

- [ ] **Step 1: Write the realm**

Create `docker/keycloak/realm-tiny-ledger.json`. UUIDs are pinned so `sub` is deterministic — that is the whole point.

```json
{
  "realm": "tiny-ledger",
  "enabled": true,
  "sslRequired": "none",
  "accessTokenLifespan": 900,
  "roles": {
    "realm": [
      { "name": "ledger:reader",  "description": "Read balance and history for owned accounts" },
      { "name": "ledger:writer",  "description": "Record movements on owned accounts" },
      { "name": "ledger:auditor", "description": "Read the audit trail across all accounts; no writes" }
    ]
  },
  "clients": [
    {
      "clientId": "ledger-test",
      "enabled": true,
      "publicClient": true,
      "directAccessGrantsEnabled": true,
      "standardFlowEnabled": false,
      "protocol": "openid-connect"
    }
  ],
  "users": [
    { "id": "00000000-0000-4000-8000-000000000001", "username": "alice",   "enabled": true,
      "credentials": [{ "type": "password", "value": "dev-only", "temporary": false }],
      "realmRoles": ["ledger:writer", "ledger:reader"] },
    { "id": "00000000-0000-4000-8000-000000000002", "username": "bob",     "enabled": true,
      "credentials": [{ "type": "password", "value": "dev-only", "temporary": false }],
      "realmRoles": ["ledger:writer", "ledger:reader"] },
    { "id": "00000000-0000-4000-8000-000000000003", "username": "carol",   "enabled": true,
      "credentials": [{ "type": "password", "value": "dev-only", "temporary": false }],
      "realmRoles": ["ledger:reader"] },
    { "id": "00000000-0000-4000-8000-000000000004", "username": "dave",    "enabled": true,
      "credentials": [{ "type": "password", "value": "dev-only", "temporary": false }],
      "realmRoles": ["ledger:auditor"] },
    { "id": "00000000-0000-4000-8000-000000000005", "username": "mallory", "enabled": true,
      "credentials": [{ "type": "password", "value": "dev-only", "temporary": false }],
      "realmRoles": ["ledger:writer", "ledger:reader"] },
    { "id": "00000000-0000-4000-8000-000000000006", "username": "nobody",  "enabled": true,
      "credentials": [{ "type": "password", "value": "dev-only", "temporary": false }],
      "realmRoles": [] }
  ]
}
```

`nobody` holds **no roles** and exists so Task 4 can prove a valid, authenticated token is still refused — the difference between authentication and authorisation.

- [ ] **Step 2: Start Keycloak on the shared base class**

In `AbstractIntegrationTest`, add beside the existing containers. **A `GenericContainer` deliberately, not `dasniko/testcontainers-keycloak`** — no new dependency to resolve, no compatibility question against Boot 4.1.

```java
    public static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
                    DockerImageName.parse("quay.io/keycloak/keycloak:26.4"))
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/keycloak/realm-tiny-ledger.json"),
                    "/opt/keycloak/data/import/realm-tiny-ledger.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/tiny-ledger/.well-known/openid-configuration")
                    .forPort(8080)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
```

The wait strategy waits for **the realm's discovery document**, not for the port or a log line. A port-open wait would let a context start against a realm that has not imported yet, and the `issuer-uri` decoder **fails lazily** — the failure would surface on first decode, in whichever test happened to run first.

Add `KEYCLOAK.start();` to the existing `static` block.

- [ ] **Step 3: Point the resource server at it, and delete the test key**

In `configureProperties`, **replace** the two JWT lines with:

```java
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/tiny-ledger");
```

Delete the `public-key-location` registration and the paragraph of comment explaining the blank issuer — it documents a workaround that no longer exists. **Leaving stale explanation behind is the defect this project has spent two plans removing.**

- [ ] **Step 4: A token helper**

Create `KeycloakTokens`. Password grant against `ledger-test`, no new HTTP dependency — `java.net.http` is in the JDK.

```java
public final class KeycloakTokens {

    /** Pinned in the realm file so `sub` is deterministic. §6.4's ownership term is the subject. */
    public static final Map<String, String> SUBJECTS = Map.of(
            "alice", "00000000-0000-4000-8000-000000000001",
            "bob", "00000000-0000-4000-8000-000000000002",
            "carol", "00000000-0000-4000-8000-000000000003",
            "dave", "00000000-0000-4000-8000-000000000004",
            "mallory", "00000000-0000-4000-8000-000000000005",
            "nobody", "00000000-0000-4000-8000-000000000006");

    private KeycloakTokens() {}

    public static String accessToken(String baseUrl, String username) {
        String form = "grant_type=password&client_id=ledger-test"
                + "&username=" + username + "&password=dev-only";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "token request for " + username + " failed: " + response.statusCode() + " " + response.body());
            }
            return new ObjectMapper().readTree(response.body()).get("access_token").asText();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("token request for " + username + " failed", e);
        }
    }
}
```

It **throws on a non-200 rather than returning null**, so a realm misconfiguration fails at the point of the mistake with the body attached, instead of surfacing later as a confusing 401.

Then add these two helpers to `AbstractIntegrationTest`, so every IT inherits them and no test builds a URL or a header by hand:

```java
    protected static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/tiny-ledger";
    }

    /** A real `Authorization` header value for one of the realm's fixture users. */
    protected static String bearer(String username) {
        return "Bearer " + KeycloakTokens.accessToken(issuerUri(), username);
    }
```

- [ ] **Step 5: The proof that matters — an actual 401 and an actual 200**

Add to `SecurityConfigIT`. The handoff is explicit that a boot proof must assert both, because a broken `issuer-uri` context **starts clean** and both 401 assertions pass without the decoder ever running.

```java
    @Test
    void aTokenFromTheRealIssuerIsAccepted() throws Exception {
        String token = KeycloakTokens.accessToken(issuerUri(), "alice");
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aTokenThisIssuerDidNotMintIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + TestJwt.signed("alice")))
                .andExpect(status().isUnauthorized());
    }
```

The second is the **differential** half: the old locally-minted token must now be *rejected*, which proves the decoder really moved to the container rather than merely still working.

- [ ] **Step 6: Prove `verify` still starts zero containers**

```bash
./mvnw -q verify 2>&1 | tee /tmp/verify.log; echo "VERIFY_EXIT=$?"
grep -ciE 'tc\.|testcontainers|Creating container' /tmp/verify.log
```

Expected: `VERIFY_EXIT=0` and **0** matches. This is the constraint that keeps the no-Docker CI job possible. Do **not** run `-Pit` locally.

- [ ] **Step 7: Push and read CI**

```bash
git add docker/keycloak/realm-tiny-ledger.json \
        src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java \
        src/test/java/com/ffroliva/tinyledger/testsupport/KeycloakTokens.java \
        src/test/java/com/ffroliva/tinyledger/config/SecurityConfigIT.java
git commit -F - <<'EOF'
test: the integration suite authenticates against a real Keycloak

Every IT ran the public-key-location decoder branch against a committed test
key, with a blank issuer. Production runs issuer-uri, and the two branches were
measured to behave differently: Boot builds a lazily-resolving SupplierJwtDecoder,
so a misconfigured context starts clean and fails on first decode. 36 green ITs
proved the chain, the entry point, the decorators and the denials were wired.
They proved nothing about whether a Keycloak token validates.

A GenericContainer running Keycloak 26.4 imports a realm with three roles and
six users whose ids are pinned, so `sub` is deterministic - §6.4 checks ownership
against the subject, and with a real issuer the subject is a UUID.

The wait strategy waits for the realm's discovery document, not for the port:
the decoder fails lazily, so a context started against a half-imported realm
would fail in whichever test ran first rather than at startup.

Proven differentially: a token minted by the container is accepted, and the old
locally-signed token is now refused - which shows the decoder moved rather than
merely still working.
EOF
git log -1 --format='%s'
git push
gh run watch "$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')" --exit-status
```

Read `INTEGRATION_TESTS=` from the CI step summary. **Record the number with the run conclusion**, never alone.

---

### Task 3: Map Keycloak's nested roles onto Spring authorities

**Files:**
- Create: `src/main/java/com/ffroliva/tinyledger/platform/KeycloakRealmRolesConverter.java`
- Create: `src/test/java/com/ffroliva/tinyledger/platform/KeycloakRealmRolesConverterTest.java`

**Interfaces:**
- Produces: `KeycloakRealmRolesConverter implements Converter<Jwt, AbstractAuthenticationToken>`. Task 4 wires it into `SecurityConfig`. Authorities are the **bare role names** — `ledger:reader`, not `ROLE_ledger:reader` — so Task 4 must use `hasAuthority`, never `hasRole`.

**This is where the previous attempt failed.** `CallerPrincipal.roles()` was written and deliberately deleted because it pinned a flat `"roles"` claim while Keycloak nests under `realm_access.roles`. A green test asserting the wrong shape is worse than no test.

- [ ] **Step 1: Write the failing test against the real claim shape**

```java
class KeycloakRealmRolesConverterTest {

    private final KeycloakRealmRolesConverter converter = new KeycloakRealmRolesConverter();

    private static Jwt jwtWith(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s");
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void readsRolesNestedUnderRealmAccess() {
        Jwt jwt = jwtWith(Map.of("realm_access", Map.of("roles", List.of("ledger:reader", "ledger:writer"))));
        assertThat(converter.convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ledger:reader", "ledger:writer");
    }

    @Test
    void aFlatRolesClaimIsIgnored() {
        Jwt jwt = jwtWith(Map.of("roles", List.of("ledger:reader")));
        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    @Test
    void aTokenWithNoRealmAccessHasNoAuthorities() {
        Jwt jwt = jwtWith(Map.of("scope", "openid"));
        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }
}
```

`aFlatRolesClaimIsIgnored` is the regression test for the deleted implementation. Without it, a future refactor to a flat claim would pass.

- [ ] **Step 2: Run it and watch it fail**

```bash
./mvnw -q test -Dtest=KeycloakRealmRolesConverterTest 2>&1 | tail -20; echo "EXIT=$?"
```

Expected: **compilation failure** — the class does not exist. Confirm the log names `KeycloakRealmRolesConverterTest`. A `-Dtest` pattern matching nothing exits **0**; if you see `EXIT=0`, the test did not run and this step has proven nothing.

- [ ] **Step 3: Implement it**

```java
package com.ffroliva.tinyledger.platform;

/**
 * §6.4: Keycloak nests realm roles under {@code realm_access.roles}. An earlier attempt pinned a flat
 * {@code roles} claim behind a green test — the shape Keycloak does not use — and was deleted rather
 * than kept, because a passing test asserting the wrong shape is worse than no test at all.
 *
 * <p>Authorities are the bare role names, so authorisation rules use {@code hasAuthority}. Spring's
 * {@code hasRole} prepends {@code ROLE_}, which these names do not carry.
 */
public class KeycloakRealmRolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        Collection<GrantedAuthority> authorities = List.of();
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            authorities = roles.stream()
                    .map(String::valueOf)
                    .map(SimpleGrantedAuthority::new)
                    .map(GrantedAuthority.class::cast)
                    .toList();
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
```

- [ ] **Step 4: Run it green**

```bash
./mvnw -q test -Dtest=KeycloakRealmRolesConverterTest 2>&1 | tail -20; echo "EXIT=$?"
```

Expected: `EXIT=0`, and the log names the class with 3 tests run.

- [ ] **Step 5: Full local unit run, then commit**

```bash
./mvnw -q verify; echo "VERIFY_EXIT=$?"
```

Expected `0`, and **151** unit tests (148 + 3).

```bash
git add src/main/java/com/ffroliva/tinyledger/platform/KeycloakRealmRolesConverter.java \
        src/test/java/com/ffroliva/tinyledger/platform/KeycloakRealmRolesConverterTest.java
git commit -F - <<'EOF'
feat: map Keycloak realm roles onto Spring authorities

Keycloak nests realm roles under realm_access.roles. CallerPrincipal.roles()
was written once against a flat "roles" claim, passed its tests, and was
deliberately deleted - the shape was wrong and a green test asserting a wrong
shape is worse than none, because the follow-up trusts it.

aFlatRolesClaimIsIgnored is the regression test for exactly that mistake.

Authorities are bare role names, so rules use hasAuthority; hasRole would
prepend ROLE_, which these names do not carry.
EOF
git log -1 --format='%s'
```

---

### Task 4: Enforce the roles, and replace the auditor denial

**Files:**
- Modify: `src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java:68-90`
- Create: `src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java`

**Interfaces:**
- Consumes: `KeycloakRealmRolesConverter` (Task 3), `KeycloakTokens.accessToken` (Task 2).

**Task 6b's `denyAll()` is REPLACED, never deleted.** Its comment says so and gives the reason: the trail is deliberately not owner-scoped and `accountUid` is optional on it, so any authenticated caller could page every account's id, amount and reference — which also voids §6.5's "unguessable UUIDs" premise. Deleting the matcher reopens exactly that.

- [ ] **Step 1: Replace the matcher block**

In `fullChain`, replace the `authorizeHttpRequests` lambda and wire the converter:

```java
                .authorizeHttpRequests(auth -> auth
                        // §6.4 row 4 / §7: role alone, no account subject. This REPLACES Task 6b's
                        // denyAll() — the routes stay closed to everyone without ledger:auditor.
                        .requestMatchers("/api/v1/audit/**", "/api/v1/accounts/*/events")
                        .hasAuthority("ledger:auditor")
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts")
                        .hasAuthority("ledger:writer")
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/v1/accounts/*/deposits/*",
                                "/api/v1/accounts/*/withdrawals/*")
                        .hasAuthority("ledger:writer")
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts/**")
                        .hasAuthority("ledger:reader")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(new KeycloakRealmRolesConverter()))
                        .authenticationEntryPoint(problems))
```

**Matcher order is load-bearing.** The auditor rule must precede the `GET /api/v1/accounts/**` reader rule, or `GET /accounts/{uid}/events` matches the reader rule first and a plain reader reads raw event streams. Task 6b's exposure was *observed live* before it was closed; this is the same shape.

Do **not** touch the `standalone` chain. Measured: moving those matchers there makes `SecurityConfigTest` fail with **403 instead of 501**.

- [ ] **Step 2: Write the boundary ITs**

`bearer(String)` and `issuerUri()` come from `AbstractIntegrationTest` (Task 2). `ANY_UID` is a fixed
random UUID — these assertions are refused **before** the account is looked up, so it need not exist;
that is the §6.3 authorise-before-anything ordering. Follow `SecurityConfigIT`'s existing `mockMvc`
wiring rather than inventing new setup.

```java
class RoleAuthorizationIT extends AbstractIntegrationTest {

    private static final String ANY_UID = "11111111-1111-4111-8111-111111111111";
    private static final String DEPOSIT_BODY = """
            {"amount":{"currency":"GBP","minorUnits":1000}}""";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anAuditorReadsTheTrail() throws Exception {
        mockMvc.perform(get("/api/v1/audit/entries").header(HttpHeaders.AUTHORIZATION, bearer("dave")))
                .andExpect(status().isOk());
    }

    @Test
    void aReaderIsRefusedTheTrail() throws Exception {
        mockMvc.perform(get("/api/v1/audit/entries").header(HttpHeaders.AUTHORIZATION, bearer("carol")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aReaderIsRefusedTheRawEventStream() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/" + ANY_UID + "/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer("carol")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aReaderMayNotMoveMoney() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/deposits/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer("carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEPOSIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAuditorMayNotMoveMoney() throws Exception {
        mockMvc.perform(put("/api/v1/accounts/" + ANY_UID + "/deposits/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer("dave"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DEPOSIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRolelessButAuthenticatedTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer("nobody")))
                .andExpect(status().isForbidden());
    }
}
```

The last one is the point of the whole task: `nobody` holds a **valid** token from the real issuer. A 401 there would mean the roles are not being read at all; the assertion is **403**, which is the difference between authentication and authorisation.

- [ ] **Step 3: Prove the auditor matcher by deliberately breaking its order**

Before trusting the order, invert it: move the `GET /api/v1/accounts/**` reader rule **above** the auditor rule, push, and read CI.

Expected: `aReaderIsRefusedTheRawEventStream` **fails with 200**. That is the live exposure Task 6b found, reproduced on purpose. Then restore the order and confirm it passes. **Record both runs' ids and conclusions.** A green run on the correct order proves nothing on its own.

- [ ] **Step 4: Push and read CI**

```bash
./mvnw -q verify; echo "VERIFY_EXIT=$?"   # local: unit only, must stay green
git add src/main/java/com/ffroliva/tinyledger/config/SecurityConfig.java \
        src/test/java/com/ffroliva/tinyledger/config/RoleAuthorizationIT.java
git commit -F - <<'EOF'
feat: enforce ledger:reader / writer / auditor at the filter chain

Task 6b closed both auditor routes with denyAll() because the role did not
exist. This REPLACES that denial with the ledger:auditor check it was waiting
for - the routes stay shut to everyone without the role. Deleting the matcher
would reopen the exposure: the trail is not owner-scoped and accountUid is
optional on it, so any authenticated caller could page every account's id,
amount and reference, which also voids §6.5's unguessable-UUID premise.

Matcher order is load-bearing: the auditor rule precedes the reader rule,
because GET /accounts/{uid}/events would otherwise match the reader rule first
and hand raw event streams to any reader. Proven by inverting the order and
watching the test fail with 200, then restoring it.

nobody holds a valid token with no roles and is refused 403, not 401 - the
difference between authentication and authorisation, and the assertion that
fails if the converter is not wired.
EOF
git log -1 --format='%s'
git push
gh run watch "$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')" --exit-status
```

---

### Task 5: Land the spec text in the same commit as the code

The council's central recommendation, and the reason v3.9 exists: **a spec has no test gate, so prose describing unbuilt behaviour has nothing holding it accountable.** These lines are now true, so they change now — not in a later documentation pass.

**Files:**
- Modify: `docs/spec.md`

- [ ] **Step 1: Flip every line v3.9 recorded as not built**

Each of these was written by v3.9 as a *false-at-the-time* claim made honest. Verify each against your own code before flipping it:

| Where | v3.9 says | Now |
|---|---|---|
| §6.4 test-users preface | "**Not built at v3.9** — neither the realm file nor a Keycloak service exists yet" | Built: the realm file exists and the IT suite imports it |
| §6.4 `ACC-001`…`ACC-900` sentence | "neither the realm file nor the seed script is built at v3.9" | The realm file is built; **the seed script is still not** — say so |
| §9.4 | "Keycloak is **not** among them at v3.9 — the suite trusts a committed test key" | Real Postgres, Kafka, Redis **and Keycloak**; the production `issuer-uri` branch is exercised |
| Gaps table, Keycloak realm row | "Neither exists" | **Delete the row** |
| Gaps table, `POST /accounts` row | "no role check exists anywhere" | **Delete the row** |
| Gaps table, auditor row | "403 to every caller" | **Delete the row** |
| "No role check exists in `src/main` at v3.9" | present | **Delete the sentence** |
| §12.1 CI table stage 7 | "Postgres, Kafka, Redis" | add Keycloak |

**Do not delete the `aud`, rate limiting, `x-fapi-interaction-id` or `/error` rows** — Plan 3 owns those and they are still true.

- [ ] **Step 2: Bump to 3.10 with one revision row**

`docs/spec.md:4` → `**Version:** 3.10`. **One** row, matching the established format.

- [ ] **Step 3: Audit every `§` you touched**

For each: open it and confirm it says what your sentence claims. This is the only real gate a documentation change has. **Then sweep for the twin**: `grep -niE 'keycloak|realm|ledger:reader|ledger:writer|ledger:auditor' docs/spec.md` and read every hit in context. Eight twins were found on the v3.9 branch, each after the named location was already fixed. Assume a ninth.

```bash
python scripts/ci/check_docs_governance.py; echo "GOVERNANCE_EXIT=$?"
```

Expected `0`. **This proves nothing about correctness** — it checks the document inventory.

- [ ] **Step 4: Commit and push**

```bash
git add docs/spec.md
git commit -F - <<'EOF'
docs: spec v3.10 — the roles and the realm exist now

v3.9 recorded nine divergences between this document and the code. Three of
them are closed by the commits above and their rows are deleted rather than
softened: the Keycloak realm, POST /accounts authorised by authentication
alone, and the temporary 403 on both auditor operations.

§9.4 says Keycloak is among the containers, because it now is, and the
production issuer-uri decoder branch is exercised by every IT rather than by
none. The seed script is still not built and still says so.

Landing here rather than in a later documentation pass is the point: a spec
has no test gate, so prose describing unbuilt behaviour has nothing holding it
accountable. That is how §6.4 drifted in the first place.

The aud, rate-limiting, x-fapi-interaction-id and /error rows stay - Plan 3
owns them and they are still true.
EOF
git log -1 --format='%s'
git push
```

---

## Review focus

1. **Is `denyAll()` replaced, not deleted?** The auditor routes must be closed to a caller without `ledger:auditor`. Check with a token that has `ledger:reader` only.
2. **Matcher order.** Does `GET /accounts/{uid}/events` hit the auditor rule and not the reader rule? This exposure was observed live once already.
3. **Did the context fork?** Assert `missCount = 1`. A second context means an `@Import`, `@TestPropertySource` or `@DynamicPropertySource` was added off the shared base.
4. **Does `verify` still start zero containers?** Differentially: 0 under `verify`, non-zero under `-Pit`.
5. **Are the role names bare?** `hasAuthority("ledger:auditor")`, never `hasRole`.
6. **Does any spec sentence still say the realm is unbuilt?** And does any now claim the seed script exists? It does not.
