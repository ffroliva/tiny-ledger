---
description: Check whether this machine can run tiny-ledger — prerequisites and known-bad conditions, each with its fix.
allowed-tools: PowerShell
---

```powershell
.\scripts\dev.ps1 doctor
```

**Run this before a demo, and first when anything behaves oddly.**

Different job from `/tiny-ledger:status`: status reports what *is* running, doctor reports whether
the machine *can* run it. Checks Docker, JDK 25, uv, Git Bash, the venv's build OS, ports 8080 and
5432, the app image, the dev CA's expiry, cached tokens, the repo's branch and cleanliness, and
containers outside the project.

Report `[FAIL]` lines as blocking and quote the fix. Two that need judgement rather than obedience:

- **`.venv built on Linux`** — a WSL-built venv has a `lib64` symlink Windows `uv` cannot delete, so
  `uv sync` fails with "Access is denied" and nothing explains why. The fix deletes it; say so.
- **`port 5432 held by <something else>`** — do not route around this with `TINY_LEDGER_PG_PORT`
  without saying what it means: another Postgres there makes `tiny-ledger-postgres-1` park in
  `created`, and the compose file warns the silent version of that failure is the dangerous one.

`[warn]` lines are not blocking. Cached tokens only matter after a reset; other containers only
matter if they hold a port.
