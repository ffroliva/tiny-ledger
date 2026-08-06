#!/usr/bin/env bash
# Poll a URL until it answers with an expected HTTP status, or give up.
#
# Usage: wait-for.sh <url> <expected-status> <timeout-seconds> <label>
#
# Why a status rather than "is the port open": a listening socket proves a process
# bound it, not that the application is serving. For the `full` profile an
# unauthenticated request to a protected route must answer 401 — which proves the
# app is up AND that the security chain is wired. A 200 there would itself be a
# defect, so this probe fails loudly on the one outcome that looks most like success.
#
# Why not a fixed sleep: too short and it flakes under CI load, too long and every
# run pays for it.
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <url> <expected-status> <timeout-seconds> <label>" >&2
  exit 2
fi

url=$1
expected=$2
timeout=$3
label=$4

deadline=$(( SECONDS + timeout ))
actual=none

while [ "$SECONDS" -lt "$deadline" ]; do
  # curl already prints 000 on a connection failure and *also* exits non-zero, so a
  # `|| echo 000` fallback would concatenate a second one and report "000000".
  # `|| true` keeps set -e happy without corrupting the status this loop reports.
  actual=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" || true)
  actual=${actual:-000}
  if [ "$actual" = "$expected" ]; then
    echo "$label ready after ${SECONDS}s (HTTP $actual)"
    exit 0
  fi
  sleep 2
done

echo "::error::$label never returned HTTP $expected within ${timeout}s (last: $actual)" >&2
exit 1
