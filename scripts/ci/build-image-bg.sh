#!/usr/bin/env bash
# Run the buildpack image build in the background so it overlaps a job's other slow setup
# (compose stack, kind cluster, the Compose-image Trivy scan) instead of running before it.
# Measured on run 41498130960: each of security/zap/e2e/k8s-kind spent 1m30-1m55 on this build,
# serially, in front of 1m-1m30 of setup that never needed the image.
#
#   scripts/ci/build-image-bg.sh start   # returns at once
#   scripts/ci/build-image-bg.sh wait    # blocks, prints the build log, exits with the build's code
#
# A background process outlives its step on GitHub-hosted runners (orphans are reaped at job end),
# which is what makes the split across steps work. `wait` without `start` fails rather than hanging.
set -euo pipefail

dir="${RUNNER_TEMP:-/tmp}"
log="$dir/build-image.log"
rc="$dir/build-image.rc"
started="$dir/build-image.started"

case "${1:-}" in
  start)
    rm -f "$rc"
    touch "$started"
    nohup bash -c "./mvnw -q spring-boot:build-image -DskipTests >'$log' 2>&1; echo \$? >'$rc'" \
      >/dev/null 2>&1 &
    echo "image build started in the background (pid $!)"
    ;;
  wait)
    [ -f "$started" ] || { echo "::error::build-image-bg.sh wait called without start"; exit 2; }
    while [ ! -f "$rc" ]; do sleep 2; done
    cat "$log"
    code="$(cat "$rc")"
    [ "$code" -eq 0 ] || echo "::error::background image build failed (exit $code)"
    exit "$code"
    ;;
  *)
    echo "usage: $0 start|wait" >&2
    exit 2
    ;;
esac
