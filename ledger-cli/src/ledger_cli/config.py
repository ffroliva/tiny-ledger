"""Settings for both run modes (spec §7's `standalone`/`full` split).

Precedence, high to low: explicit CLI flags > `LEDGER_*` environment variables > these defaults.
`base_url` is the same for both profiles — one jar, one port, selected by Spring profile, not by
a different bind address (README's `./mvnw spring-boot:run`). `issuer_uri` mirrors the app's own
`LEDGER_ISSUER_URI` default (`application-full.properties`) so a single env var configures both
sides of a `full`-profile run.
"""

from __future__ import annotations

from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="LEDGER_", extra="ignore")

    profile: Literal["standalone", "full"] = "standalone"
    # Unchanged, and it is the STANDALONE default: that mode runs a plain jar on loopback with no
    # proxy and no TLS. `full` behind Traefik is https://127.0.0.1 (port 443), supplied by
    # LEDGER_BASE_URL, which scripts/e2e/run-e2e.sh exports according to the mode it chose.
    base_url: str = "http://127.0.0.1:8080"

    # `full` profile auth (§6.4) — Direct Access Grants against the `ledger-test` fixture client
    # (docker/keycloak/realm-tiny-ledger.json). See NOTES.md for why this, not client-credentials.
    # HTTPS at Traefik's hostname — the proxy fronts Keycloak too, so `iss` is minted at the edge.
    # This client therefore needs to trust the dev CA for the TOKEN request as well as for the API
    # request; `scripts/e2e/run-e2e.sh` exports SSL_CERT_FILE, which httpx honours for both.
    issuer_uri: str = "https://auth.localhost/realms/tiny-ledger"
    client_id: str = "ledger-test"
    username: str | None = None
    password: str | None = None
    token: str | None = None

    timeout: float = 10.0
    json_output: bool = False
