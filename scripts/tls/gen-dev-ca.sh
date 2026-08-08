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

# TLS_DIR earned its keep: pointing it elsewhere is how the differential control was run
# (verify the leaf against a CA it was NOT signed by). There is deliberately no knob for the
# validity period -- nothing sets one, and 825 days outlives any stack this issues for.
OUT=${TLS_DIR:-docker/tls}
DAYS=825

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

# EVERY name a client can dial, and each entry is here for a measured reason.
#
# `auth.localhost` is the one that matters most: Traefik fronts Keycloak too, so `iss` is minted at
# that hostname and the CLI dials it over TLS. A certificate missing it means every token request
# fails verification, which surfaces as an authentication problem rather than a certificate one.
#
# `*.localhost` rather than a plain hostname because it resolves to loopback with no /etc/hosts
# edit — and, measured on this machine, it resolves to 127.0.0.1 ONLY, while bare `localhost`
# offers ::1 first and the IPv6 path does not route here. So the sub-domain form is strictly better
# than the trap run-e2e.sh already documents:
#
#   localhost      -> [::1, 127.0.0.1]
#   app.localhost  -> [127.0.0.1]
#   auth.localhost -> [127.0.0.1]
#
# IP:127.0.0.1 stays because the ZAP baseline and the readiness probe dial the address directly.
# `app`, `keycloak` and `traefik` are the in-network service names.
SAN="DNS:localhost,DNS:app.localhost,DNS:auth.localhost,DNS:app,DNS:keycloak,DNS:traefik,IP:127.0.0.1,IP:::1"

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

# A JAVA TRUSTSTORE OF THE SAME CA, because one client here is a JVM and a JVM does not read PEM.
#
# `E2E_MODE=jar` runs the application on the host, where it has to fetch Keycloak's signing keys
# over HTTPS — Traefik fronts the identity provider now, so there is no plaintext path left. The
# variables the other clients use do not reach it: SSL_CERT_FILE is OpenSSL's, and the JVM consults
# its own `cacerts`. Without this the jar boots, answers 401 on the readiness probe, and then fails
# every scenario with an authentication error whose real cause is a certificate one.
#
# `changeit` is the JDK's own default truststore password and is not a secret in any sense: this
# store contains one public certificate and no private key. Naming it here rather than inventing a
# value keeps it out of the "is this a credential?" conversation entirely.
#
# -noprompt because keytool otherwise asks "Trust this certificate?" on stdin, and this script runs
# unattended in CI.
if command -v keytool >/dev/null 2>&1; then
  rm -f "$OUT/truststore.p12"
  keytool -importcert -noprompt -alias tiny-ledger-dev-ca \
    -file "$OUT/ca.crt" -keystore "$OUT/truststore.p12" \
    -storetype PKCS12 -storepass changeit >/dev/null
  echo "java truststore written to $OUT/truststore.p12 (one CA, password 'changeit')"
else
  # Not fatal: only the jar-mode e2e path needs it, and every other consumer of this script has a
  # JDK by definition. Say so rather than failing a run that does not need it.
  echo "keytool not on PATH — skipping the Java truststore; E2E_MODE=jar will not be able to reach Keycloak over TLS" >&2
fi

# The key must not be world-readable. chmod is a no-op on the Windows filesystem and correct on
# the CI runner, so it is applied rather than skipped for the platform where it does nothing.
chmod 600 "$OUT/ca.key" "$OUT/server.key" 2>/dev/null || true

echo "dev CA and leaf written to $OUT/ (gitignored, valid ${DAYS} days)"
