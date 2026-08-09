<#
Drive the tiny-ledger stack without rediscovering its traps.

Usage: .\dev.ps1 <command> [-RepoPath <path>] [-Rebuild] [-User <name>]

  status      What is running, which mode, which ports. Start here.
  standalone  Run in-memory on 8080. No Docker. Streams the app log.
  full        Postgres/Redis/Kafka/Keycloak under Compose, HTTPS via Traefik.
  demo        The five-command tour against whichever mode is up.
  run-e2e     The seven e2e scenarios (needs `full` up). Git Bash, never WSL.
  clean-up    Tear down, delete the event store, clear the cached token.

Every trap encoded here was hit for real on 8-9 Aug 2026, not imagined:
  * LEDGER_PROFILE=full is required or the CLI sends no token and everything 401s.
  * `down` without --profile app leaves the app running AND exits 0.
  * Keycloak restarts with new signing keys, so a cached token 401s after a reset.
  * PowerShell's bare `bash` is WSL, which has no uv; run-e2e needs Git Bash.
  * Another Postgres on 5432 parks tiny-ledger-postgres-1 in `created`.
  * Windows curl is Schannel and rejects the dev CA; the CLI (httpx) is unaffected.
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)]
  [ValidateSet('help', 'status', 'doctor', 'standalone', 'full', 'demo', 'run-e2e', 'clean-up', 'selfcheck')]
  [string]$Command = 'status',

  # Derived from this script's own location (repo/scripts/dev.ps1), never hardcoded: the repository
  # is cloned to different paths on different machines, and eight worktrees exist on this one.
  [string]$RepoPath = (Split-Path $PSScriptRoot -Parent),
  [string]$User = 'alice',
  [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$compose = 'docker/docker-compose.yml'

# Git Bash, discovered rather than assumed — it is not always under Program Files, and `bash` on
# PATH is the wrong answer: from PowerShell that resolves to C:\windows\system32\bash.exe (WSL),
# a separate Linux environment where this repository's Windows-side toolchain does not exist.
function Resolve-GitBash {
  foreach ($p in @(
      "$env:ProgramFiles\Git\bin\bash.exe",
      "${env:ProgramFiles(x86)}\Git\bin\bash.exe",
      "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe")) {
    if ($p -and (Test-Path $p)) { return $p }
  }
  # Fall back to the git on PATH: <git>/cmd/git.exe -> <git>/bin/bash.exe
  $git = (Get-Command git.exe -ErrorAction SilentlyContinue).Source
  if ($git) {
    $candidate = Join-Path (Split-Path (Split-Path $git -Parent) -Parent) 'bin\bash.exe'
    if (Test-Path $candidate) { return $candidate }
  }
  return $null
}
$gitBash = Resolve-GitBash

function Say  ($m) { Write-Host $m -ForegroundColor Cyan }
function Warn ($m) { Write-Host $m -ForegroundColor Yellow }
function Ok   ($m) { Write-Host $m -ForegroundColor Green }
function Die  ($m) { Write-Host $m -ForegroundColor Red; exit 1 }

# Docker Desktop's published ports are NOT visible to Get-NetTCPConnection on this machine —
# measured 9 Aug, when 5432 reported "free" moments before Compose failed to bind it. So ask
# Docker for container mappings and the OS for host processes, and treat either as "taken".
function Test-PortBusy([int]$Port) {
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { return $true }
  $mapped = docker ps --format '{{.Ports}}' 2>$null | Select-String -SimpleMatch ":$Port->"
  return [bool]$mapped
}

function Get-PortOwner([int]$Port) {
  $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($c) { return (Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue).ProcessName }
  $n = docker ps --format '{{.Names}}\t{{.Ports}}' 2>$null | Select-String -SimpleMatch ":$Port->" |
       ForEach-Object { ($_ -split "`t")[0] } | Select-Object -First 1
  if ($n) { return "container $n" }
  return $null
}

function Set-LedgerEnv([string]$Profile) {
  $env:LEDGER_PROFILE = $Profile
  $env:LEDGER_USERNAME = $User
  if ($Profile -eq 'full') {
    # The one that bites: without LEDGER_PROFILE=full the CLI attaches no token at all.
    $env:LEDGER_BASE_URL   = 'https://app.localhost'
    $env:LEDGER_ISSUER_URI = 'https://auth.localhost/realms/tiny-ledger'
    $env:LEDGER_CLIENT_ID  = 'ledger-test'
    $env:LEDGER_PASSWORD   = 'dev-only'
    $env:SSL_CERT_FILE     = Join-Path $RepoPath 'docker\tls\ca.crt'
  } else {
    $env:LEDGER_BASE_URL = 'http://127.0.0.1:8080'
    Remove-Item Env:\SSL_CERT_FILE -ErrorAction SilentlyContinue
  }
}

function Invoke-Cli([string[]]$CliArgs) {
  Push-Location (Join-Path $RepoPath 'ledger-cli')
  try {
    & uv run ledger-cli @CliArgs 2>&1 | Where-Object { $_ -notmatch 'keycloak\.(refresh|token)' }
    # Stop at the FIRST failed step. Every later step in the tour needs the account step 1 opens, so
    # a 409 there used to print three cascading `account-not-found` 404s — four failures on screen
    # where there was one refusal. Guarded here rather than at seven call sites; only `demo` calls this.
    if ($LASTEXITCODE -ne 0) {
      Die "Step failed (exit $LASTEXITCODE). Stopping: the remaining steps all depend on it."
    }
  }
  finally { Pop-Location }
}

if ($Command -ne 'selfcheck' -and -not (Test-Path $RepoPath)) { Die "No repo at $RepoPath — pass -RepoPath." }

switch ($Command) {

  'help' {
    Write-Host @"

tiny-ledger — operate the stack without rediscovering its traps

  .\dev.ps1 <command> [-RepoPath <path>] [-User <name>] [-Rebuild]

COMMANDS
  help          this
  status        what IS running — containers, ports, what answers
  doctor        whether the machine CAN run it — prerequisites and known-bad state
  standalone    in-memory on 8080, no Docker. Streams the log; Ctrl+C stops
  full          Compose stack, HTTPS via Traefik. -Rebuild forces the image build
  demo          five-command tour of whichever mode is up
  run-e2e       the seven e2e scenarios (needs full). Removes app+traefik on exit
  clean-up      down -v, delete the event store, clear the cached token
  selfcheck     the script's own assertions. Starts nothing

TYPICAL FLOWS
  first thing            doctor  ->  fix anything red  ->  status
  quick API demo         standalone   (second terminal)  demo
  the real thing         full  ->  demo  ->  run-e2e  ->  full   (e2e removes the app)
  between rounds         clean-up  ->  full

USERS (-User)  alice/bob/mallory writer+reader · carol reader · dave auditor
               trent writer+reader+admin · nobody no roles

THINGS THAT LOOK BROKEN AND ARE NOT
  401 on every call        LEDGER_PROFILE not 'full'. This script sets it
  401 after a reset        Keycloak reissued its keys; clear the cached token
  browser 'not private'    private CA, deliberately untrusted. NEVER use -k
  demo fails after e2e     run-e2e removed app+traefik. Run 'full'
  port free but won't bind Docker's ports are invisible to Get-NetTCPConnection

"@ -ForegroundColor Gray
  }

  'doctor' {
    $problems = 0
    function Diag($label, $state, $detail) {
      switch ($state) {
        'ok'   { Write-Host ("  [ ok ] {0,-24} {1}" -f $label, $detail) -ForegroundColor Green }
        'warn' { Write-Host ("  [warn] {0,-24} {1}" -f $label, $detail) -ForegroundColor Yellow }
        'bad'  { Write-Host ("  [FAIL] {0,-24} {1}" -f $label, $detail) -ForegroundColor Red; $script:problems++ }
      }
    }
    Say "`ntiny-ledger doctor — can this machine run it?`n"

    # --- tools -------------------------------------------------------------
    if (Get-Command docker -ErrorAction SilentlyContinue) {
      $v = docker version --format '{{.Server.Version}}' 2>$null
      if ($v) { Diag 'docker' 'ok' "daemon $v" } else { Diag 'docker' 'bad' 'CLI present, daemon not responding — start Docker Desktop' }
    } else { Diag 'docker' 'bad' 'not on PATH' }

    $javaV = (& java -version 2>&1 | Select-Object -First 1)
    if ($javaV -match '"?(\d+)') {
      if ([int]$Matches[1] -ge 25) { Diag 'JDK' 'ok' "$($Matches[1])" }
      else { Diag 'JDK' 'bad' "$($Matches[1]) — the build needs 25" }
    } else { Diag 'JDK' 'bad' 'java not on PATH' }

    if (Get-Command uv -ErrorAction SilentlyContinue) { Diag 'uv' 'ok' (Get-Command uv).Source }
    else { Diag 'uv' 'bad' 'not on PATH — the CLI and e2e need it' }

    if ($gitBash) { Diag 'Git Bash' 'ok' $gitBash }
    else { Diag 'Git Bash' 'bad' 'not found — run-e2e cannot work (bare `bash` is WSL)' }

    # --- the cross-OS venv trap -------------------------------------------
    # A .venv built inside WSL has `home = /home/...` and a lib64 symlink Windows uv cannot
    # delete, so `uv sync` dies with "Access is denied" and nothing explains why. Measured 9 Aug.
    $cfg = Join-Path $RepoPath 'ledger-cli\.venv\pyvenv.cfg'
    if (Test-Path $cfg) {
      $home_ = (Select-String -Path $cfg -Pattern '^home = (.+)$').Matches.Groups[1].Value
      if ($home_ -match '^[A-Za-z]:') { Diag 'ledger-cli/.venv' 'ok' 'built on Windows' }
      else { Diag 'ledger-cli/.venv' 'bad' "built on Linux ($home_) — delete it: rm -rf ledger-cli/.venv" }
    } else { Diag 'ledger-cli/.venv' 'warn' 'absent — uv sync will create it on first use' }

    # --- ports -------------------------------------------------------------
    $o8080 = Get-PortOwner 8080
    if (-not $o8080) { Diag 'port 8080' 'ok' 'free for standalone' }
    elseif ($o8080 -like '*tiny-ledger*') { Diag 'port 8080' 'ok' "$o8080" }
    else { Diag 'port 8080' 'warn' "held by $o8080 — standalone will refuse to start" }

    $o5432 = Get-PortOwner 5432
    if (-not $o5432) { Diag 'port 5432' 'ok' 'free' }
    elseif ($o5432 -like '*tiny-ledger*') { Diag 'port 5432' 'ok' "$o5432" }
    else { Diag 'port 5432' 'bad' "held by $o5432 — postgres will park in 'created' and full breaks" }

    # --- artefacts ---------------------------------------------------------
    if (docker images -q tiny-ledger:local 2>$null) { Diag 'app image' 'ok' 'tiny-ledger:local present' }
    else { Diag 'app image' 'warn' "absent — 'full' will build it (~90s+)" }

    $ca = Join-Path $RepoPath 'docker\tls\ca.crt'
    if (Test-Path $ca) {
      # .NET, not openssl: openssl ships with Git Bash and is NOT on the PowerShell PATH, and a
      # doctor that dies while diagnosing is worse than one that skips a line. Measured 9 Aug.
      try {
        $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($ca)
        if ($cert.NotAfter -lt (Get-Date)) { Diag 'dev CA' 'bad' "EXPIRED $($cert.NotAfter.ToString('yyyy-MM-dd')) — regenerate: scripts/tls/gen-dev-ca.sh --force" }
        else { Diag 'dev CA' 'ok' "valid to $($cert.NotAfter.ToString('yyyy-MM-dd'))" }
      } catch { Diag 'dev CA' 'warn' 'present but unreadable as a certificate' }
    } else { Diag 'dev CA' 'warn' "absent — run-e2e generates it" }

    $cache = Join-Path $env:LOCALAPPDATA 'ledger-cli\ledger-cli\Cache'
    $tok = @(Get-ChildItem $cache -Filter 'token-*.json' -ErrorAction SilentlyContinue)
    if ($tok.Count) { Diag 'cached tokens' 'warn' "$($tok.Count) cached — stale after any reset, causing 401s. 'clean-up' clears them" }
    else { Diag 'cached tokens' 'ok' 'none' }

    # --- repo --------------------------------------------------------------
    Push-Location $RepoPath
    try {
      $branch = (& git branch --show-current 2>$null)
      $dirty  = @(& git status --porcelain 2>$null | Where-Object { $_ -notmatch '\.idea' })
      if ($dirty.Count) { Diag 'repo' 'warn' "$branch, $($dirty.Count) uncommitted file(s) — check before a screen share" }
      else { Diag 'repo' 'ok' "$branch, clean" }
    } finally { Pop-Location }

    $others = @(docker ps --format '{{.Names}}' 2>$null | Where-Object { $_ -notlike 'tiny-ledger-*' })
    if ($others.Count) { Diag 'other containers' 'warn' "$($others.Count) running — may contend for ports: $($others -join ', ')" }
    else { Diag 'other containers' 'ok' 'none' }

    Write-Host ""
    if ($problems) { Die "$problems blocking problem(s). Fix those before demoing." }
    Ok "No blocking problems.`n"
  }

  'status' {
    Say "`n=== containers ==="
    $names = docker ps --format '{{.Names}}' 2>$null
    if (-not $names) { Write-Host "  none running" }
    else {
      docker ps --format '  {{.Names}}  {{.Status}}' | ForEach-Object { Write-Host $_ }
      $other = @($names | Where-Object { $_ -notlike 'tiny-ledger-*' })
      if ($other) { Warn "  $($other.Count) container(s) not part of tiny-ledger — 'clean-up' leaves them alone; stop them yourself before a demo" }
    }

    Say "`n=== ports ==="
    foreach ($p in 8080, 443, 80, 5432, 6379, 9092) {
      $owner = Get-PortOwner $p
      if ($owner) { Write-Host ("  {0,-5} {1}" -f $p, $owner) } else { Write-Host ("  {0,-5} free" -f $p) }
    }

    Say "`n=== reachable? ==="
    try {
      $null = Invoke-RestMethod 'http://localhost:8080/api/v1/accounts' -TimeoutSec 2
      Ok "  standalone is UP on 8080"
    } catch {
      if ($_.Exception.Response.StatusCode.value__) { Ok "  something answering on 8080 (HTTP $($_.Exception.Response.StatusCode.value__))" }
      else { Write-Host "  nothing on 8080" }
    }
    $ca = Join-Path $RepoPath 'docker\tls\ca.crt'
    if (Test-Path $ca) {
      # --ssl-no-revoke: Windows curl is a Schannel build and cannot check revocation on a
      # throwaway CA. Not -k: verification stays ON, which is the property worth keeping.
      $code = & curl.exe -s --ssl-no-revoke --cacert $ca -o NUL -w '%{http_code}' https://app.localhost/api/v1/accounts 2>$null
      if ($code -eq '401') { Ok "  full is UP on https://app.localhost (401 = auth enforced, correct)" }
      elseif ($code -and $code -ne '000') { Ok "  full answering, HTTP $code" }
      else { Write-Host "  full not reachable" }
    }
    Write-Host ""
  }

  'standalone' {
    if (Test-PortBusy 8080) { Die "Port 8080 is held by '$(Get-PortOwner 8080)'. Stop it first — this is what makes spring-boot:run fail." }
    Say "Starting standalone (in-memory, no Docker). Ctrl+C to stop.`n"
    Push-Location $RepoPath
    try { & .\mvnw.cmd spring-boot:run } finally { Pop-Location }
  }

  'full' {
    if (Test-PortBusy 5432) {
      $owner = Get-PortOwner 5432
      if ($owner -notlike '*tiny-ledger*') {
        Die "Port 5432 is held by '$owner'. tiny-ledger-postgres-1 would sit in 'created' and the app could talk to the WRONG database. Stop it, or set `$env:TINY_LEDGER_PG_PORT."
      }
    }
    Push-Location $RepoPath
    try {
      $img = docker images -q tiny-ledger:local 2>$null
      if ($Rebuild -or -not $img) {
        Say "Building the image (~90s+)…"
        & .\mvnw.cmd -q spring-boot:build-image -DskipTests
        if ($LASTEXITCODE -ne 0) { Die "Image build failed." }
      } else { Write-Host "Image tiny-ledger:local present — pass -Rebuild to rebuild." }

      Say "Bringing the stack up…"
      & docker compose -f $compose --profile app up -d --wait
      if ($LASTEXITCODE -ne 0) { Die "Compose failed. Run 'status' to see which port is contested." }
    } finally { Pop-Location }

    Ok "`nUp:  https://app.localhost   (401 without a token — that is correct)"
    Ok "     https://auth.localhost  (Keycloak)"
    Warn "The browser will show ERR_CERT_AUTHORITY_INVALID: the dev CA is deliberately not in any"
    Warn "trust store. The e2e asserts exactly that — 'public trust store -> rejected, as it must be'."
    Write-Host "`nNext:  .\dev.ps1 demo     or     .\dev.ps1 run-e2e`n"
  }

  'demo' {
    $mode = 'standalone'
    $ca = Join-Path $RepoPath 'docker\tls\ca.crt'
    if (Test-Path $ca) {
      $code = & curl.exe -s --ssl-no-revoke --cacert $ca -o NUL -w '%{http_code}' https://app.localhost/api/v1/accounts 2>$null
      if ($code -and $code -ne '000') { $mode = 'full' }
    }
    Say "Driving the '$mode' profile as '$User'.`n"
    Set-LedgerEnv $mode

    $acct = "DEMO-$((Get-Date).ToString('HHmmss'))"
    Say "1. open $acct";            Invoke-Cli @('account','open','--name',$acct,'--currency','GBP')
    Say "`n2. deposit 100.00";      Invoke-Cli @('deposit','--account',$acct,'--amount','100.00')
    Say "`n3. withdraw 30.00";      Invoke-Cli @('withdraw','--account',$acct,'--amount','30.00')

    $mv = [guid]::NewGuid()
    Say "`n4. same movementUid twice — the second must REPLAY, not re-apply"
    Invoke-Cli @('deposit','--account',$acct,'--amount','25.00','--movement-uid',"$mv")
    Invoke-Cli @('deposit','--account',$acct,'--amount','25.00','--movement-uid',"$mv")

    Say "`n5. balance and history"
    Invoke-Cli @('balance','--account',$acct)
    Invoke-Cli @('history','--account',$acct,'--all')
    Write-Host ""
  }

  'run-e2e' {
    if (-not $gitBash) { Die "Git Bash not found. Install Git for Windows — `bash` on PATH is WSL, which has no uv and cannot run this." }
    # Never `bash`: from PowerShell that resolves to C:\windows\system32\bash.exe, which is WSL —
    # a separate Linux world with no uv, where the script correctly refuses to run.
    Say "Running the e2e suite in Git Bash (NOT WSL). Watch for 'passed', not just green.`n"
    $repoUnix = '/' + ($RepoPath -replace ':', '' -replace '\\', '/')
    $inner = @"
cd $repoUnix &&
LEDGER_PROFILE=full \
LEDGER_ISSUER_URI=https://auth.localhost/realms/tiny-ledger \
LEDGER_CLIENT_ID=ledger-test \
LEDGER_USERNAME=$User \
LEDGER_PASSWORD=dev-only \
./scripts/e2e/run-e2e.sh
"@
    # Streamed AND kept: run-e2e.sh always dumps the application log on exit, which buries the
    # pytest summary hundreds of lines up. Telling the reader to "check the count" and then hiding
    # the count is the same defect this whole skill exists to stop, so re-print it last.
    $log = Join-Path $env:TEMP "tiny-ledger-e2e-$(Get-Date -Format yyyyMMdd-HHmmss).log"
    & $gitBash -lc $inner 2>&1 | Tee-Object -FilePath $log
    $code = $LASTEXITCODE

    $summary = Select-String -Path $log -Pattern '\d+ (passed|failed|deselected|error)' |
               Select-Object -Last 1 -ExpandProperty Line
    Write-Host "`n--------------------------------------------------------------"
    if ($summary) {
      $trimmed = $summary.Trim()
      # `7 deselected` with nothing selected is GREEN having tested nothing — pyproject.toml
      # excludes the e2e marker by default. The count is the evidence; the exit code is not.
      if ($trimmed -match '\d+ passed') { Ok "  $trimmed" } else { Warn "  $trimmed  <- no tests SELECTED" }
    } else { Warn "  no pytest summary found in the output — treat this run as unproven" }
    Write-Host "  full log: $log"
    Write-Host "--------------------------------------------------------------"

    # run-e2e.sh brings `app` and `traefik` up ITSELF and REMOVES BOTH on exit — the four backing
    # services survive, the app and the proxy do not. So https://app.localhost is dead after every
    # run, and a `demo` straight afterwards fails with connection refused against a stack that looks
    # up in `docker ps`. Measured 9 Aug. Back-to-back runs can also lose the 443/80 bind race.
    Warn "`nNOTE: the e2e run removed tiny-ledger-app-1 and tiny-ledger-traefik-1."
    Warn "https://app.localhost is DOWN until you run:  .\dev.ps1 full"
    if ($code -ne 0) { Die "e2e failed (exit $code). If this was a repeat run, bring the stack back with '.\dev.ps1 full' and leave a few seconds before retrying." }
  }

  'clean-up' {
    Push-Location $RepoPath
    try {
      # --profile app is MANDATORY: without it Compose leaves the app container running, fails to
      # remove the network, and STILL EXITS 0 (docs/docker.md §8, verified on Compose v2.38.1).
      Say "Tearing down and deleting the event store…"
      & docker compose -f $compose --profile app down -v
    } finally { Pop-Location }

    # Keycloak comes back with new signing keys, so a cached token 401s and the stack looks broken.
    $cache = Join-Path $env:LOCALAPPDATA 'ledger-cli\ledger-cli\Cache'
    if (Test-Path $cache) {
      $n = @(Get-ChildItem $cache -Filter 'token-*.json' -ErrorAction SilentlyContinue)
      if ($n.Count) { Remove-Item (Join-Path $cache 'token-*.json') -Force; Ok "Cleared $($n.Count) cached token(s)." }
      else { Write-Host "No cached tokens." }
    }

    $stray = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($stray) { Warn "Something still holds 8080 (pid $($stray.OwningProcess)) — a leftover standalone run. Stop it before 'standalone'." }

    Ok "Clean. Next: .\dev.ps1 full   (or standalone)"
  }

  'selfcheck' {
    # Assertions only — nothing started, nothing torn down, safe to run any time.
    $fail = 0
    function Check($label, $cond) {
      if ($cond) { Ok "  PASS $label" } else { Write-Host "  FAIL $label" -ForegroundColor Red; $script:fail++ }
    }
    Say "selfcheck"
    Check "repo path exists"            (Test-Path $RepoPath)
    Check "compose file present"        (Test-Path (Join-Path $RepoPath $compose))
    Check "e2e script present"          (Test-Path (Join-Path $RepoPath 'scripts/e2e/run-e2e.sh'))
    Check "ledger-cli present"          (Test-Path (Join-Path $RepoPath 'ledger-cli/pyproject.toml'))
    Check "Git Bash resolved"           ([bool]$gitBash -and (Test-Path $gitBash))
    Check "RepoPath from script dir"    ((Split-Path $PSScriptRoot -Parent) -eq $RepoPath -or $PSBoundParameters.ContainsKey('RepoPath'))
    Check "uv on PATH"                  ([bool](Get-Command uv -ErrorAction SilentlyContinue))
    Check "docker on PATH"              ([bool](Get-Command docker -ErrorAction SilentlyContinue))
    Check "Set-LedgerEnv sets profile"  ((& { Set-LedgerEnv 'full'; $env:LEDGER_PROFILE }) -eq 'full')
    Check "standalone clears SSL_CERT"  ((& { Set-LedgerEnv 'standalone'; $env:SSL_CERT_FILE }) -eq $null)
    Check "port probe returns bool"     ((Test-PortBusy 65535) -is [bool])
    if ($fail) { Die "`n$fail check(s) failed." } else { Ok "`nAll checks passed." }
  }
}
