#!/usr/bin/env bash
# Start the built jar under the `full` profile against a running Compose stack, run
# the e2e marker, and tear down. Always dumps the application log: a failed e2e run
# whose cause sits in a log nobody printed costs more than the run itself.
#
# Expects `docker compose -f docker/docker-compose.yml up -d --wait` to have brought
# up Postgres, Redis, Kafka and Keycloak already. Run from the repository root.
set -euo pipefail

# -exec, because the root pom gives spring-boot-maven-plugin a classifier so the plain
# jar stays usable as a dependency (benchmarks/ compiles against it). The runnable,
# repackaged jar is the one with the classifier.
JAR=${JAR:-target/tiny-ledger-0.1.0-SNAPSHOT-exec.jar}
BASE_URL=${LEDGER_BASE_URL:-http://127.0.0.1:8080}
APP_LOG=${APP_LOG:-app.log}
READY_TIMEOUT=${READY_TIMEOUT:-120}

if [ ! -f "$JAR" ]; then
  echo "::error::$JAR not found — build it first: ./mvnw -q -DskipTests package" >&2
  exit 1
fi

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
  echo "--- application log ($APP_LOG) ---"
  cat "$APP_LOG" 2>/dev/null || echo "(no application log was produced)"
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
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
java -jar "$JAR" \
  --spring.profiles.active=full \
  --spring.datasource.url="jdbc:postgresql://127.0.0.1:${PG_PORT}/tiny_ledger" \
  --spring.data.redis.host=127.0.0.1 \
  --spring.kafka.bootstrap-servers=127.0.0.1:9092 \
  --ledger.rate-limit.ip-backstop.capacity=10000 \
  > "$APP_LOG" 2>&1 &
APP_PID=$!

# 401 is the ready signal — see scripts/e2e/wait-for.sh for why a status and not a port.
scripts/e2e/wait-for.sh "$BASE_URL/api/v1/accounts" 401 "$READY_TIMEOUT" "tiny-ledger (full)"

cd ledger-cli
# -m e2e overrides pyproject's addopts, which excludes the marker by default. If this
# ever reports "deselected" instead of running five tests, the override did not take
# and the job is green having tested nothing.
uv run pytest -m e2e -v
