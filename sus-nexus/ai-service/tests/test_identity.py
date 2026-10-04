from __future__ import annotations

import httpx
import respx

from sus_nexus_ai.security.identity import (
    TOKEN_EXCHANGE_GRANT,
    FakeIdentityProvider,
    KeycloakIdentityProvider,
)

TOKEN_URL = "http://keycloak:8180/realms/sus-nexus/protocol/openid-connect/token"


def _keycloak(request: httpx.Request) -> httpx.Response:
    body = request.content.decode()
    if TOKEN_EXCHANGE_GRANT.replace(":", "%3A") in body or TOKEN_EXCHANGE_GRANT in body:
        assert "subject_token=user-token" in body and "actor_token=svc-token" in body
        assert "audience=core-municipal" in body
        return httpx.Response(200, json={"access_token": "exchanged-token", "expires_in": 60})
    assert "grant_type=client_credentials" in body and "client_id=ai-service-agent" in body
    return httpx.Response(200, json={"access_token": "svc-token", "expires_in": 300})


@respx.mock
async def test_client_credentials_cached_and_token_exchange_on_behalf_of() -> None:
    route = respx.post(TOKEN_URL).mock(side_effect=_keycloak)
    provider = KeycloakIdentityProvider(
        TOKEN_URL, "ai-service-agent", "secret", audience="core-municipal"
    )

    svc = await provider.token_for("regulation_completeness", "ibge_3143302")
    again = await provider.token_for("regulation_completeness", "ibge_3143302")
    assert svc.access_token == "svc-token" and svc.subject == "agent:regulation_completeness"
    assert again is svc  # cache
    assert route.call_count == 1

    obo = await provider.token_for(
        "regulation_completeness", "ibge_3143302", on_behalf_of_token="user-token"
    )
    assert obo.access_token == "exchanged-token" and obo.on_behalf_of == "user"
    assert route.call_count == 3  # client credentials + exchange (sem cache para OBO)
    await provider.aclose()


async def test_fake_identity_is_deterministic() -> None:
    token = await FakeIdentityProvider().token_for("a", "ibge_3143302")
    assert token.access_token == "fake-token-a-ibge_3143302" and not token.expired
