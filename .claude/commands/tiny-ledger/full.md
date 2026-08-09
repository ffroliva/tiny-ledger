---
description: Bring up the full tiny-ledger stack — Postgres, Redis, Kafka, Keycloak, Traefik, HTTPS.
allowed-tools: PowerShell
---

```powershell
.\scripts\dev.ps1 full
```

Add `-Rebuild` to force the container image build (~90s+); otherwise an existing
`tiny-ledger:local` is reused.

Expect `https://app.localhost` to answer **401** — that is auth being enforced, not a failure.

Also use this to restore the stack **after `/tiny-ledger:run-e2e`**, which removes the app and
Traefik containers when it finishes.

If it refuses because 5432 is contested, do not work around it silently: another Postgres on that
port makes `tiny-ledger-postgres-1` park in `created`, and the compose file warns the silent version
of that failure is worse than the loud one. Stop the other container, or set
`$env:TINY_LEDGER_PG_PORT`.
