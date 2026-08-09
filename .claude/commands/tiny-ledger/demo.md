---
description: Run the five-command tiny-ledger tour — open, deposit, withdraw, idempotent replay, balance and history.
allowed-tools: PowerShell
---

```powershell
.\scripts\dev.ps1 demo
```

Auto-detects whichever mode is up. Add `-User dave` for the auditor, `-User carol` for a
reader-only principal.

**Step 4 is the one worth narrating.** The same `movementUid` is sent twice; the second returns
`replayed (idempotent — original result, not re-applied)` and the balance does not move. That is the
property a payments client actually needs from a retry, shown rather than described.

Say precisely what it proves: **idempotency protects a retry, not a re-run.** Two `deposit` calls
without a pinned `--movement-uid` are two real deposits, because the CLI mints a fresh uid each time.

If it fails with connection refused, an e2e run removed the app and Traefik — `/tiny-ledger:full`
restores them.
