#!/usr/bin/env bash
# E7 — restart replays incomplete publications.
#
# The one case the integration suite cannot reach. §9.3 E7 asks for the application to be KILLED
# mid-publication and restarted, so that Spring Modulith's `republish-outstanding-events-on-restart`
# completes the delivery. `KafkaAuditModuleIT` runs inside a shared Spring context (ADR 0003) that no
# test may kill, so E7 stayed open while E12 covered only its precondition — that the in-flight work is
# durably on disk.
#
# Here the application is a real OS process, so it can actually be killed. The sequence is:
#
#   1. pause Kafka                     — delivery becomes impossible
#   2. write a movement                — the append commits; the publication row cannot complete
#   3. assert the row is on disk       — this is E12's claim, re-checked as this test's precondition
#   4. kill -9 the application         — no shutdown hook, no graceful drain, no second chance
#   5. unpause Kafka, restart the app  — the ONLY actor left that can finish the delivery
#   6. assert the row drains and the entry reaches the audit trail, with no manual intervention
#
# Deliberately NOT wired into CI stage 9 yet. It kills a process and restarts it, which is a different
# shape of job from `run-e2e.sh`, and adding a stage is a decision worth taking deliberately rather
# than as a side effect. Run it by hand:
#
#   docker compose -f docker/docker-compose.yml up -d --wait
#   ./mvnw -q -DskipTests package
#   ./scripts/e2e/restart-replay.sh
set -euo pipefail

JAR=${JAR:-target/tiny-ledger-0.1.0-SNAPSHOT-exec.jar}
BASE_URL=${LEDGER_BASE_URL:-http://127.0.0.1:8080}
ISSUER=${LEDGER_ISSUER_URI:-http://localhost:8081/realms/tiny-ledger}
PG_PORT=${TINY_LEDGER_PG_PORT:-5432}
READY_TIMEOUT=${READY_TIMEOUT:-120}
LOG_BEFORE=${LOG_BEFORE:-app-before-kill.log}
LOG_AFTER=${LOG_AFTER:-app-after-restart.log}

[ -f "$JAR" ] && : || { echo "::error::$JAR not found — ./mvnw -q -DskipTests package" >&2; exit 1; }

APP_PID=""
cleanup() {
  rc=$?
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
  docker compose -f docker/docker-compose.yml unpause kafka >/dev/null 2>&1 || true
  if [ $rc -ne 0 ]; then
    echo "--- $LOG_AFTER (tail) ---"; tail -40 "$LOG_AFTER" 2>/dev/null || echo "(none)"
  fi
  exit $rc
}
trap cleanup EXIT

start_app() {
  java -jar "$JAR" \
    --spring.profiles.active=full \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:${PG_PORT}/tiny_ledger" \
    --spring.data.redis.host=127.0.0.1 \
    --spring.kafka.bootstrap-servers=127.0.0.1:9092 \
    --ledger.rate-limit.ip-backstop.capacity=10000 \
    > "$1" 2>&1 &
  APP_PID=$!
  scripts/e2e/wait-for.sh "$BASE_URL/api/v1/accounts" 401 "$READY_TIMEOUT" "tiny-ledger (full)"
}

psql_count() {
  docker compose -f docker/docker-compose.yml exec -T postgres \
    psql -U ledger -d tiny_ledger -tAc "$1" | tr -d '[:space:]'
}

echo "== 1. start the application =="
start_app "$LOG_BEFORE"

TOKEN=$(curl -sS -X POST "$ISSUER/protocol/openid-connect/token" \
  -d client_id=ledger-test -d username=alice -d password=dev-only -d grant_type=password \
  | jq -r .access_token)
[ "$TOKEN" != "null" ] || { echo "::error::could not mint a token" >&2; exit 1; }

ACCOUNT=$(curl -sS -X POST "$BASE_URL/api/v1/accounts" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"ACC-E7","currency":"GBP"}' | jq -r .accountUid)
echo "   opened $ACCOUNT"

# Let the AccountOpened publication drain, so the row counted below is unambiguously the one this
# script created while Kafka was down.
for _ in $(seq 1 60); do [ "$(psql_count 'select count(*) from event_publication')" = "0" ] && break; sleep 0.5; done

echo "== 2. pause Kafka, then write a movement =="
docker compose -f docker/docker-compose.yml pause kafka >/dev/null
MOVEMENT=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c "import uuid;print(uuid.uuid4())")
curl -sS -o /dev/null -w '   deposit -> HTTP %{http_code}\n' \
  -X PUT "$BASE_URL/api/v1/accounts/$ACCOUNT/deposits/$MOVEMENT" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount":{"currency":"GBP","minorUnits":4200}}'

echo "== 3. the publication must be on disk before the kill =="
OUTSTANDING=$(psql_count 'select count(*) from event_publication')
echo "   event_publication rows: $OUTSTANDING"
[ "$OUTSTANDING" -ge 1 ] || { echo "::error::nothing outstanding — a kill here would prove nothing" >&2; exit 1; }

echo "== 4. kill -9 the application =="
kill -9 "$APP_PID"; wait "$APP_PID" 2>/dev/null || true; APP_PID=""
# Still outstanding with the process gone: the row is the only record of the undelivered event.
STILL=$(psql_count 'select count(*) from event_publication')
echo "   event_publication rows with the process dead: $STILL"
[ "$STILL" -ge 1 ] || { echo "::error::the row vanished with the process — nothing to replay" >&2; exit 1; }

echo "== 5. unpause Kafka and restart =="
docker compose -f docker/docker-compose.yml unpause kafka >/dev/null
start_app "$LOG_AFTER"

echo "== 6. the restart must complete the delivery, unaided =="
for _ in $(seq 1 120); do
  DRAINED=$(psql_count 'select count(*) from event_publication')
  ENTRIES=$(psql_count "select count(*) from audit_entries where account_id = '$ACCOUNT' and event_type = 'MoneyDeposited'")
  [ "$DRAINED" = "0" ] && [ "$ENTRIES" -ge 1 ] && break
  sleep 1
done

echo "   event_publication rows: $DRAINED (want 0)"
echo "   MoneyDeposited audit entries: $ENTRIES (want >= 1)"
[ "$DRAINED" = "0" ] || { echo "::error::E7 FAILED — the publication never completed after restart" >&2; exit 1; }
[ "$ENTRIES" -ge 1 ] || { echo "::error::E7 FAILED — the movement never reached the audit trail" >&2; exit 1; }
echo "E7 PASSED — a publication orphaned by kill -9 was replayed on restart with no intervention"
