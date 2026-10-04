"""Identidade do agente (AIA-012): client-credentials Keycloak + token exchange "em nome de".

O agente nunca usa o token de um usuário diretamente: obtém um token de serviço próprio
(client ``ai-service-agent``) e, quando a execução foi disparada por um usuário, faz
*token exchange* (RFC 8693) para carregar o ``act``/``sub`` do usuário na chamada ao core.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Protocol

import httpx

from sus_nexus_ai.common import get_logger

log = get_logger(__name__)

TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange"


@dataclass(frozen=True)
class AgentToken:
    access_token: str
    expires_at: float
    subject: str
    on_behalf_of: str | None = None

    @property
    def expired(self) -> bool:
        return time.time() >= self.expires_at - 15


class IdentityProvider(Protocol):
    async def token_for(
        self, agent_id: str, tenant: str, on_behalf_of_token: str | None = None
    ) -> AgentToken: ...


class FakeIdentityProvider:
    """Identidade determinística para testes/dev."""

    async def token_for(
        self, agent_id: str, tenant: str, on_behalf_of_token: str | None = None
    ) -> AgentToken:
        subject = f"agent:{agent_id}"
        return AgentToken(
            access_token=f"fake-token-{agent_id}-{tenant}",
            expires_at=time.time() + 300,
            subject=subject,
            on_behalf_of="user" if on_behalf_of_token else None,
        )


class KeycloakIdentityProvider:
    def __init__(
        self,
        token_url: str,
        client_id: str,
        client_secret: str,
        audience: str = "core-municipal",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._token_url = token_url
        self._client_id = client_id
        self._client_secret = client_secret
        self._audience = audience
        self._client = client or httpx.AsyncClient(timeout=5.0)
        self._cache: dict[str, AgentToken] = {}

    async def _request(self, data: dict[str, str]) -> dict[str, object]:
        resp = await self._client.post(self._token_url, data=data)
        resp.raise_for_status()
        body: dict[str, object] = resp.json()
        return body

    async def token_for(
        self, agent_id: str, tenant: str, on_behalf_of_token: str | None = None
    ) -> AgentToken:
        cache_key = f"{agent_id}:{tenant}:{'obo' if on_behalf_of_token else 'svc'}"
        cached = self._cache.get(cache_key)
        if cached and not cached.expired and on_behalf_of_token is None:
            return cached

        service = await self._request(
            {
                "grant_type": "client_credentials",
                "client_id": self._client_id,
                "client_secret": self._client_secret,
                "scope": "openid",
            }
        )
        access_token = str(service["access_token"])
        expires_in = float(service.get("expires_in", 300))  # type: ignore[arg-type]
        subject = f"agent:{agent_id}"
        on_behalf_of: str | None = None

        if on_behalf_of_token:
            exchanged = await self._request(
                {
                    "grant_type": TOKEN_EXCHANGE_GRANT,
                    "client_id": self._client_id,
                    "client_secret": self._client_secret,
                    "subject_token": on_behalf_of_token,
                    "subject_token_type": "urn:ietf:params:oauth:token-type:access_token",
                    "actor_token": access_token,
                    "actor_token_type": "urn:ietf:params:oauth:token-type:access_token",
                    "audience": self._audience,
                    "requested_token_type": "urn:ietf:params:oauth:token-type:access_token",
                }
            )
            access_token = str(exchanged["access_token"])
            expires_in = float(exchanged.get("expires_in", expires_in))  # type: ignore[arg-type]
            on_behalf_of = "user"

        token = AgentToken(
            access_token=access_token,
            expires_at=time.time() + expires_in,
            subject=subject,
            on_behalf_of=on_behalf_of,
        )
        if on_behalf_of_token is None:
            self._cache[cache_key] = token
        log.info("identity.token_issued", agent_id=agent_id, on_behalf_of=on_behalf_of is not None)
        return token

    async def aclose(self) -> None:
        await self._client.aclose()
