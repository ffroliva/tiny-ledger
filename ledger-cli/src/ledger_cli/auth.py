"""Keycloak tokens for the `full` profile (spec §6.4).

**Direct Access Grants (Resource Owner Password Credentials), not client-credentials** — see
NOTES.md for why: `docker/keycloak/realm-tiny-ledger.json` defines only public clients
(`ledger-test`, `ledger-other`) with `directAccessGrantsEnabled: true` and password credentials on
the six fixture *users*; it has no confidential client and no service account, so there is no
client-credentials grant available to use.

Tokens are cached under `platformdirs.user_cache_dir("ledger-cli")`, keyed by
`(issuer_uri, client_id, username)`, refreshed via the refresh token when possible and re-fetched
via password grant otherwise. `--token` bypasses all of this.
"""

from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path
from typing import Any, cast

import httpx
import structlog
from platformdirs import user_cache_dir

from ledger_cli.config import Settings

log = structlog.get_logger(__name__)

_CACHE_DIR = Path(user_cache_dir("ledger-cli"))


def _cache_file(settings: Settings) -> Path:
    key = f"{settings.issuer_uri}|{settings.client_id}|{settings.username or ''}"
    digest = hashlib.sha256(key.encode()).hexdigest()[:16]
    return _CACHE_DIR / f"token-{digest}.json"


def _load_cached(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        data: object = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError):
        return None
    return cast(dict[str, Any], data) if isinstance(data, dict) else None


def _store(path: Path, data: dict[str, Any]) -> None:
    _CACHE_DIR.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data))
    try:
        path.chmod(0o600)  # ponytail: best-effort — NTFS ACLs don't map 1:1 to POSIX mode bits
    except OSError:
        pass


def _token_request(
    settings: Settings, client: httpx.Client, *, grant_type: str, **fields: str
) -> dict[str, Any]:
    token_url = f"{settings.issuer_uri.rstrip('/')}/protocol/openid-connect/token"
    data = {"grant_type": grant_type, "client_id": settings.client_id, **fields}
    resp = client.post(token_url, data=data, timeout=settings.timeout)
    resp.raise_for_status()
    payload: dict[str, Any] = resp.json()
    return payload


def get_access_token(settings: Settings) -> str | None:
    """A bearer token for `full`, or `None` for `standalone` (§6.4's fixed local principal)."""
    if settings.profile == "standalone":
        return None
    if settings.token:
        return settings.token
    if not settings.username or not settings.password:
        raise RuntimeError(
            "the `full` profile needs --token, or --user/--password for a Keycloak fixture user "
            "(docker/keycloak/realm-tiny-ledger.json)"
        )

    path = _cache_file(settings)
    cached = _load_cached(path)
    now = time.time()
    if cached is not None and float(cached.get("expires_at", 0)) > now + 5:
        return str(cached["access_token"])

    with httpx.Client() as client:
        payload: dict[str, Any] | None = None
        refresh_token = cached.get("refresh_token") if cached else None
        if refresh_token:
            try:
                payload = _token_request(
                    settings, client, grant_type="refresh_token", refresh_token=refresh_token
                )
            except httpx.HTTPStatusError:
                log.info("keycloak.refresh.failed", client_id=settings.client_id)
                payload = None
        if payload is None:
            payload = _token_request(
                settings,
                client,
                grant_type="password",
                username=settings.username,
                password=settings.password,
            )

    access_token = str(payload["access_token"])
    expires_at = now + float(payload.get("expires_in", 60))
    _store(
        path,
        {
            "access_token": access_token,
            "refresh_token": payload.get("refresh_token"),
            "expires_at": expires_at,
        },
    )
    log.info("keycloak.token.acquired", client_id=settings.client_id, username=settings.username)
    return access_token
