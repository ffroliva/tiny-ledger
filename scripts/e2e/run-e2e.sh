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
APP_IMAGE=${APP_IMAGE:-tiny-ledger:0.1.0-SNAPSHOT}
BASE_URL=${LEDGER_BASE_URL:-http://127.0.0.1:8080}
APP_LOG=${APP_LOG:-app.log}
READY_TIMEOUT=${READY_TIMEOUT:-120}

case "$E2E_MODE" in
  jar)
    if [ ! -f "$JAR" ]; then
      echo "::error::$JAR not found — build it first: ./mvnw -q -DskipTests package" >&2
      exit 1
    fi
    ;;
  image)
    # Compose has no `build:` for this service on purpose (see docker-compose.yml), so a
    # missing image is not something `up` can repair. Say which command produces it rather
    # than letting compose fail with "pull access denied", which reads as a registry problem.
    if ! docker image inspect "$APP_IMAGE" >/dev/null 2>&1; then
      echo "::error::$APP_IMAGE not found — build it first: ./mvnw -q spring-boot:build-image -DskipTests" >&2
      exit 1
    fi
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
COMPOSE="docker compose -f docker/docker-compose.yml"
unhealthy=$($COMPOSE ps -a --format '{{.Service}} {{.Status}}' 2>/dev/null | grep -v '(healthy)' || true)
if [ -n "$unhealthy" ]; then
  echo "::error::the full stack is not healthy — refusing to run e2e against a partial stack" >&2
  echo "$unhealthy" >&2
  echo "A service stuck in 'Created' usually means its host port is already taken." >&2
  echo "Postgres is the usual culprit; set TINY_LEDGER_PG_PORT to a free port and retry." >&2
  exit 1
fi

APP_PID=""
cleanup() {
  rc=$?
  if [ "$E2E_MODE" = image ]; then
    echo "--- application log (compose service 'app') ---"
    $COMPOSE --profile app logs --no-color app 2>/dev/null || echo "(no application log was produced)"
    # `rm -sf`, not `stop`: the guard above rejects any container that is not (healthy), and a
    # stopped app container would trip it on the NEXT run. The app is the only service this
    # script started, so it is the only one it removes — the four backing services stay up for
    # whoever brought them up.
    $COMPOSE --profile app rm -sf app >/dev/null 2>&1 || true
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
  LEDGER_RATE_LIMIT_IP_BACKSTOP_CAPACITY=10000 $COMPOSE --profile app up -d app
else
  java -jar "$JAR" \
    --spring.profiles.active=full \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:${PG_PORT}/tiny_ledger" \
    --spring.data.redis.host=127.0.0.1 \
    --spring.kafka.bootstrap-servers=127.0.0.1:9092 \
    --ledger.rate-limit.ip-backstop.capacity=10000 \
    > "$APP_LOG" 2>&1 &
  APP_PID=$!
fi

# 401 is the ready signal — see scripts/e2e/wait-for.sh for why a status and not a port.
scripts/e2e/wait-for.sh "$BASE_URL/api/v1/accounts" 401 "$READY_TIMEOUT" "tiny-ledger (full)"

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
