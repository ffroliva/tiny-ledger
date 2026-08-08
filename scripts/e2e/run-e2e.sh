#!/usr/bin/env bash
# Start the application under the `full` profile against a running Compose stack, run
# the e2e marker, and tear down. Always dumps the application log: a failed e2e run
# whose cause sits in a log nobody printed costs more than the run itself.
#
# Expects `docker compose -f docker/docker-compose.yml up -d --wait` to have brought
# up Postgres, Redis, Kafka and Keycloak already. Run from the repository root.
set -euo pipefail

# Issue #11. The artefact under test is now the IMAGE, not a jar on the host — which is
# the point of the issue: what CI exercises should be what would be deployed.
#
#   E2E_MODE=image   (default) the tiny-ledger image as a Compose service
#   E2E_MODE=jar               the host jar, exactly as before
#
# The jar path is KEPT rather than deleted. Running the jar directly is still a
# supported way to use this project (README, spec §1.5), and deleting the only thing
# that ever exercised it would be silent coverage loss — the failure mode would be a
# broken `java -jar` nobody notices until a human tries it.
E2E_MODE=${E2E_MODE:-image}

# -exec, because the root pom gives spring-boot-maven-plugin a classifier so the plain
# jar stays usable as a dependency (benchmarks/ compiles against it). The runnable,
# repackaged jar is the one with the classifier.
JAR=${JAR:-target/tiny-ledger-0.1.0-SNAPSHOT-exec.jar}
APP_LOG=${APP_LOG:-app.log}
READY_TIMEOUT=${READY_TIMEOUT:-120}

# The two modes now differ in TRANSPORT as well as in artefact, and that is the honest topology
# rather than a compromise:
#
#   image  -> through Traefik, over real HTTPS, against a certificate chaining to the dev CA. This
#             is the deployed shape: an ingress terminates TLS and the application never sees it.
#   jar    -> plaintext, straight to the process. This is what `java -jar` actually is (README,
#             spec §1.5) — there is no proxy in that recipe, so pretending there is one would test
#             a topology nobody runs.
#
# 127.0.0.1, never `localhost`, for the reason spelled out below — and the dev certificate carries
# IP:127.0.0.1 in its SAN list precisely so this pinning does not break verification.
HTTPS_PORT=${TINY_LEDGER_HTTPS_PORT:-443}
if [ "${E2E_MODE:-image}" = image ]; then
  BASE_URL=${LEDGER_BASE_URL:-https://127.0.0.1:${HTTPS_PORT}}
else
  BASE_URL=${LEDGER_BASE_URL:-http://127.0.0.1:8080}
fi
# Hand the decision back to the CLI. Before TLS both sides could hardcode http://127.0.0.1:8080
# and agree by accident; now the URL depends on E2E_MODE, so one of them has to own it and the
# other has to follow. The runner owns it because the runner is what chooses the topology — and
# ci.yml no longer sets LEDGER_BASE_URL at all, precisely so there is nothing to drift.
# The ${LEDGER_BASE_URL:-...} above still honours an explicit override, so this only ever
# re-exports a value the caller either supplied or did not care about.
export LEDGER_BASE_URL="$BASE_URL"

# uv is not used until the LAST line of this script, which is exactly why it is checked on the
# FIRST. Without this guard a missing uv is discovered after the image is built, the app service
# is up and the readiness poll has passed — and the EXIT trap then dumps the whole application
# log on top of the one line that said `uv: command not found`, so the cause scrolls past and a
# missing tool reads as an application failure. That is the same class of bug as the relative
# APP_LOG the subshell below fixes: the run's real cause present but unreadable.
#
# Checked before the E2E_MODE case on purpose. Every other guard here reports something that
# takes a build to repair; this one takes an install, and there is no reason to learn about it
# after a ~90 s buildpack run.
if ! command -v uv >/dev/null 2>&1; then
  echo "::error::uv not found — the e2e suite is pytest driven by the Python CLI in ledger-cli/, so this script cannot run without it" >&2
  echo "Install: https://docs.astral.sh/uv/getting-started/installation/ — uv provisions its own Python (3.11+), so Python is not a separate install." >&2
  exit 1
fi

case "$E2E_MODE" in
  jar)
    if [ ! -f "$JAR" ]; then
      echo "::error::$JAR not found — build it first: ./mvnw -q -DskipTests package" >&2
      exit 1
    fi
    # The CA is needed in THIS mode too, and that surprises people. The application is
    # plaintext on the host here, but KEYCLOAK IS STILL BEHIND TRAEFIK -- one ingress, one
    # certificate story -- so both the host JVM and the Python client speak TLS to the
    # identity provider. The JVM gets the PKCS12 truststore (see the launch below); httpx gets
    # SSL_CERT_FILE, exactly as in image mode.
    scripts/tls/gen-dev-ca.sh
    export SSL_CERT_FILE="$PWD/docker/tls/ca.crt"
    ;;
  image)
    # Compose has no `build:` for this service on purpose (see docker-compose.yml), so a
    # missing image is not something `up` can repair. Say which command produces it rather
    # than letting compose fail with "pull access denied", which reads as a registry problem.
    #
    # The tag is a LITERAL, matching docker-compose.yml's `image:` exactly, and is deliberately
    # not an overridable variable. An $APP_IMAGE knob would move only this check while compose
    # still started the hardcoded tag — a guard that passes on one image while another runs is
    # worse than no guard.
    if ! docker image inspect tiny-ledger:0.1.0-SNAPSHOT >/dev/null 2>&1; then
      echo "::error::tiny-ledger:0.1.0-SNAPSHOT not found — build it first: ./mvnw -q spring-boot:build-image -DskipTests" >&2
      exit 1
    fi

    # Generate the dev CA if it is not there. Idempotent, so a developer's existing certificate
    # survives — and CI, which starts from a clean checkout, gets a fresh throwaway one every run
    # and therefore needs NO certificate secret. Same principle that keeps the Grafana token out
    # of CI entirely (docs/security-material.md).
    scripts/tls/gen-dev-ca.sh

    # The client that speaks to the application needs to trust that CA, and needs no code change
    # to do it — this is the standard variable its TLS stack already reads:
    #
    # SSL_CERT_FILE is what the Python CLI's TLS stack reads. Measured on httpx 0.28.1: with this
    # set, the client's store reports {'x509': 1, 'x509_ca': 1} — this CA and nothing else.
    #
    # It REPLACES the public trust store rather than adding to it, which is fine here: the only
    # host the CLI talks to over TLS is this stack. If it were missing, the run would fail with a
    # certificate-verification error — loudly, never a pass that skipped the check.
    #
    # CURL_CA_BUNDLE is deliberately NOT set alongside it. Nothing here uses curl over TLS any
    # more (see the readiness step below), and a variable that configures nothing is a knob that
    # lies about what is in effect.
    export SSL_CERT_FILE="$PWD/docker/tls/ca.crt"
    ;;
  *)
    echo "::error::E2E_MODE must be 'image' or 'jar', got '$E2E_MODE'" >&2
    exit 1
    ;;
esac

# Refuse to start against a stack that is not fully up. Without this the app boots
# anyway and connects to whatever else holds the port — measured: with an unrelated
# Postgres on 5432 our container stayed in `Created` while the app connected to the
# OTHER database and failed Liquibase on credentials. Different credentials are the
# only reason that was visible; a matching ledger/ledger user elsewhere would have
# been read and written silently. Fail here, loudly, with the likely cause named.
#
# THE GUARD NAMES THE FOUR BACKING SERVICES EXPLICITLY, and that is a fix rather than a
# tidy-up. It used to check every container in the project, which was right when the app
# was never one of them. It is not any more: `docker compose ps -a` lists containers from
# INACTIVE profiles too (verified on Docker 28.3.0 / Compose v2.38.1 — a profiled service
# shows up as `extra Up 8 seconds` with no `--profile` given), and the `app` service has no
# healthcheck by necessity, since its run image ships no shell to run one.
#
# So the old form aborted on the app's own container. Anyone following the README's new
# recipe — build-image, then `--profile app up -d`, then this script — was told "the full
# stack is not healthy" and pointed at Postgres, which was fine. The same lockout happened
# after any run whose EXIT trap did not fire (Ctrl-C, SIGKILL, a cancelled CI job), and to
# anyone using `--profile observability`, because otel-collector has no healthcheck either.
COMPOSE="docker compose -f docker/docker-compose.yml"
unhealthy=$($COMPOSE ps -a --format '{{.Service}} {{.Status}}' 2>/dev/null \
  | grep -E '^(postgres|redis|kafka|keycloak) ' | grep -v '(healthy)' || true)
if [ -n "$unhealthy" ]; then
  echo "::error::the full stack is not healthy — refusing to run e2e against a partial stack" >&2
  echo "$unhealthy" >&2
  echo "A service stuck in 'Created' usually means its host port is already taken." >&2
  echo "Postgres is the usual culprit; set TINY_LEDGER_PG_PORT to a free port and retry." >&2
  exit 1
fi

# START FROM A KNOWN RATE-LIMIT STATE. The buckets live in Redis (RateLimitConfig, `full` profile)
# and `test_rate_limit` deliberately EXHAUSTS alice's 100/minute write bucket to prove the 429 and
# its Retry-After are real. So a second run of this suite inside the same minute begins with that
# bucket already empty and fails on its first write, for a reason nothing in the output names.
#
# That is not hypothetical: it is exactly how CI failed the first time both legs ran in one job —
# stage 9 (image) passed, stage 9b (jar) ran seconds later against the same Redis, and
# test_rate_limit came back `429 /errors/rate-limit-exceeded` before it had made a single
# deliberate over-limit call.
#
# The flush targets the COMPOSE service by name, never a host or a URL, so it cannot reach anything
# but this stack's throwaway Redis. What it clears is rate-limit buckets and the balance cache —
# both derived, neither a system of record. The event store is Postgres.
$COMPOSE exec -T redis redis-cli FLUSHALL >/dev/null 2>&1 || true

APP_PID=""
cleanup() {
  rc=$?
  # Traefik is removed in BOTH modes, because both start it: image mode routes the application
  # through it, and jar mode still needs it in front of Keycloak. The four backing services stay
  # up for whoever brought them up.
  $COMPOSE --profile app rm -sf traefik >/dev/null 2>&1 || true
  if [ "$E2E_MODE" = image ]; then
    echo "--- application log (compose service 'app') ---"
    # stderr is NOT discarded, and that matters more here than it looks. compose demultiplexes
    # the container's stderr to the CLI's stderr, which is exactly where the buildpack launcher's
    # `failed to launch: ...`, `Error occurred during initialization of VM` and OOM-kill messages
    # go — the most likely image failures. `2>/dev/null` would print this header and then nothing,
    # defeating the reason this trap exists (see the file header). There is no `|| echo` fallback
    # because `docker compose logs <service>` exits 0 even when no container exists, so the branch
    # could never fire; the header plus empty output is the honest signal instead.
    $COMPOSE --profile app logs --no-color app traefik 2>&1 || true
    # `rm -sf`, not `stop`: the guard above rejects any container that is not (healthy), and a
    # stopped app container would trip it on the NEXT run. The app is the only service this
    # script started, so it is the only one it removes — the four backing services stay up for
    # whoever brought them up.
    $COMPOSE --profile app rm -sf app traefik >/dev/null 2>&1 || true
  else
    echo "--- application log ($APP_LOG) ---"
    cat "$APP_LOG" 2>/dev/null || echo "(no application log was produced)"
    if [ -n "$APP_PID" ]; then
      kill "$APP_PID" 2>/dev/null || true
      wait "$APP_PID" 2>/dev/null || true
    fi
  fi
  exit $rc
}
trap cleanup EXIT

# The per-IP backstop is 300/min and `ledger.rate-limit.exempt-ips` is EMPTY in the
# `full` profile — it is set only in application-standalone.properties. So an entire
# e2e run meters as one source IP.
#
# test_rate_limit deliberately exhausts the PER-PRINCIPAL write bucket (100/min) to
# prove the 429 and Retry-After are real. If the IP backstop fired first, that test
# would still see a 429 and would pass for the wrong reason — the most expensive kind
# of green. So raise ONLY the backstop, and leave the write bucket at its production
# value, because the write bucket is the one under test.
#
# This is a launch argument, never a properties-file edit: production defaults stay
# untouched and this override cannot leak into a real deployment.
PG_PORT=${TINY_LEDGER_PG_PORT:-5432}

# Every host below is 127.0.0.1, never `localhost`, and that is load-bearing rather
# than stylistic. Measured on a Windows host: `localhost` resolves to ::1 first,
# Docker Desktop publishes on both 0.0.0.0 and [::], but the IPv6 path does not
# route — `/dev/tcp/::1/6379` times out while `/dev/tcp/127.0.0.1/6379` is open.
#
# That matters more than it looks. RateLimitConfig gives the Lettuce client a 250 ms
# command timeout so rate limiting can FAIL OPEN during a Redis outage — but the same
# timeout gates connection *initialization*, so an IPv6-preferring host does not get a
# degraded limiter, it gets an application that cannot boot:
#
#   RedisConnectionException: Unable to connect to localhost/<unresolved>:6379
#   Caused by: RedisCommandTimeoutException: Connection initialization timed out after 250 millisecond(s)
#
# The integration suite never sees this because Testcontainers hands out an IP, not a
# hostname. Pinning IPv4 here is a no-op on the Linux CI runner and the difference
# between working and not on a developer machine.
if [ "$E2E_MODE" = image ]; then
  # The image resolves its peers by SERVICE NAME on the compose network, so none of the
  # 127.0.0.1 pinning below applies to it — the IPv6 trap is a property of the host's
  # resolver, and the app is no longer on the host. TINY_LEDGER_PG_PORT likewise moves only
  # the host-side publication; the app dials postgres:5432 regardless. That is a real
  # reduction in moving parts, and the same one that makes Traefik able to route to
  # `app:8080` without a `host.docker.internal` seam.
  #
  # Every connection setting lives in docker-compose.yml. The ONE thing overridden here is
  # the IP backstop, for the reason above, and it is an environment variable rather than a
  # file edit for the same reason it was a launch argument before: production defaults stay
  # untouched and this cannot leak into a real deployment.
  #
  # No `--wait`: the app service carries no healthcheck (the run image has no shell to run
  # one), so `--wait` would return the moment the container starts. wait-for.sh below is the
  # real readiness gate, and it polls for a STATUS rather than a port for the reason that
  # script explains.
  LEDGER_RATE_LIMIT_IP_BACKSTOP_CAPACITY=10000 $COMPOSE --profile app up -d app traefik
else
  # THE TRUSTSTORE IS REQUIRED HERE, and it is the one thing jar mode needs that image mode does
  # not. The application runs on the host and fetches Keycloak's signing keys over HTTPS, because
  # Traefik fronts the identity provider and there is no plaintext path left. A JVM reads neither
  # SSL_CERT_FILE nor a PEM — it consults its own `cacerts`. Measured without it: the jar boots,
  # answers 401 on the readiness probe, and then every one of the seven scenarios fails, with the
  # certificate as the real cause and authentication as the visible symptom.
  #
  # javax.net.ssl.trustStore REPLACES the default store rather than adding to it, so this JVM
  # trusts exactly one CA. Correct here — the only TLS peer it has is this stack — and never a
  # deployment setting.
  #
  # In IMAGE mode none of this applies: that container fetches the key set in-network over plain
  # HTTP (`jwk-set-uri` -> keycloak:8080) while still validating the HTTPS issuer, which is the
  # split docker-compose.yml explains at length.
  # -D BEFORE -jar. After it they are ARGUMENTS TO THE APPLICATION, not JVM options: the
  # process starts, the truststore is never installed, and the failure is indistinguishable
  # from not passing them at all.
  # The proxy, WITHOUT the application container: Keycloak is behind it even in jar mode, so
  # the host JVM and the CLI both reach the issuer through TLS here. `traefik` alone is enough
  # now that it declares no dependency on `app`.
  $COMPOSE --profile app up -d traefik

  java \
    -Djavax.net.ssl.trustStore="$PWD/docker/tls/truststore.p12" \
    -Djavax.net.ssl.trustStorePassword=changeit \
    -Djavax.net.ssl.trustStoreType=PKCS12 \
    -jar "$JAR" \
    --spring.profiles.active=full \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:${PG_PORT}/tiny_ledger" \
    --spring.data.redis.host=127.0.0.1 \
    --spring.kafka.bootstrap-servers=127.0.0.1:9092 \
    --ledger.rate-limit.ip-backstop.capacity=10000 \
    > "$APP_LOG" 2>&1 &
  APP_PID=$!
fi

if [ "$E2E_MODE" = image ]; then
  # Readiness AND the proof that the transport is real TLS, in one step and in Python rather than
  # curl. The reason is written out in scripts/e2e/https-check.py and is not a style preference:
  # curl on the Windows development machine is a Schannel build that rejects a private CA passed
  # with --cacert, so a curl-based check would have passed on the CI runner and failed here. It
  # also runs through the CLI's own environment, so the stack being proven is the stack the
  # scenarios below actually use.
  echo "--- HTTPS readiness and TLS control ---"
  # "$SSL_CERT_FILE", not "$PWD/docker/tls/ca.crt" — and that is a fix, not a preference. `$PWD` is
  # expanded when the second command of the `&&` runs, which is AFTER the `cd`, so the relative
  # form resolved against ledger-cli/ and the script died on FileNotFoundError. The exported
  # variable is already absolute and is the single source of truth for this path.
  (cd ledger-cli && uv run python ../scripts/e2e/https-check.py "$BASE_URL" "http://127.0.0.1:${TINY_LEDGER_HTTP_PORT:-80}" "$READY_TIMEOUT" "$SSL_CERT_FILE")
else
  # 401 is the ready signal — see scripts/e2e/wait-for.sh for why a status and not a port.
  scripts/e2e/wait-for.sh "$BASE_URL/api/v1/accounts" 401 "$READY_TIMEOUT" "tiny-ledger (full)"
fi

# A SUBSHELL, not a bare `cd`. The EXIT trap above cats "$APP_LOG", which is relative to the
# repository root — a bare `cd ledger-cli` here leaks into the trap, so it looks for
# ledger-cli/app.log and prints "(no application log was produced)" while the real 21 KB log sits
# unread at the root. Measured on the first run this suite ever had: six scenarios failed 401 and
# the operator was handed that message instead of the cause. The comment at the top of this file
# says dumping the log is the point of the trap; this is what makes it true.
#
# -m e2e overrides pyproject's addopts, which excludes the marker by default. If this
# ever reports "deselected" instead of running five tests, the override did not take
# and the job is green having tested nothing.
#
# -s because the scenarios print what they actually observed — how many settled, how many version
# conflicts were retried — and pytest shows captured stdout only on FAILURE. Those lines are the
# evidence that a concurrency scenario exercised its race at all, and they are worth most on the
# runs that pass: N19 passed on Windows for a week having never collided, and a suppressed "0
# version conflicts retried" is exactly how that stayed invisible.
(cd ledger-cli && uv run pytest -m e2e -v -s)
