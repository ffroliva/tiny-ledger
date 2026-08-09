---
description: Run the seven tiny-ledger e2e scenarios over HTTPS. Needs the full stack up.
allowed-tools: PowerShell
---

```powershell
./scripts/dev.sh run-e2e
```

**Report the count, not the exit code.** `ledger-cli/pyproject.toml` deselects the `e2e` marker by
default, so `7 deselected` is a green run that tested nothing. The script re-prints the pytest
summary last — quote that line. `7 passed, 52 deselected` is the good result.

**Afterwards, say that the app and Traefik containers were removed.** The script does that itself,
so `https://app.localhost` is down until `/tiny-ledger:full`. A `demo` straight after will fail with
connection refused against a stack that still looks up in `docker ps`.

Runs in Git Bash by full path, never `bash` — from PowerShell that resolves to WSL, which has no
`uv`. Back-to-back runs can lose the 443/80 bind race; bring the stack back and leave a few seconds.
