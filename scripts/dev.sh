#!/usr/bin/env bash
# Drive the tiny-ledger stack without rediscovering its traps.
#
#   ./scripts/dev.sh <command> [--user NAME] [--repo PATH] [--rebuild]
#
# Linux and macOS first, like every other script here. On Windows run it from Git Bash or WSL —
# in WSL, Docker Desktop's WSL integration and a Linux-side `uv` are yours to provide, and the
# `.venv` under ledger-cli/ belongs to whichever OS built it (doctor checks exactly that).
#
# Every trap encoded here was hit for real on 8-9 Aug 2026, not imagined:
#   * LEDGER_PROFILE=full is required or the CLI sends no token and everything 401s.
#   * `down` without --profile app leaves the app running AND exits 0.
#   * Keycloak restarts with new signing keys, so a cached token 401s after a reset.
#   * Another Postgres on 5432 parks tiny-ledger-postgres-1 in `created`.
#   * Docker's published ports are invisible to the host's listener table on some machines.
set -euo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
COMPOSE=docker/docker-compose.yml
USER_NAME=alice
REBUILD=0
COMMAND=status

if [ -t 1 ]; then C='\033[36m'; Y='\033[33m'; G='\033[32m'; R='\033[31m'; D='\033[90m'; N='\033[0m'
else C=''; Y=''; G=''; R=''; D=''; N=''; fi
say()  { printf "${C}%s${N}\n" "$*"; }
warn() { printf "${Y}%s${N}\n" "$*"; }
ok()   { printf "${G}%s${N}\n" "$*"; }
die()  { printf "${R}%s${N}\n" "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    help|status|doctor|standalone|full|demo|run-e2e|clean-up|selfcheck) COMMAND=$1; shift ;;
    --user) USER_NAME=${2:?--user needs a name}; shift 2 ;;
    --repo) REPO=${2:?--repo needs a path}; shift 2 ;;
    --rebuild) REBUILD=1; shift ;;
    *) die "Unknown argument: $1. Try: $0 help" ;;
  esac
done

# Windows curl is a Schannel build and cannot check revocation on a throwaway CA. The flag is
# Schannel-only, so it is added ONLY there — passing it to an OpenSSL curl is an error, not a no-op.
CURL_EXTRA=()
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) CURL_EXTRA=(--ssl-no-revoke) ;; esac

# platformdirs, which is what ledger-cli uses, resolves its cache per OS. Three answers, not one.
token_cache_dir() {
  case "$(uname -s)" in
    Darwin) printf '%s\n' "$HOME/Library/Caches/ledger-cli" ;;
    MINGW*|MSYS*|CYGWIN*) printf '%s\n' "${LOCALAPPDATA:-$HOME/AppData/Local}/ledger-cli/ledger-cli/Cache" ;;
    *) printf '%s\n' "${XDG_CACHE_HOME:-$HOME/.cache}/ledger-cli" ;;
  esac
}

# Docker Desktop's published ports are NOT always visible in the host listener table — measured
# 9 Aug, when 5432 reported "free" moments before Compose failed to bind it. So ask the OS AND
# ask Docker, and treat either as taken. lsof/ss/netstat: whichever this machine actually has.
port_owner() {
  local port=$1 pid name
  if command -v lsof >/dev/null 2>&1; then
    pid=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)
    [ -n "$pid" ] && { ps -p "$pid" -o comm= 2>/dev/null | head -1; return; }
  elif command -v ss >/dev/null 2>&1; then
    name=$(ss -ltnp 2>/dev/null | grep -E "[:.]${port}[[:space:]]" | grep -oE 'users:\(\("[^"]+' | head -1 | sed 's/.*"//' || true)
    [ -n "$name" ] && { printf '%s\n' "$name"; return; }
  elif command -v netstat >/dev/null 2>&1; then
    grep -qE "[:.]${port}[[:space:]]+.*LISTEN" <(netstat -an 2>/dev/null) && { printf 'a host process\n'; return; }
  fi
  name=$(docker ps --format '{{.Names}}\t{{.Ports}}' 2>/dev/null | grep ":${port}->" | head -1 | cut -f1 || true)
  [ -n "$name" ] && printf 'container %s\n' "$name"
  # An unowned port is an ANSWER, not a failure: without this the last test's exit status leaks out
  # and every caller has to write `|| true`. selfcheck caught exactly that.
  return 0
}

set_ledger_env() {
  export LEDGER_PROFILE=$1 LEDGER_USERNAME=$USER_NAME
  if [ "$1" = full ]; then
    # The one that bites: without LEDGER_PROFILE=full the CLI attaches no token at all.
    export LEDGER_BASE_URL=https://app.localhost
    export LEDGER_ISSUER_URI=https://auth.localhost/realms/tiny-ledger
    export LEDGER_CLIENT_ID=ledger-test
    export LEDGER_PASSWORD=dev-only
    export SSL_CERT_FILE="$REPO/docker/tls/ca.crt"
  else
    export LEDGER_BASE_URL=http://127.0.0.1:8080
    unset SSL_CERT_FILE
  fi
}

# Stop at the FIRST failed step. Every later step of the tour needs the account step 1 opens, so a
# 409 there printed three cascading `account-not-found` 404s — four failures where there was one.
#
# Captured, not piped. `uv ... | grep -v ... || true` looks equivalent and is not: the `|| true`
# runs a fresh command, which resets PIPESTATUS, so the exit code read back was always 0 and the
# guard did nothing. Caught by running it — the tour sailed past a 409 into two 404s, the exact
# failure this function exists to prevent.
run_cli() {
  local out code=0
  out=$(cd "$REPO/ledger-cli" && uv run ledger-cli "$@" 2>&1) || code=$?
  printf '%s\n' "$out" | grep -vE 'keycloak\.(refresh|token)' || true
  [ "$code" -eq 0 ] || die "Step failed (exit $code). Stopping: the remaining steps all depend on it."
}

# uuidgen is util-linux and absent on plenty of machines; /proc is Linux-only. openssl is the one
# that is present everywhere this repository already needs it (the dev CA), so it is the fallback.
new_uuid() {
  uuidgen 2>/dev/null && return 0
  openssl rand -hex 16 | sed -E 's/(.{8})(.{4})(.{4})(.{4})(.{12})/\1-\2-\3-\4-\5/'
}

full_reachable() {
  [ -f "$REPO/docker/tls/ca.crt" ] || return 1
  local code
  code=$(curl -s "${CURL_EXTRA[@]}" --cacert "$REPO/docker/tls/ca.crt" \
           -o /dev/null -w '%{http_code}' https://app.localhost/api/v1/accounts 2>/dev/null || true)
  [ -n "$code" ] && [ "$code" != 000 ] && printf '%s\n' "$code"
}

[ "$COMMAND" = selfcheck ] || [ -d "$REPO" ] || die "No repo at $REPO — pass --repo."

case "$COMMAND" in

help)
  printf "${D}%s${N}\n" "
tiny-ledger — operate the stack without rediscovering its traps

  ./scripts/dev.sh <command> [--user NAME] [--repo PATH] [--rebuild]

COMMANDS
  help          this
  status        what IS running — containers, ports, what answers
  doctor        whether the machine CAN run it — prerequisites and known-bad state
  standalone    in-memory on 8080, no Docker. Streams the log; Ctrl+C stops
  full          Compose stack, HTTPS via Traefik. --rebuild forces the image build
  demo          five-command tour of whichever mode is up
  run-e2e       the seven e2e scenarios (needs full). Removes app+traefik on exit
  clean-up      down -v, delete the event store, clear the cached token
  selfcheck     the script's own assertions. Starts nothing

TYPICAL FLOWS
  first thing            doctor  ->  fix anything red  ->  status
  quick API demo         standalone   (second terminal)  demo
  the real thing         full  ->  demo  ->  run-e2e  ->  full   (e2e removes the app)
  between rounds         clean-up  ->  full

USERS (--user)  alice/bob/mallory writer+reader · carol reader · dave auditor
                trent writer+reader+admin · nobody no roles

THINGS THAT LOOK BROKEN AND ARE NOT
  401 on every call        LEDGER_PROFILE not 'full'. This script sets it
  401 after a reset        Keycloak reissued its keys; clear the cached token
  409 on 'open'            the per-owner account cap. 'clean-up' resets it
  browser 'not private'    private CA, deliberately untrusted. NEVER use -k
  demo fails after e2e     run-e2e removed app+traefik. Run 'full'
  port free but won't bind Docker's ports can be invisible to the host listener table
"
  ;;

doctor)
  problems=0
  diag() { # label state detail
    case "$2" in
      ok)   printf "  ${G}[ ok ]${N} %-24s %s\n" "$1" "$3" ;;
      warn) printf "  ${Y}[warn]${N} %-24s %s\n" "$1" "$3" ;;
      bad)  printf "  ${R}[FAIL]${N} %-24s %s\n" "$1" "$3"; problems=$((problems + 1)) ;;
    esac
  }
  say ""; say "tiny-ledger doctor — can this machine run it?"; echo

  if command -v docker >/dev/null 2>&1; then
    v=$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)
    [ -n "$v" ] && diag docker ok "daemon $v" || diag docker bad 'CLI present, daemon not responding — start Docker'
  else diag docker bad 'not on PATH'; fi

  if command -v java >/dev/null 2>&1; then
    jv=$(java -version 2>&1 | head -1 | grep -oE '"?[0-9]+' | head -1 | tr -d '"')
    [ "${jv:-0}" -ge 25 ] 2>/dev/null && diag JDK ok "$jv" || diag JDK bad "${jv:-?} — the build needs 25"
  else diag JDK bad 'java not on PATH'; fi

  command -v uv >/dev/null 2>&1 && diag uv ok "$(command -v uv)" || diag uv bad 'not on PATH — the CLI and e2e need it'

  # A .venv built under a different OS carries that OS's `home =` and a lib64 symlink the other
  # cannot delete, so `uv sync` dies with a permission error and nothing explains why. Measured 9 Aug.
  cfg="$REPO/ledger-cli/.venv/pyvenv.cfg"
  if [ -f "$cfg" ]; then
    vhome=$(grep -E '^home = ' "$cfg" | head -1 | sed 's/^home = //')
    case "$(uname -s):$vhome" in
      MINGW*:[A-Za-z]:*|MSYS*:[A-Za-z]:*|CYGWIN*:[A-Za-z]:*) diag ledger-cli/.venv ok 'built on Windows' ;;
      MINGW*:*|MSYS*:*|CYGWIN*:*) diag ledger-cli/.venv bad "built on Linux ($vhome) — rm -rf ledger-cli/.venv" ;;
      *:[A-Za-z]:*) diag ledger-cli/.venv bad "built on Windows ($vhome) — rm -rf ledger-cli/.venv" ;;
      *) diag ledger-cli/.venv ok "built here ($vhome)" ;;
    esac
  else diag ledger-cli/.venv warn 'absent — uv sync will create it on first use'; fi

  o8080=$(port_owner 8080 || true)
  case "$o8080" in "") diag 'port 8080' ok 'free for standalone' ;;
    *tiny-ledger*) diag 'port 8080' ok "$o8080" ;;
    *) diag 'port 8080' warn "held by $o8080 — standalone will refuse to start" ;; esac

  o5432=$(port_owner 5432 || true)
  case "$o5432" in "") diag 'port 5432' ok 'free' ;;
    *tiny-ledger*) diag 'port 5432' ok "$o5432" ;;
    *) diag 'port 5432' bad "held by $o5432 — postgres parks in 'created' and full breaks" ;; esac

  [ -n "$(docker images -q tiny-ledger:local 2>/dev/null || true)" ] \
    && diag 'app image' ok 'tiny-ledger:local present' \
    || diag 'app image' warn "absent — 'full' will build it (~90s+)"

  ca="$REPO/docker/tls/ca.crt"
  if [ -f "$ca" ]; then
    if command -v openssl >/dev/null 2>&1; then
      if openssl x509 -in "$ca" -noout -checkend 0 >/dev/null 2>&1; then
        diag 'dev CA' ok "valid to $(openssl x509 -in "$ca" -noout -enddate | cut -d= -f2)"
      else diag 'dev CA' bad 'EXPIRED — regenerate: scripts/tls/gen-dev-ca.sh --force'; fi
    else diag 'dev CA' warn 'present; no openssl to check its expiry'; fi
  else diag 'dev CA' warn 'absent — run-e2e generates it'; fi

  cache=$(token_cache_dir)
  n=$(ls "$cache"/token-*.json 2>/dev/null | wc -l | tr -d ' ')
  [ "$n" -gt 0 ] 2>/dev/null \
    && diag 'cached tokens' warn "$n cached — stale after any reset, causing 401s. 'clean-up' clears them" \
    || diag 'cached tokens' ok none

  branch=$(git -C "$REPO" branch --show-current 2>/dev/null || echo '?')
  dirty=$(git -C "$REPO" status --porcelain 2>/dev/null | grep -vc '\.idea' || true)
  [ "${dirty:-0}" -gt 0 ] \
    && diag repo warn "$branch, $dirty uncommitted file(s) — check before a screen share" \
    || diag repo ok "$branch, clean"

  others=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -v '^tiny-ledger-' || true)
  [ -n "$others" ] \
    && diag 'other containers' warn "$(echo "$others" | wc -l | tr -d ' ') running — may contend for ports: $(echo "$others" | tr '\n' ' ')" \
    || diag 'other containers' ok none

  echo
  [ "$problems" -eq 0 ] || die "$problems blocking problem(s). Fix those before demoing."
  ok "No blocking problems."; echo
  ;;

status)
  say ""; say "=== containers ==="
  if [ -z "$(docker ps -q 2>/dev/null || true)" ]; then echo "  none running"
  else
    docker ps --format '  {{.Names}}  {{.Status}}'
    others=$(docker ps --format '{{.Names}}' | grep -v '^tiny-ledger-' || true)
    [ -n "$others" ] && warn "  $(echo "$others" | wc -l | tr -d ' ') container(s) not part of tiny-ledger — 'clean-up' leaves them alone; stop them yourself before a demo"
  fi

  say ""; say "=== ports ==="
  for p in 8080 443 80 5432 6379 9092; do
    owner=$(port_owner "$p" || true)
    printf '  %-5s %s\n' "$p" "${owner:-free}"
  done

  say ""; say "=== reachable? ==="
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:8080/api/v1/accounts 2>/dev/null || true)
  { [ -n "$code" ] && [ "$code" != 000 ]; } && ok "  standalone is UP on 8080 (HTTP $code)" || echo "  nothing on 8080"
  code=$(full_reachable || true)
  if [ "$code" = 401 ]; then ok "  full is UP on https://app.localhost (401 = auth enforced, correct)"
  elif [ -n "$code" ]; then ok "  full answering, HTTP $code"
  else echo "  full not reachable"; fi
  echo
  ;;

standalone)
  owner=$(port_owner 8080 || true)
  [ -z "$owner" ] || die "Port 8080 is held by '$owner'. Stop it first — this is what makes spring-boot:run fail."
  say "Starting standalone (in-memory, no Docker). Ctrl+C to stop."; echo
  cd "$REPO" && ./mvnw spring-boot:run
  ;;

full)
  owner=$(port_owner 5432 || true)
  case "$owner" in ""|*tiny-ledger*) ;;
    *) die "Port 5432 is held by '$owner'. tiny-ledger-postgres-1 would sit in 'created' and the app could talk to the WRONG database. Stop it, or set TINY_LEDGER_PG_PORT." ;;
  esac
  cd "$REPO"
  if [ "$REBUILD" = 1 ] || [ -z "$(docker images -q tiny-ledger:local 2>/dev/null || true)" ]; then
    say "Building the image (~90s+)…"
    ./mvnw -q spring-boot:build-image -DskipTests || die "Image build failed."
  else
    echo "Image tiny-ledger:local present — pass --rebuild to rebuild."
  fi
  say "Bringing the stack up…"
  docker compose -f "$COMPOSE" --profile app up -d --wait || die "Compose failed. Run 'status' to see which port is contested."
  echo
  ok "Up:  https://app.localhost   (401 without a token — that is correct)"
  ok "     https://auth.localhost  (Keycloak)"
  warn "The browser will show ERR_CERT_AUTHORITY_INVALID: the dev CA is deliberately not in any"
  warn "trust store. The e2e asserts exactly that — 'public trust store -> rejected, as it must be'."
  echo; echo "Next:  ./scripts/dev.sh demo     or     ./scripts/dev.sh run-e2e"; echo
  ;;

demo)
  mode=standalone
  [ -n "$(full_reachable || true)" ] && mode=full
  say "Driving the '$mode' profile as '$USER_NAME'."; echo
  set_ledger_env "$mode"

  acct="DEMO-$(date +%H%M%S)"
  say "1. open $acct";        run_cli account open --name "$acct" --currency GBP
  echo; say "2. deposit 100.00";  run_cli deposit  --account "$acct" --amount 100.00
  echo; say "3. withdraw 30.00";  run_cli withdraw --account "$acct" --amount 30.00

  mv=$(new_uuid)
  echo; say "4. same movementUid twice — the second must REPLAY, not re-apply"
  run_cli deposit --account "$acct" --amount 25.00 --movement-uid "$mv"
  run_cli deposit --account "$acct" --amount 25.00 --movement-uid "$mv"

  echo; say "5. balance and history"
  run_cli balance --account "$acct"
  run_cli history --account "$acct" --all
  echo
  ;;

run-e2e)
  say "Running the e2e suite. Watch for 'passed', not just green."; echo
  log=${TMPDIR:-/tmp}/tiny-ledger-e2e-$(date +%Y%m%d-%H%M%S).log
  code=0
  ( cd "$REPO" && LEDGER_PROFILE=full \
      LEDGER_ISSUER_URI=https://auth.localhost/realms/tiny-ledger \
      LEDGER_CLIENT_ID=ledger-test LEDGER_USERNAME="$USER_NAME" LEDGER_PASSWORD=dev-only \
      ./scripts/e2e/run-e2e.sh ) 2>&1 | tee "$log" || code=${PIPESTATUS[0]}

  # run-e2e.sh always dumps the application log on exit, which buries the pytest summary hundreds
  # of lines up. Telling the reader to check the count and then hiding it is the very defect this
  # script exists to stop, so re-print it last.
  summary=$(grep -oE '[0-9]+ (passed|failed|deselected|error)[^|]*' "$log" | tail -1 || true)
  echo; echo "--------------------------------------------------------------"
  if [ -z "$summary" ]; then warn "  no pytest summary found in the output — treat this run as unproven"
  # `7 deselected` with nothing selected is GREEN having tested nothing: pyproject.toml excludes
  # the e2e marker by default. The count is the evidence; the exit code is not.
  elif printf '%s' "$summary" | grep -q 'passed'; then ok "  $summary"
  else warn "  $summary  <- no tests SELECTED"; fi
  echo "  full log: $log"
  echo "--------------------------------------------------------------"

  # run-e2e.sh brings `app` and `traefik` up ITSELF and REMOVES BOTH on exit — the four backing
  # services survive, the app and the proxy do not. So https://app.localhost is dead afterwards and
  # a `demo` straight after fails against a stack that still looks up in `docker ps`.
  echo; warn "NOTE: the e2e run removed tiny-ledger-app-1 and tiny-ledger-traefik-1."
  warn "https://app.localhost is DOWN until you run:  ./scripts/dev.sh full"
  [ "$code" -eq 0 ] || die "e2e failed (exit $code). If this was a repeat run, bring the stack back with 'full' and leave a few seconds before retrying."
  ;;

clean-up)
  # --profile app is MANDATORY: without it Compose leaves the app container running, fails to
  # remove the network, and STILL EXITS 0 (docs/docker.md §8, verified on Compose v2.38.1).
  say "Tearing down and deleting the event store…"
  ( cd "$REPO" && docker compose -f "$COMPOSE" --profile app down -v )

  # Keycloak comes back with new signing keys, so a cached token 401s and the stack looks broken.
  cache=$(token_cache_dir)
  n=$(ls "$cache"/token-*.json 2>/dev/null | wc -l | tr -d ' ')
  if [ "${n:-0}" -gt 0 ] 2>/dev/null; then rm -f "$cache"/token-*.json; ok "Cleared $n cached token(s)."
  else echo "No cached tokens."; fi

  owner=$(port_owner 8080 || true)
  [ -z "$owner" ] || warn "Something still holds 8080 ($owner) — a leftover standalone run. Stop it before 'standalone'."
  ok "Clean. Next: ./scripts/dev.sh full   (or standalone)"
  ;;

selfcheck)
  # Assertions only — nothing started, nothing torn down, safe to run any time.
  fail=0
  check() { if eval "$2"; then ok "  PASS $1"; else printf "  ${R}FAIL %s${N}\n" "$1"; fail=$((fail + 1)); fi; }
  say selfcheck
  check "repo path exists"           "[ -d '$REPO' ]"
  check "compose file present"       "[ -f '$REPO/$COMPOSE' ]"
  check "e2e script present"         "[ -f '$REPO/scripts/e2e/run-e2e.sh' ]"
  check "ledger-cli present"         "[ -f '$REPO/ledger-cli/pyproject.toml' ]"
  check "repo derived from script"   "[ -f '$REPO/scripts/dev.sh' ]"
  check "uv on PATH"                 "command -v uv >/dev/null"
  check "docker on PATH"             "command -v docker >/dev/null"
  check "token cache path resolves"  "[ -n \"\$(token_cache_dir)\" ]"
  check "full env sets the profile"  "[ \"\$(set_ledger_env full; echo \$LEDGER_PROFILE)\" = full ]"
  check "standalone clears the CA"   "[ -z \"\$(set_ledger_env standalone; echo \${SSL_CERT_FILE:-})\" ]"
  check "port probe never errors"    "port_owner 65535 >/dev/null"
  check "uuid generator works"       "new_uuid | grep -qE '^[0-9a-fA-F-]{36}$'"
  echo
  [ "$fail" -eq 0 ] || die "$fail check(s) failed."
  ok "All checks passed."
  ;;
esac
