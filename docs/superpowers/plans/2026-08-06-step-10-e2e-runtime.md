# Step 10 — full-profile e2e runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.

**Goal:** Give the five real, unmocked e2e tests in `ledger-cli/tests/test_e2e_scenarios.py` somewhere
to actually run — a `full`-profile runtime (Postgres, Redis, Kafka, **Keycloak**, and the application
itself) plus the CI stages that drive them.

**Architecture:** Compose gains a Keycloak service on a fixed port so the `full` profile's default
`issuer-uri` resolves without an override. CI builds the existing Spring Boot jar, starts it as a
background process against those services, waits for a **401** on a protected route — which proves both
that the app is listening and that the security chain is live — then runs the pinned Python toolchain
and finally the `e2e` marker. No Dockerfile: the runner already has the jar and a JDK, and a second
build artifact is one more thing to keep true.

**Tech stack:** Docker Compose, Keycloak 26.4, Spring Boot 4.1 (Java 25), `uv` + Python 3.11, pytest,
GitHub Actions.

---

## Why this plan exists in this shape

Three facts were measured on the tree before writing this. They are the whole reason the work is not
just "add a CI job":

1. **`application-full.properties:15`** sets
   `issuer-uri=${LEDGER_ISSUER_URI:http://localhost:8081/realms/tiny-ledger}` unconditionally, but
   **nothing in this repository has ever started a Keycloak on 8081.** Keycloak exists only inside
   `AbstractIntegrationTest`, under Testcontainers, on a random mapped port. A `full` boot on a host
   today has no issuer to talk to. Compose must supply one, and on **8081**, so the documented default
   is true rather than merely plausible.
2. **Nothing in the tree starts the application as a process** — no Dockerfile, no script, no compose
   service, no CI step. `README.md:104-105` and `docker/docker-compose.yml:3-5` document
   `./mvnw spring-boot:run` for a human; that is documentation, not a runtime.
3. **The five e2e tests need no edits.** They take everything from `Settings()`, which is
   pydantic-settings with `env_prefix="LEDGER_"` (`ledger-cli/src/ledger_cli/config.py:18`). The CI job
   configures them entirely through `LEDGER_*` environment variables. **Do not modify the tests to make
   them pass** — a test edited to fit its runtime stops being evidence about the runtime.

### The rate-limit collision — read before Task 4

`ledger.rate-limit.exempt-ips` is **empty in `full`** (it is set only in
`application-standalone.properties:29`). So an e2e run from a single source IP is metered by the
per-IP backstop of **300/minute** (`application.properties:42`) and the per-principal write bucket of
**100/minute** (`:30`).

`test_rate_limit` deliberately exhausts the write bucket to prove the 429 and `Retry-After` are real.
That is ~100 writes, and the other four scenarios add roughly 30 more requests — about 130 against a
300 backstop. It fits, but not by much, and a retry or an added assertion could push it over. A 429
from the *backstop* would look exactly like the 429 the test wants, and the test would pass for the
wrong reason.

**Ruling: raise the IP backstop for the e2e run only, and leave the per-principal write bucket at its
production 100.** The backstop is not what `test_rate_limit` exercises; the write bucket is. Raising
the one the test does not use removes the false-pass risk without weakening the test. Do it with an
environment variable at app start, never by editing a properties file — production defaults must stay
untouched.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `docker/docker-compose.yml` | add a `keycloak` service on 8081 importing the existing realm | 1 |
| `scripts/e2e/wait-for.sh` | one readiness helper: poll a URL until it returns an expected status | 2 |
| `scripts/e2e/run-e2e.sh` | start app, wait, run pytest, tear down, always dump logs | 3 |
| `.github/workflows/ci.yml` | stage 8 (Python quality) and stage 9 (e2e) jobs | 4, 5 |
| `docs/spec.md` | §12.1 stages 8 and 9 flip from unbuilt to built | 6 |
| `README.md` | the `full` run recipe stops being incomplete | 6 |

`scripts/` was emptied when `scripts/ci/check_docs_governance.py` was deleted; this recreates it.

---

## Task 1: Keycloak in Compose, on the port the default already names

**Files:**
- Modify: `docker/docker-compose.yml`

- [ ] **Step 1: Read the working container recipe rather than inventing one**

`src/test/java/com/ffroliva/tinyledger/testsupport/AbstractIntegrationTest.java:52-63` already starts
this exact image successfully in CI. Copy its shape — image tag, bootstrap admin env vars, realm mount
path, command, and the readiness URL it waits on:

```java
new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.4"))
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
        .withCopyFileToContainer(
                MountableFile.forHostPath("docker/keycloak/realm-tiny-ledger.json"),
                "/opt/keycloak/data/import/realm-tiny-ledger.json")
        .withCommand("start-dev", "--import-realm")
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/realms/tiny-ledger/.well-known/openid-configuration").forPort(8080))
```

- [ ] **Step 2: Add the service**

Append to `docker/docker-compose.yml`, matching the file's existing two-space indentation and its
healthcheck style (`interval: 5s`, `timeout: 5s`, `retries: 10`):

```yaml
  # The `full` profile's issuer-uri defaults to localhost:8081 (application-full.properties:15).
  # The port mapping below is what makes that default true rather than aspirational.
  # TEST FIXTURE realm — dev-only credentials, never deploy. See docker/keycloak/realm-tiny-ledger.json.
  keycloak:
    image: quay.io/keycloak/keycloak:26.4
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_HEALTH_ENABLED: "true"
    volumes:
      - ./keycloak/realm-tiny-ledger.json:/opt/keycloak/data/import/realm-tiny-ledger.json:ro
    ports:
      - "8081:8080"
    healthcheck:
      # Keycloak's image carries no curl or wget. Bash's /dev/tcp is always present.
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/8080 && echo -e 'GET /realms/tiny-ledger/.well-known/openid-configuration HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q 'issuer' <&3"]
      interval: 5s
      timeout: 5s
      retries: 20
```

The volume path is relative to `docker/`, matching how the file is invoked
(`docker compose -f docker/docker-compose.yml`).

- [ ] **Step 3: Update the file's header comment**

`docker/docker-compose.yml:1-5` currently says the app is not a service and lists three services.
Keycloak is now a fourth. Keep the "app is not a service here" statement — it is still true and still
load-bearing — but stop implying the stack is only infrastructure the app talks to.

- [ ] **Step 4: Prove it starts and imports the realm**

```bash
docker compose -f docker/docker-compose.yml up -d keycloak
```

Then poll until the discovery document is served, and assert the realm is the right one:

```bash
curl -sf http://localhost:8081/realms/tiny-ledger/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
```

Expected: `"issuer":"http://localhost:8081/realms/tiny-ledger"`

**This is the whole point of the task — if the issuer string does not match
`application-full.properties:15`'s default exactly, the app will reject every token and Task 3 will
fail in a way that looks like an auth bug.** Record the exact output.

Then confirm the fixture users imported:

```bash
curl -sf -d "client_id=ledger-test" -d "username=alice" -d "password=dev-only" -d "grant_type=password" \
  http://localhost:8081/realms/tiny-ledger/protocol/openid-connect/token | head -c 60
```

Expected: a JSON object beginning `{"access_token":"eyJ`.

- [ ] **Step 5: Tear down and commit**

```bash
docker compose -f docker/docker-compose.yml down
git add docker/docker-compose.yml
git commit -F - <<'EOF'
feat(e2e): Compose starts Keycloak on 8081, the port the full profile already names

application-full.properties:15 has always defaulted issuer-uri to
localhost:8081/realms/tiny-ledger, and nothing in this repository ever started a
Keycloak there — it existed only under Testcontainers on a random port. A hand-run
or CI-run `full` boot had no issuer to talk to.

Container shape copied from AbstractIntegrationTest:52-63, which already starts
this image successfully in CI, rather than invented. The healthcheck uses bash
/dev/tcp because the Keycloak image ships neither curl nor wget.
EOF
```

---

## Task 2: A readiness helper that waits for a status, not a sleep

**Files:**
- Create: `scripts/e2e/wait-for.sh`

A fixed `sleep` is the classic source of a flaky pipeline: too short and it fails under load, too long
and every run pays for it. Poll for the condition instead.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Poll a URL until it answers with an expected HTTP status, or give up.
#
# Usage: wait-for.sh <url> <expected-status> <timeout-seconds> <label>
#
# Why a status and not "is the port open": a listening socket proves a process bound
# it, not that the application is serving. For the `full` profile an unauthenticated
# request to a protected route must answer 401 — which proves the app is up AND that
# the security chain is wired. A 200 there would itself be a defect.
set -euo pipefail

url=$1; expected=$2; timeout=$3; label=$4
deadline=$(( SECONDS + timeout ))

until [ "$SECONDS" -ge "$deadline" ]; do
  actual=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" || echo 000)
  if [ "$actual" = "$expected" ]; then
    echo "$label ready after ${SECONDS}s (HTTP $actual)"
    exit 0
  fi
  sleep 2
done

echo "::error::$label never returned HTTP $expected within ${timeout}s (last: ${actual:-none})" >&2
exit 1
```

- [ ] **Step 2: Make it executable and prove both branches**

```bash
chmod +x scripts/e2e/wait-for.sh
git update-index --chmod=+x scripts/e2e/wait-for.sh
```

Prove the **failure** path first — a script that has only ever succeeded is untested:

```bash
./scripts/e2e/wait-for.sh http://127.0.0.1:9/nothing 200 6 "unbound port"; echo "exit=$?"
```

Expected: fails after ~6s, prints the `::error::` line, `exit=1`.

Then the success path against something certainly running:

```bash
docker compose -f docker/docker-compose.yml up -d keycloak
./scripts/e2e/wait-for.sh http://localhost:8081/realms/tiny-ledger/.well-known/openid-configuration 200 90 "keycloak"; echo "exit=$?"
docker compose -f docker/docker-compose.yml down
```

Expected: `keycloak ready after Ns (HTTP 200)`, `exit=0`.

- [ ] **Step 3: Commit**

```bash
git add scripts/e2e/wait-for.sh
git commit -F - <<'EOF'
feat(e2e): poll for a readiness status instead of sleeping

A fixed sleep is too short under CI load and wasted otherwise. Polls for an exact
HTTP status: a listening socket proves a process bound the port, not that the
application is serving.

Both branches proven: the timeout path against an unbound port, the success path
against Keycloak.
EOF
```

---

## Task 3: Start the app, run the e2e tests, always surface the logs

**Files:**
- Create: `scripts/e2e/run-e2e.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Start the built jar under the `full` profile against a running Compose stack,
# run the e2e marker, and tear down. Always dumps the app log — a failed e2e run
# whose cause is in a log nobody printed costs more than the run itself.
set -euo pipefail

JAR=${JAR:-target/tiny-ledger-0.1.0-SNAPSHOT.jar}
BASE_URL=${LEDGER_BASE_URL:-http://127.0.0.1:8080}
APP_LOG=${APP_LOG:-app.log}

[ -f "$JAR" ] || { echo "::error::$JAR not found — build it first with ./mvnw -q -DskipTests package" >&2; exit 1; }

cleanup() {
  local rc=$?
  echo "--- application log ---"
  cat "$APP_LOG" 2>/dev/null || echo "(no log)"
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  exit $rc
}
trap cleanup EXIT

# The per-IP backstop is 300/min and exempt-ips is empty in `full`, so an entire e2e
# run is metered as one source IP. test_rate_limit deliberately exhausts the
# PER-PRINCIPAL write bucket (100/min) to prove the 429 is real; if the backstop
# fired instead, that test would pass for the wrong reason. Raise only the backstop.
# Production defaults are untouched — this is a launch argument, not a file edit.
java -jar "$JAR" \
  --spring.profiles.active=full \
  --ledger.rate-limit.ip-backstop.capacity=10000 \
  > "$APP_LOG" 2>&1 &
APP_PID=$!

# 401 is the ready signal: the app is serving AND the security chain is live.
scripts/e2e/wait-for.sh "$BASE_URL/api/v1/accounts" 401 120 "tiny-ledger (full)"

cd ledger-cli
uv run pytest -m e2e -v
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x scripts/e2e/run-e2e.sh
git update-index --chmod=+x scripts/e2e/run-e2e.sh
```

- [ ] **Step 3: Prove the guard fires when the jar is absent**

```bash
JAR=/nonexistent.jar ./scripts/e2e/run-e2e.sh; echo "exit=$?"
```

Expected: the `::error::` line about the missing jar, `exit=1`, and **no java process started**.

- [ ] **Step 4: Commit**

```bash
git add scripts/e2e/run-e2e.sh
git commit -F - <<'EOF'
feat(e2e): run the five unmocked e2e tests against a real full-profile app

Starts the built jar against the Compose stack, waits for 401 on a protected route
(app serving AND security chain live), runs the e2e marker, always dumps the app log.

Raises ONLY the per-IP backstop, by launch argument. exempt-ips is empty in `full`,
so a whole e2e run meters as one source IP against 300/min; test_rate_limit
deliberately exhausts the per-principal write bucket (100/min) to prove the 429, and
a backstop 429 would make it pass for the wrong reason. The bucket the test actually
exercises keeps its production value.
EOF
```

---

## Task 4: CI stage 8 — the Python toolchain gate

**Files:**
- Modify: `.github/workflows/ci.yml`

Spec §12.1 stage 8 is the Python CLI gate: lint, type-check, unit tests. It is separate from stage 9 so
a lint failure does not spend four containers to discover itself.

- [ ] **Step 1: Add the job**

Append after the `security` job. Note there is **no `setup-python` step anywhere in this workflow
today**; `uv` supplies the interpreter, which is why `python-version` is pinned in one place only —
`ledger-cli/pyproject.toml` already sets `requires-python = ">=3.11"`.

```yaml
  # Stage 8 — the Python CLI's own gate. Deliberately does not need Docker: a ruff or
  # pyright failure should not cost a four-container stack to discover.
  cli:
    runs-on: ubuntu-latest
    needs: gate
    defaults:
      run:
        working-directory: ledger-cli
    steps:
      - uses: actions/checkout@v4
      - name: Install uv
        uses: astral-sh/setup-uv@v5
        with:
          enable-cache: true
      - name: Sync the pinned environment
        run: uv sync --locked
      - name: Stage 8a — ruff
        run: uv run ruff check .
      - name: Stage 8b — pyright (strict)
        run: uv run pyright
      - name: Stage 8c — unit tests
        run: uv run pytest -v
      - name: Unit test count — a green build that ran nothing is not green
        run: |
          n=$(uv run pytest --collect-only -q 2>/dev/null | tail -1 | grep -oE '^[0-9]+' || echo 0)
          echo "### CLI tests collected: $n" >> "$GITHUB_STEP_SUMMARY"
          if [ "$n" -eq 0 ]; then echo "::error::pytest collected zero tests"; exit 1; fi
```

`uv sync --locked` fails if `uv.lock` is stale rather than silently resolving something else — the
Python equivalent of the reproducibility this repository already demands of Maven.

The zero-count guard mirrors the existing `unit` and `integration` jobs. AGENTS trap 4: a run that
matched nothing exits 0 and looks identical to a green one.

- [ ] **Step 2: Verify the workflow still parses**

```bash
python -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/ci.yml')); print(sorted(d['jobs']))"
```

Expected: `['cli', 'gate', 'integration', 'security', 'unit']`

- [ ] **Step 3: Commit and push**

```bash
git add .github/workflows/ci.yml
git commit -F - <<'EOF'
ci: stage 8 — the Python CLI gate (ruff, pyright strict, pytest)

Spec §12.1 stage 8, which has never run. Needs no Docker, so a lint failure does not
cost a container stack. `uv sync --locked` fails on a stale lockfile rather than
resolving something else.

Carries the same zero-count guard as the unit and integration jobs: a pytest run that
collected nothing exits 0 and is indistinguishable from a green one (AGENTS trap 4).
EOF
git push
```

- [ ] **Step 4: Read the result from CI, paired with its conclusion**

```bash
gh run watch
gh run view --json conclusion -q .conclusion
```

Expected: `success`, and the step summary shows a non-zero collected count.

> **If GitHub Actions is in an outage** (it was during Plan 4's close-out), record that the run is
> owed and unrun. Do **not** claim a green you did not observe.

---

## Task 5: CI stage 9 — the e2e job

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add the job**

```yaml
  # Stage 9 — the five real, unmocked e2e scenarios against a running `full` stack.
  # These tests were written in f2c9965 and had never executed: there was no Keycloak
  # on 8081 and nothing started the app. This job is that runtime.
  e2e:
    runs-on: ubuntu-latest
    needs: [gate, cli]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: corretto
          java-version: 25
          cache: maven
      - uses: astral-sh/setup-uv@v5
        with:
          enable-cache: true
      - name: Start the full stack (Postgres, Redis, Kafka, Keycloak)
        run: docker compose -f docker/docker-compose.yml up -d --wait
      - name: Build the application jar
        run: ./mvnw -q -DskipTests package
      - name: Stage 9 — e2e scenarios
        env:
          LEDGER_PROFILE: full
          LEDGER_BASE_URL: http://127.0.0.1:8080
          LEDGER_ISSUER_URI: http://localhost:8081/realms/tiny-ledger
          LEDGER_CLIENT_ID: ledger-test
          LEDGER_USERNAME: alice
          LEDGER_PASSWORD: dev-only
        run: |
          cd ledger-cli && uv sync --locked && cd ..
          ./scripts/e2e/run-e2e.sh
      - name: Compose logs on failure
        if: failure()
        run: docker compose -f docker/docker-compose.yml logs --no-color --tail 200
      - name: Stop the stack
        if: always()
        run: docker compose -f docker/docker-compose.yml down -v
```

`--wait` makes Compose block on the healthchecks rather than returning as soon as containers are
created — which is why Task 1's healthcheck had to be real.

`LEDGER_PASSWORD` is the realm fixture's `dev-only`, committed and marked
`TEST FIXTURE — never deploy`. It is not a secret and must not be moved into GitHub Secrets, where it
would imply it is one.

- [ ] **Step 2: Verify the workflow parses and the dependency graph resolves**

```bash
python - <<'PY'
import yaml
d = yaml.safe_load(open('.github/workflows/ci.yml'))
jobs = d['jobs']
print(sorted(jobs))
for name, j in jobs.items():
    needs = j.get('needs', [])
    needs = [needs] if isinstance(needs, str) else needs
    missing = [n for n in needs if n not in jobs]
    assert not missing, f"{name} needs missing job(s): {missing}"
print("all needs resolve")
PY
```

Expected: `['cli', 'e2e', 'gate', 'integration', 'security', 'unit']` then `all needs resolve`.

- [ ] **Step 3: Commit and push**

```bash
git add .github/workflows/ci.yml
git commit -F - <<'EOF'
ci: stage 9 — run the five e2e scenarios against a real full-profile app

These tests shipped in f2c9965 and had never executed once: nothing started a
Keycloak on 8081 and nothing ran the app as a process. Spec §12.1 stage 9 named the
job; this builds the runtime it needed.

Compose --wait blocks on the healthchecks rather than returning when containers are
merely created. The realm password is the committed TEST FIXTURE value and stays in
plain env deliberately: putting a published dev-only credential in Secrets would
imply it is one.
EOF
git push
```

- [ ] **Step 4: Read the result — and check WHICH tests ran**

```bash
gh run watch
gh run view --log | grep -E "PASSED|FAILED|passed|failed|deselected"
```

Expected: **5 passed**, zero deselected.

**A green stage 9 that ran zero tests is the exact failure this repository has already paid for.**
`addopts` in `ledger-cli/pyproject.toml` is `-m 'not e2e and not live'`, and `run-e2e.sh` overrides it
with `-m e2e`. If the output says `5 deselected`, the override did not take and the job is green
having tested nothing. Confirm the count is **5 passed**, not merely that the job is green.

---

## Task 6: Make the documentation true — MINIMAL, and coordinate first

**Files:**
- Modify: `docs/spec.md` (§12.1 stages 8 and 9)
- Modify: `README.md` (the `full` run recipe)

> **COORDINATION:** another agent is editing documentation concurrently, `README.md` in particular.
> **Before touching either file, check with the orchestrator.** If the other agent owns them, hand
> over the two facts below instead of editing, and say so in your report. Two agents editing one
> Markdown file produces a conflict neither can resolve correctly.

This repository's rule is that spec text lands in the same commit as the code it describes. That rule
and the concurrent-edit risk are in genuine tension here; the resolution is to keep this edit as small
as the rule allows, not to skip it.

- [ ] **Step 1: §12.1 — flip stages 8 and 9**

Both rows currently say the stage does not exist. They now do. Cite the job names (`cli`, `e2e`) and
the script, exactly as the other rows cite theirs. Note that stage 6 is struck as removed — do **not**
renumber around it.

- [ ] **Step 2: §12.1 prose — correct the count of unbuilt stages**

The prose states how many of the twelve stages run. Two more do now. **Re-read the sentence; do not
grep for a phrase you may have just changed** (AGENTS trap 4).

- [ ] **Step 3: `README.md` — the `full` recipe is no longer incomplete**

It currently tells a reader to start three infrastructure services and warns that nothing provides an
issuer. Compose now starts Keycloak on 8081 and the default resolves. Correct it, and keep the
statement that the app itself is not a Compose service — still true.

- [ ] **Step 4: Verify and commit**

```bash
./mvnw -q verify   # must exit 0; docs do not affect it, but the gate is the gate
git add docs/spec.md README.md
git commit -F - <<'EOF'
docs: stages 8 and 9 exist now, and the full recipe starts an issuer

§12.1 listed both as unbuilt and README warned that a `full` boot had no issuer to
talk to. Compose starts Keycloak on 8081, which is the port
application-full.properties:15 has always defaulted to.
EOF
```

---

## Definition of done

- `docker compose -f docker/docker-compose.yml up -d --wait` brings up four healthy services.
- CI shows a `cli` job and an `e2e` job, both green, **with `5 passed` and zero deselected**.
- The five e2e tests are unmodified — `git diff f2c9965 -- ledger-cli/tests/test_e2e_scenarios.py` is
  empty.
- `./mvnw -q verify` still exits 0 and still starts **zero** containers.
- No production properties file changed. `git diff --stat` shows nothing under
  `src/main/resources/`.

## Explicitly out of scope

- **The `pytest-bdd` catalogue and README-curl extraction.** Spec §11 describes larger mechanisms;
  `5fae157` already records them as unbuilt. Five sequence tests do not complete them, and this plan
  must not imply otherwise.
- **A Dockerfile / an `app` Compose service.** The runner has the jar and a JDK. A second packaging
  artifact is one more thing that can stop being true.
- **The `ledger-cli` service account.** The CLI authenticates by password grant against `ledger-test`
  because the realm has no confidential client (`ledger-cli/src/ledger_cli/auth.py:3-7`). §6.4's
  preface already disclaims that table as intent. Changing the realm is not this plan's job.
- **Step 11** (Gatling, JMH, §9.7 thresholds) — its own plan.
