---
description: Reset tiny-ledger for a clean round — tear down, delete the event store, clear the cached token.
allowed-tools: PowerShell
---

```powershell
./scripts/dev.sh clean-up
```

**This deletes the event store** (`down -v` removes the `postgres-data` volume). That is the point;
it is dev data. Say so before running it if the user might not expect it.

Three steps, and the third is the one people miss: after the volume goes, Keycloak restarts with new
signing keys, so the CLI's **cached token is stale and everything returns 401** — which reads as a
broken stack. The script clears it.

`--profile app` is passed on `down` because without it Compose leaves the app container running,
fails to remove the network, **and still exits 0**.

Containers outside `tiny-ledger-*` are left alone. If `status` flagged any, mention them — stopping
them is the user's call.

Standalone needs none of this: restart the app and it is reset.
