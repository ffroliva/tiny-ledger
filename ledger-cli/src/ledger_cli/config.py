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
    base_url: str = "http://127.0.0.1:8080"

    # `full` profile auth (§6.4) — Direct Access Grants against the `ledger-test` fixture client
    # (docker/keycloak/realm-tiny-ledger.json). See NOTES.md for why this, not client-credentials.
    issuer_uri: str = "http://localhost:8081/realms/tiny-ledger"
    client_id: str = "ledger-test"
    username: str | None = None
    password: str | None = None
    token: str | None = None

    timeout: float = 10.0
    json_output: bool = False
