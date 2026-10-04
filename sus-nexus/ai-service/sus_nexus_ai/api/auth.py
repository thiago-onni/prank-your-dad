"""Autenticação Bearer JWT (Keycloak/JWKS) com modo ``mock`` para testes.

* ``AI_AUTH_MODE=jwt``: valida assinatura via JWKS, ``aud`` e expiração; papéis de
  ``realm_access.roles`` + ``resource_access[<client>].roles``; tenant de ``municipality_id``.
* ``AI_AUTH_MODE=mock``: lê ``X-Mock-Subject``, ``X-Mock-Roles`` e ``X-Mock-Tenant``.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import jwt
from fastapi import Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from sus_nexus_ai.config import Settings


class Principal(BaseModel):
    subject: str
    roles: list[str] = Field(default_factory=list)
    tenant: str | None = None
    raw_token: str | None = Field(default=None, exclude=True)

    def has_any_role(self, roles: list[str]) -> bool:
        return any(r in self.roles for r in roles)


class JwtVerifier:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._jwks: jwt.PyJWKClient | None = None

    def _client(self) -> jwt.PyJWKClient:
        if self._jwks is None:
            self._jwks = jwt.PyJWKClient(self._settings.resolved_jwks_url, cache_keys=True)
        return self._jwks

    def verify(self, token: str) -> Principal:
        try:
            signing_key = self._client().get_signing_key_from_jwt(token)
            claims: dict[str, Any] = jwt.decode(
                token,
                signing_key.key,
                algorithms=["RS256", "ES256"],
                audience=self._settings.keycloak_audience,
                options={"require": ["exp", "sub"]},
            )
        except jwt.PyJWTError as exc:
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, f"token inválido: {exc}") from exc
        roles = list(claims.get("realm_access", {}).get("roles", []))
        client_access = claims.get("resource_access", {}).get(self._settings.keycloak_audience, {})
        roles += list(client_access.get("roles", []))
        return Principal(
            subject=str(claims["sub"]),
            roles=sorted(set(roles)),
            tenant=claims.get("municipality_id"),
            raw_token=token,
        )


def _mock_principal(request: Request) -> Principal:
    roles = request.headers.get("X-Mock-Roles", "agent_operator")
    return Principal(
        subject=request.headers.get("X-Mock-Subject", "user:mock"),
        roles=[r.strip() for r in roles.split(",") if r.strip()],
        tenant=request.headers.get("X-Mock-Tenant"),
        raw_token=None,
    )


def get_principal(request: Request) -> Principal:
    settings: Settings = request.app.state.settings
    if settings.auth_mode == "mock":
        return _mock_principal(request)
    header = request.headers.get("Authorization", "")
    if not header.lower().startswith("bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Bearer token obrigatório")
    verifier: JwtVerifier = request.app.state.jwt_verifier
    return verifier.verify(header.split(" ", 1)[1].strip())


def require_roles(*roles: str) -> Callable[..., Principal]:
    def dependency(principal: Principal = Depends(get_principal)) -> Principal:  # noqa: B008
        if not principal.has_any_role(list(roles)):
            raise HTTPException(
                status.HTTP_403_FORBIDDEN, f"papel necessário: {' ou '.join(roles)}"
            )
        return principal

    return dependency


def require_settings_roles(attr: str) -> Callable[..., Principal]:
    """Papéis configuráveis (``admin_roles``, ``approver_roles``) lidos das settings."""

    def dependency(
        request: Request,
        principal: Principal = Depends(get_principal),  # noqa: B008
    ) -> Principal:
        settings: Settings = request.app.state.settings
        allowed: list[str] = getattr(settings, attr)
        if not principal.has_any_role(allowed):
            raise HTTPException(
                status.HTTP_403_FORBIDDEN, f"papel necessário: {' ou '.join(allowed)}"
            )
        return principal

    return dependency
