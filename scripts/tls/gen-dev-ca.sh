#!/usr/bin/env bash
# Generate a throwaway CA and a leaf certificate for Traefik to terminate TLS with, locally
# and in CI. Output goes to docker/tls/, which is GITIGNORED — this repository is PUBLIC and
# holds the names and shapes of secrets, never their values (docs/security-material.md).
#
# CI runs this same script in-run and therefore needs NO certificate secret, the same principle
# that keeps the Grafana token out of CI entirely. The material is worthless: it lives for the
# length of a job, no client outside this stack ever trusts it, and nothing can be signed with it
# that anything real would accept.
#
# Usage:
#   scripts/tls/gen-dev-ca.sh            # generate if missing, otherwise leave alone
#   scripts/tls/gen-dev-ca.sh --force    # regenerate unconditionally
#
# Idempotent by default on purpose. Regenerating under a running stack would hand Traefik a
# certificate its already-connected clients do not trust, and the resulting failure reads as a
# routing problem rather than as "you re-ran the generator".
set -euo pipefail

# Git Bash rewrites any argument that LOOKS like a Unix path into a Windows one, and every
# `-subj` here starts with a slash. Measured on this machine: without this line the first
# `openssl req` fails with `Subject does not start with '/'` because MSYS had already turned
# `/CN=...` into `C:/Program Files/Git/CN=...`. A no-op on the Linux CI runner. Same trap
# HANDOFF.md records for `docker run --entrypoint=/…`.
export MSYS_NO_PATHCONV=1

OUT=${TLS_DIR:-docker/tls}
DAYS=${TLS_DAYS:-825}

force=false
if [ "${1:-}" = "--force" ]; then
  force=true
elif [ "$#" -gt 0 ]; then
  echo "usage: $0 [--force]" >&2
  exit 2
fi

if [ -f "$OUT/server.crt" ] && [ "$force" = false ]; then
  echo "$OUT/server.crt already exists — leaving it alone (pass --force to regenerate)"
  exit 0
fi

mkdir -p "$OUT"

# BOTH SAN forms, and this is load-bearing rather than belt-and-braces. scripts/e2e/run-e2e.sh
# pins 127.0.0.1 everywhere because `localhost` resolves to ::1 first on the Windows development
# machine and the IPv6 path does not route there — so a certificate carrying only DNS:localhost
# would fail verification on the exact address this repository is obliged to dial. `app` and
# `traefik` are the in-network service names, for any client that ever connects from inside the
# Compose network.
SAN="DNS:localhost,DNS:app,DNS:traefik,IP:127.0.0.1,IP:::1"

# -noenc, not -nodes: same meaning, and -nodes is the deprecated spelling in OpenSSL 3.x. A
# passphrase-less key is the point — Traefik reads it unattended at startup.
openssl req -x509 -newkey rsa:2048 -noenc \
  -keyout "$OUT/ca.key" -out "$OUT/ca.crt" -days "$DAYS" \
  -subj "/CN=tiny-ledger dev CA (THROWAWAY - never trust outside this stack)" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

openssl req -newkey rsa:2048 -noenc \
  -keyout "$OUT/server.key" -out "$OUT/server.csr" \
  -subj "/CN=localhost"

# A REAL temp file, not `<(printf ...)`. Process substitution hands openssl `/dev/fd/63`, which the
# native Windows build cannot open — measured here, verbatim:
#   Can't open "/dev/fd/63" for reading, No such file or directory
# It would have worked on the CI runner and failed on the development machine, which is the worst
# shape of bug to ship. (Found only because this script does NOT send openssl's stderr to
# /dev/null — an earlier draft did, and the failure was invisible: AGENTS.md trap 7.)
printf 'subjectAltName=%s\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' \
  "$SAN" > "$OUT/leaf.ext"

# -copy_extensions is NOT used, and that is deliberate: it would let whatever the CSR asked for
# into the issued certificate. The SANs are named here, on the signing side, which is where a CA
# decides what it is willing to attest to.
openssl x509 -req -in "$OUT/server.csr" \
  -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
  -out "$OUT/server.crt" -days "$DAYS" \
  -extfile "$OUT/leaf.ext"

rm -f "$OUT/server.csr" "$OUT/ca.srl" "$OUT/leaf.ext"

# The generator's own check. `openssl verify` exits non-zero on a bad chain, so under `set -e`
# this line is the gate: a leaf that does not chain to the CA never reaches a caller.
openssl verify -CAfile "$OUT/ca.crt" "$OUT/server.crt"

# The key must not be world-readable. chmod is a no-op on the Windows filesystem and correct on
# the CI runner, so it is applied rather than skipped for the platform where it does nothing.
chmod 600 "$OUT/ca.key" "$OUT/server.key" 2>/dev/null || true

echo "dev CA and leaf written to $OUT/ (gitignored, valid ${DAYS} days)"
