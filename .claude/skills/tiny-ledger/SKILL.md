---
name: tiny-ledger
description: Use when starting, demoing, testing or resetting the tiny-ledger stack — "run the ledger", "start it standalone", "bring up full", "run the e2e", "reset the stack", "why is it 401", "why is the browser saying not private", "port already allocated", or before a live demo of it.
---

# tiny-ledger

## Overview

Operates the tiny-ledger repository at `C:\development\github\tiny-ledger` — a separate git repo.
**This skill never edits it.** Changes there go through that repo's PR flow (eight required checks).

Core principle: **the commands are easy; the traps are not.** Every trap below cost real time on
8–9 August 2026. `dev.sh` encodes them so they are not rediscovered under pressure.

## When to Use

- Starting either run mode, demoing the API, running the e2e suite, resetting between rounds
- Diagnosing `401 Unauthenticated`, `port is already allocated`, `ERR_CERT_AUTHORITY_INVALID`,
  `uv not found`, or a `verify` that fails for no visible reason

**Not for:** changing the ledger's code, its CI, or its docs.

## Quick Reference

```powershell
./scripts/dev.sh <command>
```

| Command | Does | Note |
|---|---|---|
| `help` | commands, flows, and failures that only look real | written to be read verbatim |
| `doctor` | can this machine run it — prerequisites, known-bad state | **before any demo** |
| `status` | containers, ports, what is reachable | what *is*, not what *can* |
| `standalone` | in-memory on 8080, no Docker | streams the log; Ctrl+C stops |
| `full` | Compose + HTTPS via Traefik | refuses if 5432 is contested |
| `demo` | five-command tour of whichever mode is up | auto-detects the mode |
| `run-e2e` | seven e2e scenarios | **removes app + traefik on exit** |
| `clean-up` | `down -v` + clears the cached token | deletes the event store |
| `selfcheck` | assertions only | starts nothing |

Flags: `--repo`, `--user alice|bob|carol|dave|trent`, `--rebuild`.

## Common Mistakes

| Symptom | Cause | Fix |
|---|---|---|
| Every CLI call `401` | `LEDGER_PROFILE` not `full` — no token is attached | the script sets it |
| `401` right after a reset | Keycloak restarted with new signing keys; cached token stale | clear `%LOCALAPPDATA%\ledger-cli\ledger-cli\Cache` |
| `demo` fails after `run-e2e` | that script **removes app + traefik** when it finishes | re-run `full` |
| `down` "worked", stack still up | missing `--profile app` — leaves the app running **and exits 0** | always pass it |
| `port is already allocated` on 5432 | another Postgres (Supabase); container parks in `created` | stop it, or `$env:TINY_LEDGER_PG_PORT` |
| `uv not found` from PowerShell | bare `bash` is WSL, not Git Bash | invoke Git Bash by full path |
| curl rejects the dev CA | Windows curl is a Schannel build | `--ssl-no-revoke`, or use the CLI |
| `verify` fails impossibly | concurrent Maven builds corrupt `target/` | `mvnw clean verify` |
| Port shows "free" but binding fails | `Get-NetTCPConnection` cannot see Docker's published ports | ask `docker ps` too |

## Two things to say correctly

**The certificate is not invalid — the *authority* is untrusted.** A private CA, deliberately in no
trust store, named *THROWAWAY — never trust outside this stack*. Chain and SANs are correct. The e2e
asserts both halves: `public trust store -> rejected, as it must be` / `dev CA -> verified`.
**Never `-k`** — it disables what `scripts/e2e/https-check.py` exists to prove.

**Read the e2e count, not the colour.** `7 deselected` is green having tested nothing.

## Rules

- `clean-up` deletes the event store — dev data, by design.
- `clean-up` never stops containers outside the project; `status` warns instead.
- Never add `container_name` to the compose file: globally unique, so a second worktree cannot run
  the stack. Rejected 9 Aug.
