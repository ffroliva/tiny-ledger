"""Wait for the application over HTTPS, then PROVE the transport is real TLS.

Run through the CLI's environment (``uv run --project ledger-cli``) so it uses the very TLS stack
the e2e scenarios use — not a second, more forgiving one::

    uv run --project ledger-cli python scripts/e2e/https-check.py \
        https://127.0.0.1:8443 http://127.0.0.1:8000 120 /abs/path/to/docker/tls/ca.crt

**Why this is Python and not ``curl``.** ``scripts/e2e/wait-for.sh`` is curl, and curl on the
Windows development machine is a **Schannel** build (measured: ``curl 8.12.1 ... Schannel``, no
OpenSSL backend, and ``CURL_SSL_BACKEND=openssl`` does not switch it). Schannel resolves chains
through the Windows certificate store, so a private CA passed with ``--cacert`` is loaded and then
rejected anyway::

    schannel: added 1 certificate(s) from CA file 'docker/tls/ca.crt'
    schannel: CertGetCertificateChain trust error CERT_TRUST_IS_UNTRUSTED_ROOT

That would have worked on the Linux CI runner and failed on the machine this repository is
developed on — and the tempting repairs are both wrong. ``-k`` deletes the very check this file
exists to make, and installing a throwaway CA into the operator's Windows trust store is a change
to their machine, made by a test script, that nothing removes afterwards.

Python's ``ssl`` is OpenSSL on every platform, ``uv`` is already a hard prerequisite of
``run-e2e.sh`` (guarded on its first line), and ``httpx`` is the client the scenarios themselves
use. So this asks the question with the stack whose answer actually matters.

**Three checks, and the middle one is the point.** A ``https://`` in a variable is not evidence of
anything: the suite would pass just as happily against a client that skipped verification, or
against Traefik's own built-in self-signed certificate — which is exactly what would be served if
the bind mount had silently produced an empty directory, since that is what Compose does for a
missing source path. So the same request runs twice, with the only difference being which
certificate authority is offered, and BOTH outcomes are required.
"""

from __future__ import annotations

import ssl
import sys
import time

import certifi
import httpx

READY_STATUS = 401
"""Spec §6.4: an unauthenticated request to a protected route. A listening socket proves a process
bound a port; a 401 proves the application is serving AND the security chain is wired. A 200 here
would itself be a defect, so the probe fails loudly on the outcome that looks most like success —
the same reasoning ``wait-for.sh`` carries."""


def _context(ca_file: str) -> ssl.SSLContext:
    return ssl.create_default_context(cafile=ca_file)


def main() -> int:
    https_base, http_base, timeout_s = sys.argv[1], sys.argv[2], float(sys.argv[3])
    # ABSOLUTE, passed in by the caller. run-e2e.sh invokes this from inside ledger-cli/ so that uv
    # resolves the CLI's own environment, and a path relative to the repository root would silently
    # miss from there — the same class of bug as the relative APP_LOG that runner's EXIT trap
    # already carries a comment about.
    ca_file = sys.argv[4]
    url = f"{https_base}/api/v1/accounts"

    # 1. Readiness, over TLS, verified against the dev CA.
    deadline = time.monotonic() + timeout_s
    last = "no attempt made"
    started = time.monotonic()
    with httpx.Client(verify=_context(ca_file), timeout=5.0) as client:
        while time.monotonic() < deadline:
            try:
                status = client.get(url).status_code
                if status == READY_STATUS:
                    print(f"tiny-ledger (full, HTTPS) ready after {time.monotonic() - started:.0f}s (HTTP {status})")
                    break
                last = f"HTTP {status}"
            except Exception as exc:  # connection refused while the container boots, and TLS errors
                last = f"{type(exc).__name__}: {exc}"
            time.sleep(2)
        else:
            print(
                f"::error::never returned HTTP {READY_STATUS} over HTTPS within {timeout_s:.0f}s (last: {last})",
                file=sys.stderr,
            )
            return 1

    # 2. THE CONTROL. The identical request against the PUBLIC trust store must be refused. Without
    #    this, check 1 is consistent with a client that verifies nothing.
    try:
        with httpx.Client(verify=_context(certifi.where()), timeout=5.0) as public:
            public.get(url)
    except ssl.SSLError:
        print("  public trust store        -> rejected, as it must be")
    except httpx.ConnectError as exc:
        # httpx wraps the handshake failure; accept it only if the cause really is a certificate
        # problem, never merely because the connection died for some unrelated reason.
        if "CERTIFICATE_VERIFY_FAILED" not in str(exc):
            print(f"::error::expected a certificate verification failure, got: {exc}", file=sys.stderr)
            return 1
        print("  public trust store        -> rejected, as it must be")
    else:
        print(
            "::error::the same request SUCCEEDED against the public trust store — the certificate is "
            "not the dev CA's, or verification is off, so nothing above is evidence of TLS",
            file=sys.stderr,
        )
        return 1
    print("  dev CA                    -> verified")

    # 3. The plaintext entrypoint exists only to move callers to TLS. A redirect that quietly
    #    stopped working would leave an unencrypted door open on a published port.
    with httpx.Client(follow_redirects=False, timeout=5.0) as plain:
        redirect = plain.get(f"{http_base}/api/v1/accounts")
    if redirect.status_code != 301 or not redirect.headers.get("location", "").startswith("https://"):
        print(
            f"::error::the plaintext entrypoint answered {redirect.status_code} "
            f"(location: {redirect.headers.get('location')!r}), expected a 301 to https://",
            file=sys.stderr,
        )
        return 1
    print(f"  {http_base} -> 301 {redirect.headers['location']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
