"""`get_access_token` against a mocked Keycloak token endpoint (respx) — the network boundary is
faked; the caching, refresh-vs-password-grant choice, and standalone/full branching are not."""

from pathlib import Path
from typing import Literal

import httpx
import pytest
import respx

from ledger_cli import auth
from ledger_cli.config import Settings

ISSUER = "http://keycloak.testserver/realms/tiny-ledger"
TOKEN_URL = f"{ISSUER}/protocol/openid-connect/token"


@pytest.fixture(autouse=True)
def _isolated_cache(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(auth, "_CACHE_DIR", tmp_path)


def _settings(
    *, profile: Literal["standalone", "full"] = "full", token: str | None = None
) -> Settings:
    return Settings(
        profile=profile,
        issuer_uri=ISSUER,
        client_id="ledger-test",
        username="alice",
        password="dev-only",
        token=token,
    )


def test_standalone_needs_no_token() -> None:
    assert auth.get_access_token(Settings(profile="standalone")) is None


def test_explicit_token_bypasses_keycloak_entirely() -> None:
    # No respx mock registered at all — a real request would raise ConnectionError.
    token = auth.get_access_token(_settings(token="pre-issued-token"))
    assert token == "pre-issued-token"


def test_full_profile_without_credentials_raises() -> None:
    with pytest.raises(RuntimeError, match="full"):
        auth.get_access_token(Settings(profile="full", issuer_uri=ISSUER))


@respx.mock
def test_password_grant_is_cached_across_calls() -> None:
    route = respx.post(TOKEN_URL).mock(
        return_value=httpx.Response(200, json={"access_token": "tok-1", "expires_in": 900})
    )
    settings = _settings()
    first = auth.get_access_token(settings)
    second = auth.get_access_token(settings)
    assert first == second == "tok-1"
    assert route.call_count == 1  # the second call was served from cache


@respx.mock
def test_expired_cache_refreshes_via_refresh_token() -> None:
    respx.post(TOKEN_URL).mock(
        side_effect=[
            httpx.Response(
                200, json={"access_token": "tok-1", "refresh_token": "rt-1", "expires_in": 0}
            ),
            httpx.Response(200, json={"access_token": "tok-2", "expires_in": 900}),
        ]
    )
    settings = _settings()
    first = auth.get_access_token(settings)
    second = auth.get_access_token(settings)
    assert first == "tok-1"
    assert second == "tok-2"


@respx.mock
def test_failed_refresh_falls_back_to_password_grant() -> None:
    respx.post(TOKEN_URL).mock(
        side_effect=[
            httpx.Response(
                200, json={"access_token": "tok-1", "refresh_token": "rt-1", "expires_in": 0}
            ),
            httpx.Response(400, json={"error": "invalid_grant"}),
            httpx.Response(200, json={"access_token": "tok-3", "expires_in": 900}),
        ]
    )
    settings = _settings()
    assert auth.get_access_token(settings) == "tok-1"
    assert auth.get_access_token(settings) == "tok-3"
