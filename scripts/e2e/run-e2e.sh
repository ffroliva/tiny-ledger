#!/usr/bin/env bash
# Start the built jar under the `full` profile against a running Compose stack, run
# the e2e marker, and tear down. Always dumps the application log: a failed e2e run
# whose cause sits in a log nobody printed costs more than the run itself.
#
# Expects `docker compose -f docker/docker-compose.yml up -d --wait` to have brought
# up Postgres, Redis, Kafka and Keycloak already. Run from the repository root.
set -euo pipefail

JAR=${JAR:-target/tiny-ledger-0.1.0-SNAPSHOT.jar}
BASE_URL=${LEDGER_BASE_URL:-http://127.0.0.1:8080}
APP_LOG=${APP_LOG:-app.log}
READY_TIMEOUT=${READY_TIMEOUT:-120}

if [ ! -f "$JAR" ]; then
  echo "::error::$JAR not found — build it first: ./mvnw -q -DskipTests package" >&2
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
java -jar "$JAR" \
  --spring.profiles.active=full \
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
