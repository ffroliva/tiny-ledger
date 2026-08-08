# Container Image — Buildpacks + AOT Cache, and the Image as the Artefact Under Test

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a container image with `spring-boot:build-image`, make it a Compose service, scan it in CI, and close issue **#11** — which also closes stage 11's *"no image exists to scan"* partial and unblocks the TLS work.

**Architecture:** Paketo buildpacks via the `spring-boot-maven-plugin` already in `pom.xml` — no `Dockerfile`, layered by construction. An AOT-cache training run under the `standalone` profile. The app becomes a Compose service so a gateway can route to it by name.

**Tech Stack:** Spring Boot 4.1.0 · `spring-boot:build-image` · Paketo `spring-boot` + `libjvm` buildpacks · Trivy · Java 25

**Decided already — do NOT re-open (spec v3.36, issue #11, ADR 0005):**

- **Buildpacks, not a `Dockerfile`.** The plugin is present and the image is layered by construction.
- **AOT cache / CDS is in scope. GraalVM native and CRaC are DEFERRED** — CDS needs neither a JDK vendor change nor reflection metadata, and a CRaC checkpoint is a memory image on disk, the same artefact §6.6 refused to expose via `heapdump`.
- **The training run uses `standalone`.** Under `full` it starts the application and blocks on Postgres, Redis, Kafka and Liquibase, hanging the build.
- **Build and scan in CI; do NOT publish.** No registry, no credentials, no `packages:write`. Publishing is stage 12 and stays a separate decision, so a fork's build still passes.

---

## Rules that govern every task

From `AGENTS.md`, restated because a task executed out of order must still obey them.

1. **`./mvnw -q verify` green before every commit, and it must start ZERO containers.** `build-image` starts a Docker daemon build — it must therefore **never** be bound to a lifecycle phase that `verify` runs.
2. **Never run `-Pit` locally.** Push and read CI.
3. **Commit with explicit pathspecs. Never `git add -A`.**
4. **`main` is protected.** Every change via PR. **Never merge without being asked.**
5. **No credential in a committed file.** The repo is PUBLIC and gitleaks gates CI.
6. **A test that would pass with its fix reverted is not coverage**, and check *what* reddened — a `-Dtest` pattern matching nothing exits 0.
7. **If a document claim turns out false, fix it the same day** and record it in the spec's revision history.

### The differential zero-container check

```bash
./mvnw verify > /tmp/v.log 2>&1; echo "EXIT=$?"
grep -c "Creating container for image" /tmp/v.log      # MUST be 0
grep -c "maven" /tmp/v.log                              # control: MUST be > 0
```

---

## Two traps that are already paid for — do not rediscover

**1. The buildpack environment variables everyone quotes are DEPRECATED.** Read out of the Paketo
`spring-boot` buildpack README on 2026-08-08, with a control (`BP_` scored 12 hits in the same
document, so the reading is real):

| Deprecated — do not use | Current |
|---|---|
| `BP_JVM_CDS_ENABLED` | **`BP_JVM_AOTCACHE_ENABLED`** |
| `BPL_JVM_CDS_ENABLED` | **`BPL_JVM_AOTCACHE_ENABLED`** |
| `CDS_TRAINING_JAVA_TOOL_OPTIONS` | **`TRAINING_RUN_JAVA_TOOL_OPTIONS`** |

This is the same shape as step 9's `management.otlp.tracing.*`: the spelling in common circulation is
the dead one. The buildpack's own description of `TRAINING_RUN_JAVA_TOOL_OPTIONS` is *"useful to
configure your app not to reach external services during"* the training run — the buildpack authors
named the exact problem v3.36 predicted for `full`.

**2. `spring-boot-maven-plugin` carries `<classifier>exec</classifier>` here.** The repackaged jar is
`tiny-ledger-0.1.0-SNAPSHOT-exec.jar` and the plain jar is the main artefact, deliberately, so
`benchmarks/` can compile against real classes. `build-image` consumes the **repackaged** artifact, so
it must be pointed at the classifier. If the first build produces an image from the wrong jar it will
fail at runtime with a missing main class rather than at build time — Task 1 Step 4 checks the image
actually starts, for exactly this reason.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `pom.xml` | `<image>` configuration: name, classifier, AOT-cache env | 1, 2 |
| `docker/docker-compose.yml` | the `app` service — **this is what unblocks TLS** | 3 |
| `scripts/e2e/run-e2e.sh` | run the image instead of `java -jar` | 4 |
| `.github/workflows/ci.yml` | build the image, Trivy scan it | 5 |
| `docs/spec.md` §1 table, §12, §12.1, revision history | the mode table says "not a Compose service" | 6 |
| `README.md`, `CHANGELOG.md` | the `full` recipe | 6 |

---

## Task 1: `build-image` produces a runnable image

**Files:** Modify `pom.xml`

- [ ] **Step 1: Configure the image**

Replace the `spring-boot-maven-plugin` block in `pom.xml`:

```xml
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <classifier>exec</classifier>
          <!--
            Issue #11 / spec §12. Buildpacks, not a Dockerfile: the image is layered by construction
            and there is no base image for this repository to patch and forget.

            `image.name` is pinned to a local, unqualified name ON PURPOSE. Nothing publishes it —
            stage 12 is a separate, unbuilt decision (§12.1) — so a registry-qualified name here would
            imply a push that does not happen.

            The classifier above is why this needs saying: build-image consumes the REPACKAGED jar,
            and this project deliberately gives that jar an `exec` classifier so the plain jar stays
            usable as a dependency by benchmarks/. Getting this wrong produces an image that builds
            cleanly and fails at startup with a missing main class.
          -->
          <image>
            <name>tiny-ledger:${project.version}</name>
          </image>
        </configuration>
      </plugin>
```

- [ ] **Step 2: Confirm nothing binds `build-image` to a phase**

`verify` must start zero containers, and `build-image` drives a Docker build. There is no
`<executions>` block above, so it runs only when invoked. Prove it:

```bash
./mvnw verify > /tmp/t1.log 2>&1; echo "EXIT=$?"
grep -c "Creating container for image" /tmp/t1.log      # 0
grep -ci "build-image\|Building image" /tmp/t1.log      # 0 — must not run during verify
grep -c "maven" /tmp/t1.log                              # control, > 0
```

- [ ] **Step 3: Build the image**

```bash
./mvnw -q spring-boot:build-image -DskipTests
docker images tiny-ledger --format '{{.Repository}}:{{.Tag}}  {{.Size}}'
```

Expected: an image is listed. **First run pulls the builder and takes several minutes.**

- [ ] **Step 4: Prove it actually STARTS — the classifier check**

A wrong jar produces an image that builds fine and dies on startup, so building is not the test.

```bash
docker run --rm -d --name tl-smoke -p 18080:8080 -p 19090:9090 \
  -e SERVER_ADDRESS=0.0.0.0 -e MANAGEMENT_SERVER_ADDRESS=0.0.0.0 \
  tiny-ledger:0.1.0-SNAPSHOT
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:19090/actuator/health/readiness   # 200
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:19090/actuator/health             # 403
docker rm -f tl-smoke
```

Expected: the banner and `Started TinyLedgerApplication`. The default profile is `standalone`
(`spring.profiles.default`), so it needs no database.

**CORRECTED 2026-08-08, on the first real run — this step was wrong in two ways, and both would have
read as an image fault.** The draft curled `/actuator/health` on a published `8080` and expected
success. Measured, that gives 404 on 8080 and *nothing at all* on 9090:

1. **The management port is 9090** (`application.properties:79`), so 8080 was never going to answer.
   That much the draft already said.
2. **Under `standalone` BOTH listeners bind loopback** — `application-standalone.properties` sets
   `server.address=127.0.0.1` *and* `management.server.address=127.0.0.1`, deliberately: §6.1 makes
   loopback-only binding part of why an unauthenticated mode is safe. Inside a container that means a
   published port reaches nothing. `SERVER_ADDRESS` / `MANAGEMENT_SERVER_ADDRESS` are the override,
   and they are needed **only for this smoke test** — Task 3 runs `full`, where neither address is
   pinned and the ports are reachable as published. Do **not** weaken the `standalone` properties to
   make a smoke test convenient.
3. **`/actuator/health` answers 403, not 200**, and that is the correct answer: exposure is locked to
   `health` and the root is `denyAll` (§6.6). Probe **`/actuator/health/readiness`**. A checker that
   accepts "any 2xx on the root" would be asserting the opposite of the security property.

`curl -sf` hides all of this — it exits non-zero on 403 and 404 alike, so the two are
indistinguishable from a container that never started. Print the code.

**Measured on the first run (no AOT cache yet, so this is Task 2's baseline):**

```
9090 /actuator/health/readiness -> 200 {"status":"UP"}
9090 /actuator/health/liveness  -> 200 {"status":"UP"}
9090 /actuator/health           -> 403        <- denyAll, as §6.6 requires
8080 /actuator/health           -> 404        <- management is on 9090
Started TinyLedgerApplication in 7.161 / 6.318 / 6.285 s   (mean 6.588 s, 3 runs)
```

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "feat: build a container image with buildpacks (#11)"
```

---

## Task 2: The AOT cache, trained under `standalone`

**Files:** Modify `pom.xml`

- [ ] **Step 1: Enable it, with the CURRENT variable names**

Add to the `<image>` block from Task 1:

```xml
          <image>
            <name>tiny-ledger:${project.version}</name>
            <env>
              <!--
                Spec v3.36 / #11. The CURRENT names — BP_JVM_CDS_ENABLED and
                CDS_TRAINING_JAVA_TOOL_OPTIONS are both DEPRECATED aliases (Paketo spring-boot
                buildpack README, read 2026-08-08). Same trap as step 9's
                management.otlp.tracing.*: the spelling in common circulation is the dead one.
              -->
              <BP_JVM_AOTCACHE_ENABLED>true</BP_JVM_AOTCACHE_ENABLED>
              <!--
                THE TRAINING RUN STARTS THE APPLICATION. Under `full` it would block on Postgres,
                Redis, Kafka and Liquibase and hang the build — predicted in spec v3.36 before this
                was ever run, and the buildpack's own documentation for this variable says it exists
                "to configure your app not to reach external services during" the run.

                `standalone` is the whole answer, and it is available precisely because §1 keeps two
                run modes in one codebase. The duality earns its keep here in a way nobody planned.
              -->
              <TRAINING_RUN_JAVA_TOOL_OPTIONS>-Dspring.profiles.active=standalone</TRAINING_RUN_JAVA_TOOL_OPTIONS>
            </env>
          </image>
```

- [ ] **Step 2: Rebuild and watch the training run happen**

```bash
./mvnw -q spring-boot:build-image -DskipTests 2>&1 | tee /tmp/t2.log | tail -30
grep -ci "training run\|application.jsa\|aot" /tmp/t2.log     # must be > 0
grep -ci "standalone" /tmp/t2.log                              # the profile actually used
```

**If the build HANGS here, the training run reached `full`.** Kill it and check
`TRAINING_RUN_JAVA_TOOL_OPTIONS` is spelled exactly as above — a deprecated or misspelled name is
silently ignored, which is indistinguishable from not setting it.

- [ ] **Step 3: Measure the startup difference, and record the real number**

The point of the cache is startup time, so measure it rather than assert it.

```bash
for tag in with-cache; do
  docker run --rm -d --name tl-t -p 19090:9090 tiny-ledger:0.1.0-SNAPSHOT
  sleep 15; docker logs tl-t 2>&1 | grep -oE "Started TinyLedgerApplication in [0-9.]+ seconds"
  docker rm -f tl-t
done
```

Record both numbers — with the cache and, by temporarily setting
`BP_JVM_AOTCACHE_ENABLED=false`, without. **Put the measured pair in the commit message.** A
performance feature with no measurement is the kind of claim this repository has already retracted
once (§12's container bullet, v3.36).

**Measured 2026-08-08, three runs each, same host, minutes apart. The "without" figure is Task 1's
image, which carried no `<env>` block at all — a stronger control than flipping the flag to `false`,
because it also excludes the possibility that the flag name is read and ignored.**

```
without AOT cache   7.161 / 6.318 / 6.285 s   mean 6.588 s
with    AOT cache   3.013 / 2.942 / 3.078 s   mean 3.011 s     -54%
```

**And the runtime side had to be checked separately, because it is a second flag.** The buildpack's
own configuration table prints `$BPL_JVM_AOTCACHE_ENABLED false` — that is the *launch*-time toggle,
distinct from the `BP_` build-time one, and a cache that is built but never loaded would show up as
"the feature does nothing" with every build-time log line still looking correct. It is fine here, but
only because it was read out of the running container rather than assumed:

```
[libjvm] JVM AOT Cache Enabled, contributing -XX:AOTCache=application.aot to JAVA_TOOL_OPTIONS
Picked up JAVA_TOOL_OPTIONS: ... -XX:AOTCache=application.aot ...
```

**Do not use `docker exec … cat` to inspect the container.** The run image is
`paketobuildpacks/ubuntu-noble-run-tiny`, which ships no coreutils —
`exec: "cat": executable file not found in $PATH`. Read the application's own stdout instead.

- [ ] **Step 4: Gate and commit**

```bash
./mvnw verify > /tmp/t2v.log 2>&1; echo "EXIT=$?"; grep -c "Creating container for image" /tmp/t2v.log
git add pom.xml
git commit -m "feat: AOT-cache the image, trained under standalone (#11)"
```

---

## Task 3: The app becomes a Compose service

This is the task the TLS work is waiting on.

**Files:** Modify `docker/docker-compose.yml`

- [ ] **Step 1: Add the service**

Add before the `volumes:` block:

```yaml
  # Issue #11. Until now this file had NO app service and the README told you to run the jar on the
  # host — §1's mode table said so, and it was true. The image exists now, so `full` is one command.
  #
  # `build` is absent on purpose: the image comes from `./mvnw spring-boot:build-image`, not from a
  # Dockerfile Compose could build. That keeps ONE way to produce the artefact, which is the point of
  # buildpacks here — see spec §12.
  app:
    image: tiny-ledger:0.1.0-SNAPSHOT
    profiles: [app]
    depends_on:
      postgres: { condition: service_healthy }
      redis:    { condition: service_healthy }
      kafka:    { condition: service_healthy }
      keycloak: { condition: service_healthy }
    environment:
      SPRING_PROFILES_ACTIVE: full
      # Service names, not localhost: inside the network the app resolves its peers by name, and the
      # published host ports are irrelevant to it.
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/tiny_ledger
      SPRING_DATA_REDIS_HOST: redis
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      # The issuer must be a URL that is valid for BOTH the app (resolving in-network) and the token
      # the caller presents (minted via the published port). A mismatch here is the classic
      # "invalid_token: iss claim is not valid" and it is a HOSTNAME problem, not a crypto one.
      LEDGER_ISSUER_URI: http://keycloak:8080/realms/tiny-ledger
    ports:
      - "8080:8080"
      - "9090:9090"
```

**`profiles: [app]` keeps the default `up` unchanged**, exactly as the `observability` profile does —
the four-container default is a property §6.6 and the README both assert.

- [ ] **Step 2: Prove the default is still four services — differentially**

```bash
docker compose -f docker/docker-compose.yml config --services | sort | tr '\n' ' '
COMPOSE_PROFILES=app docker compose -f docker/docker-compose.yml config --services | sort | tr '\n' ' '
```

Expected: four names without the profile, five with it. This is the same check the Collector service
already passes, and it is the one that fails if `profiles:` is dropped.

- [ ] **Step 3: Start the whole stack and exercise it**

```bash
docker compose -f docker/docker-compose.yml --profile app up -d --wait
curl -s -o /dev/null -w "health -> %{http_code}\n" http://127.0.0.1:9090/actuator/health/readiness
docker compose -f docker/docker-compose.yml --profile app logs app | tail -20
docker compose -f docker/docker-compose.yml --profile app down
```

**Expect the Keycloak issuer to bite here.** A token minted through `localhost:8081` carries
`iss=http://localhost:8081/...` while the app expects `http://keycloak:8080/...`. If so, the fix is to
make Keycloak's hostname identical on both sides (`KC_HOSTNAME`) rather than to relax issuer
validation — **never** disable the issuer check to make a test pass.

### Executed 2026-08-08. TWO problems this step did not predict, and the issuer one was worse than described.

**1. Kafka's advertised listener made the app unreachable from inside its own network.** The broker
advertised `PLAINTEXT://localhost:9092`, which was correct while only the host ever spoke to it. A
Kafka client bootstraps, is handed the ADVERTISED address, and reconnects to *that* — so the app
container dialled `localhost:9092`, meaning itself. The bootstrap address being right is exactly what
makes the failure confusing. Fixed with two listeners: `kafka:29092` advertised to containers,
`localhost:9092` to the host. Not a security change — both are PLAINTEXT, and backing-service TLS
remains a named gap.

**2. The issuer problem is not `localhost` vs `keycloak`. It is that `iss` is whatever the caller
TYPED.** Keycloak derives it from the request's Host header, so with nothing pinned:

```
token minted via 127.0.0.1:8081  ->  iss = http://127.0.0.1:8081/realms/tiny-ledger  -> 401
token minted via localhost:8081  ->  iss = http://localhost:8081/realms/tiny-ledger  -> 200
```

Same host, same realm, same user, two spellings of loopback, one authenticates. **That is live, not
hypothetical:** `scripts/e2e/run-e2e.sh` pins `127.0.0.1` everywhere for the documented IPv6 routing
trap, while `ledger-cli/src/ledger_cli/config.py` defaults `issuer_uri` to `localhost`. The e2e suite
passes today *because those two disagree* — align them in the obvious direction and it breaks.

`KC_HOSTNAME: http://localhost:8081` makes `iss` a property of the deployment rather than of the
request. Verified after the change: both spellings mint the identical issuer.

**And the app still cannot resolve `localhost:8081`.** The answer is that "what is `iss`?" and "where
do I fetch the signing keys?" are two different questions, and Boot answers them separately —
`LEDGER_ISSUER_URI` stays public, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` points at
`http://keycloak:8080/...`. **Nothing is relaxed**, and that was confirmed in the shipped bytecode
rather than assumed from docs (`spring-boot-security-oauth2-resource-server-4.1.0`,
`JwtDecoderConfiguration`): `buildJwkSetUriJwtDecoder` calls `setJwtValidator(getValidator())`, and
`getValidator()` reads `getIssuerUri()` to construct `new JwtIssuerValidator(...)` next to the `aud`
validator from `getAudiences()`. Issuer **and** audience validation both stay fully enforced.

**3. The app service gets NO `healthcheck:`, deliberately.** The run image is
`ubuntu-noble-run-tiny` — no shell, no curl, no wget, no coreutils, so every `CMD`/`CMD-SHELL` form
would fail. `docker compose up --wait` therefore returns as soon as the app container *starts*, not
when it is ready. Do not read that as readiness. `scripts/e2e/wait-for.sh` already polls for a status
and is the right waiter; Task 4 uses it.

**Measured end to end through the image — a real money path, not just a health check:**

```
POST /api/v1/accounts                 -> 201
PUT  /api/v1/accounts/{id}/deposits/{uid} -> 201  SETTLED, balanceAfter EUR 10000 minor
GET  /api/v1/accounts/{id}/balance    -> 200  streamVersion 2
GET  /api/v1/accounts/{id}/transactions -> 200  the deposit
GET  /api/v1/audit/entries (as dave)  -> 200  non-empty
9090 /actuator/health/{liveness,readiness} -> 200 UP
8080 /api/v1/accounts with no token   -> 401
```

The audit entry is the load-bearing one: it can only be there if the Kafka relay published over
`kafka:29092` and the consumer read it back. Postgres, Redis and Kafka were all reached by service
name, and `host.docker.internal` appears nowhere.

**Host port 5432 collision, again.** `up` failed with `Bind for 0.0.0.0:5432 failed: port is already
allocated` — the trap `run-e2e.sh` already guards. `TINY_LEDGER_PG_PORT=55432` clears it, and note the
app is indifferent: it dials `postgres:5432` inside the network, so the host publication only matters
to host-side tools.

- [ ] **Step 4: Commit**

```bash
git add docker/docker-compose.yml
git commit -m "feat: the app is a Compose service, behind an `app` profile (#11)"
```

---

## Task 4: The e2e suite runs the image, not a host jar

**Files:** Modify `scripts/e2e/run-e2e.sh`

- [ ] **Step 1: Replace the host launch**

`scripts/e2e/run-e2e.sh:82` runs `java -jar "$JAR"`. Replace that with bringing up the `app` Compose
profile, and keep the existing readiness wait. Preserve the script's guard that refuses to start
against a partially-healthy stack — that guard exists because a fixed host port once made the app
connect to an unrelated Postgres.

**Keep the `JAR` path variable and the host-jar path behind a flag** (`E2E_MODE=jar`), because the
jar remains a supported way to run the app and deleting the only exercise of it would be silent
coverage loss.

- [ ] **Step 2: Run it locally**

```bash
bash scripts/e2e/run-e2e.sh 2>&1 | tail -30
```

Expected: `5 passed`, **not** `5 deselected` — `ledger-cli/pyproject.toml`'s `addopts` excludes the
`e2e` marker, so a run reporting `deselected` is green having tested nothing (AGENTS trap 4).

**CORRECTED 2026-08-08: the suite is SEVEN scenarios, not five.** It grew after the number was
written into this plan. The count itself was never the guard — `selected` vs `deselected` is — but a
plan asserting "5 passed" would have made a correct 7 look wrong, and a checker written against it
would fail a good run. Both modes measured:

```
E2E_MODE=image (default)  collected 59 items / 52 deselected / 7 selected   ->  7 passed, 52 deselected in 19.92s
E2E_MODE=jar              collected 59 items / 52 deselected / 7 selected   ->  7 passed, 52 deselected
E2E_MODE=nonsense         ::error::E2E_MODE must be 'image' or 'jar', got 'nonsense'
```

The `jar` run is not decoration: a preserved fallback nobody executes is dead flexibility, and it
would rot silently. It is exercised here, and the third line shows the mode guard rejects a typo
rather than falling through to a default.

- [ ] **Step 3: Commit**

```bash
git add scripts/e2e/run-e2e.sh
git commit -m "test: the e2e suite exercises the image, not a host jar (#11)"
```

---

## Task 5: CI builds the image and Trivy scans it

This closes stage 11's *"no image exists to scan"* partial.

**Files:** Modify `.github/workflows/ci.yml`

- [ ] **Step 1: Build the image in a job that HAS Docker**

`unit` deliberately runs on a runner without Docker (ADR 0003), so the image build cannot go there.
Add it to a Docker-bearing job, or a new `image` job needing `unit`.

- [ ] **Step 2: Scan it, and make the scan able to fail**

Use `aquasecurity/trivy-action`, `severity: CRITICAL,HIGH`, `exit-code: 1`, `ignore-unfixed: true`.

**`exit-code: 1` is the whole point.** A scan that reports and exits 0 is the defect this pipeline has
now hit twice — stage 6's governance check, and the Sonar job that could not fail until
`-Dsonar.qualitygate.wait=true` was added on 2026-08-07. Do not add a third.

`ignore-unfixed: true` is a deliberate trade: a CRITICAL with no available fix is not actionable and
would make the pipeline permanently red, which trains people to ignore it. **Say so in a comment**, and
say that it means the build can be green with known-unfixable criticals present.

- [ ] **Step 3: Delete the honest excuse it replaces**

`ci.yml` currently prints *"Trivy image scan: no image exists to scan"*. That line was true and is now
false — remove it in the same commit that makes it false, and update the stage-11 summary.

- [ ] **Step 4: Push, read CI, commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: build the image and scan it, closing stage 11's partial (#11)"
git push && gh run watch
```

---

## Task 5b: OWASP Dependency-Check

Added by decision on 2026-08-08. Stage 11's summary currently says it is *deferred* because it is slow
and needs a suppression pass. **Both reasons are still true** — this task pays those costs rather than
pretending they went away.

**Why it is still worth adding once Trivy exists:** Trivy scans the *image*, so it sees runtime jars
and OS packages. Dependency-Check scans the *build*, so it also sees **test-scope** dependencies.
That is the only real gap between them, and it is a small one — so the honest framing is "belt and
braces, chosen deliberately", not "Trivy was insufficient".

**Files:** Modify `pom.xml`, `.github/workflows/ci.yml`; create `.github/owasp-suppressions.xml`

- [ ] **Step 1: The plugin, pinned and bound to NOTHING**

```xml
      <!--
        OWASP Dependency-Check 13.0.0. NO <executions>, exactly like sonar-maven-plugin above and for
        the same stated reason: this is a deliberate invocation and must never ride along with
        `verify`. It downloads the NVD feed; bound to a phase it would make every local build slow
        and network-dependent, and `verify` starting to need the internet is a change nobody asked
        for.
      -->
      <plugin>
        <groupId>org.owasp</groupId>
        <artifactId>dependency-check-maven</artifactId>
        <version>13.0.0</version>
        <configuration>
          <!-- THE GATE. Without this the plugin reports and exits 0 — the third time this pipeline
               would have shipped a check that cannot fail (stage 6, then the Sonar job before
               -Dsonar.qualitygate.wait=true). 7.0 is CVSS HIGH. -->
          <failBuildOnCVSS>7</failBuildOnCVSS>
          <suppressionFiles>
            <suppressionFile>.github/owasp-suppressions.xml</suppressionFile>
          </suppressionFiles>
          <!-- The retired/experimental analysers add runtime and noise for a Java-only project. -->
          <nodeAnalyzerEnabled>false</nodeAnalyzerEnabled>
          <assemblyAnalyzerEnabled>false</assemblyAnalyzerEnabled>
        </configuration>
      </plugin>
```

- [ ] **Step 2: Create the suppression file with NO suppressions in it**

`.github/owasp-suppressions.xml` — a valid, empty ruleset plus a comment stating the rule: every
suppression names a specific CVE **and** why it does not apply here, and a blanket suppression is
never acceptable. This is the same reasoning the gitleaks step already uses for the Keycloak realm
file: no standing exemption that would silently cover future content.

- [ ] **Step 3: The CI step, with the NVD key handled the way this pipeline already handles secrets**

NVD rate-limits severely without an API key — the first update can take **tens of minutes** or fail
outright. So the step follows the exact pattern the Sonar step already uses in this file:

- If `NVD_API_KEY` is absent: **skip loudly** and say it was skipped, **not** passed. A fork's build
  must still go green — the same principle that keeps CI free of a Grafana credential.
- If present: run with `-DnvdApiKey=$NVD_API_KEY` and let `failBuildOnCVSS` bite.

**CORRECTED 2026-08-08. This plan said `-Dnvd.api.key`, and that is not a recognised property.**
Read out of `dependency-check-maven` 13.0.0's own `META-INF/maven/plugin.xml`, which declares
`<nvdApiKey implementation="java.lang.String">${nvdApiKey}</nvdApiKey>` — the flag is
**`-DnvdApiKey`**. An unrecognised `-D` is silently ignored, so the wrong spelling would have
rate-limited against an anonymous NVD and presented as a slow network rather than a
misconfiguration. Exactly the shape of the deprecated buildpack variables and step 9's
`management.otlp.tracing.*`: the plausible spelling is the dead one, and it fails quietly.

Note also that `failBuildOnCVSS` carries **no** `${...}` expression in that descriptor, so it cannot
be set from the command line at all — it lives in `pom.xml`, which is where a gate belongs anyway.

Cache the NVD data directory between runs or every build pays the download again:

```yaml
      - uses: actions/cache@v6
        with:
          path: ~/.m2/repository/org/owasp/dependency-check-data
          key: ${{ runner.os }}-owasp-nvd
```

- [ ] **Step 4: Prove the gate can fail**

A scanner that has never gone red is indistinguishable from one that cannot. On a throwaway branch,
temporarily lower `failBuildOnCVSS` to `0` and confirm the job fails; restore it. **Record the exact
failure line in the commit message.** This repository has shipped two gates that could not fail; do
not make it three.

- [ ] **Step 5: Update the stage 11 summary and commit**

Delete the "dependency-check: deferred" line — it is now false. Say what is covered and what is not:
Trivy for the image, Dependency-Check for the build including test scope, and **that both are skipped
rather than failed when their credentials are absent.**

---

## Task 5c: OWASP ZAP — deferred to the TLS plan, on purpose

**Not in this plan, and this is the reason rather than an omission.** A ZAP baseline scan reports
missing HSTS, HTTP→HTTPS redirect behaviour, TLS version and cipher posture, and cookie flags —
**most of its first report would be precisely the list the TLS work is about to configure.** Running
it now produces a page of findings that are already scheduled to be fixed, which is how a scanner
teaches people to ignore it.

Recorded so it is not lost: `zaproxy/action-baseline@v0.15.0` (already `node24`, consistent with the
action bump merged 2026-08-07). It needs a running app, so it depends on **Task 3** of this plan.

---

## Task 6: The documents, and close #11

**Do not start until Task 5 is green on CI.**

**Files:** `docs/spec.md` (§1 mode table line 26, §12 line ~1796, §12.1 stage 11, §14 if it lists this, revision history, **and the header version — `grep -n 'Version:' docs/spec.md`**), `README.md:129`, `CHANGELOG.md`, `docs/INDEX.md` (carries the spec version)

- [ ] **Step 1: §1's mode table** — `full` is no longer two steps and the app **is** a Compose service. That row currently says the opposite, in bold.
- [ ] **Step 2: §12** — replace *"Container image: NOT BUILT"* with what exists. **Claim only what is true**: buildpacks, layered, AOT cache, Trivy-scanned in CI, not published. **Do not** re-assert non-root, read-only rootfs or no-shell unless each is verified against the built image — that exact overclaim is what v3.36 retracted, and repeating it would be the same defect twice.

  Verify before claiming:
  ```bash
  docker run --rm --entrypoint /bin/sh tiny-ledger:0.1.0-SNAPSHOT -c "id -u" 2>&1 | tail -1
  ```
  A non-zero uid supports "non-root"; a shell that runs at all disproves "no shell".
- [ ] **Step 3: §12.1 stage 11** — partial → complete, citing the run id.
- [ ] **Step 4: Revision history row + the header version.**
- [ ] **Step 5: README** — the `full` recipe becomes one command.
- [ ] **Step 6: Close #11** with the evidence, and note that the TLS work is now unblocked.

---

## What this unblocks, and what it deliberately leaves

**Unblocked:** the TLS plan. With an `app` service, Traefik routes to `app:8080` by name, and the
`host.docker.internal` seam — the single most likely thing to have bitten — never exists. **And the
OWASP ZAP baseline scan** (Task 5c), which needs a running app and wants TLS configured first so its
first report is not a list of things already scheduled.

**Deliberately not here:** publishing to a registry (stage 12, a separate decision), GraalVM native and
CRaC (deferred with reasons at v3.36), and Kubernetes manifests (ADR 0005 wrote none on purpose).
