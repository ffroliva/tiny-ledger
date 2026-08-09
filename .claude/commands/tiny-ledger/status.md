---
description: What is running in the tiny-ledger stack — containers, ports, and which mode is reachable.
allowed-tools: PowerShell
---

Run and show the output verbatim:

```powershell
./scripts/dev.sh status
```

Read it for the user:

- **`tiny-ledger-app-1` or `tiny-ledger-traefik-1` absent while the four backing services are up**
  means an e2e run removed them. `https://app.localhost` is down until `/tiny-ledger:full`.
- **A port owned by something outside the project** — especially 5432 or 8080 — is the thing that
  makes `full` or `standalone` fail. Name the owner.
- **Containers outside `tiny-ledger-*`** are left alone by `clean-up`; say so rather than stopping them.
