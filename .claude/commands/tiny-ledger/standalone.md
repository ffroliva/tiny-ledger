---
description: Start tiny-ledger in standalone mode — in-memory on port 8080, no Docker.
allowed-tools: PowerShell
---

```powershell
.\scripts\dev.ps1 standalone
```

This **streams the application log and does not return** — it is the running app. Ctrl+C stops it.
Run it in the background if the user needs the terminal back.

Wait for `AUTH DISABLED (standalone)`. Auth is off and storage is in memory, so restarting the app
*is* the reset — `/tiny-ledger:clean-up` is for `full` only.

If it refuses to start, port 8080 is held — usually a leftover run. The script names the owner.
