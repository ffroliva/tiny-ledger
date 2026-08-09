---
description: Show the tiny-ledger commands, the typical flows, and the failures that look real but are not.
allowed-tools: PowerShell
---

```powershell
.\scripts\dev.ps1 help
```

Show the output verbatim — it is written to be read, not summarised.

If the user asked "what can this do", stop there. If they asked "how do I start", point at the
flow that matches:

- **first thing on a machine** → `/tiny-ledger:doctor`, then `/tiny-ledger:status`
- **quick API demo** → `/tiny-ledger:standalone`, then `/tiny-ledger:demo` in a second terminal
- **the real thing** → `/tiny-ledger:full` → `:demo` → `:run-e2e` → `:full` again (e2e removes the app)
- **between rounds** → `/tiny-ledger:clean-up` → `/tiny-ledger:full`
