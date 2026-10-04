"""Invocação humana de agentes com perfil consultada no OPA (``data.sus.agents.invoke``).

OPA falso via respx: permitido, negado (mesmo com papel local válido), OPA indisponível
(fail-closed → 503) e kill switch enviado no input e respeitado.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from typing import Any

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from sus_nexus_ai.api.app import create_app
from sus_nexus_ai.config import Settings
from sus_nexus_ai.security.kill_switch import KillSwitch, KillSwitchState
from sus_nexus_ai.security.policy import (
    AgentPolicyClient,
    InvokeAgent,
    InvokeInput,
    InvokeSubject,
    PolicyInput,
    PolicyUnavailable,
    evaluate_invoke_locally,
    evaluate_locally,
)
from sus_nexus_ai.service import AIService, build_service
from sus_nexus_ai.tools.analytics import InMemoryAggregatedAnalytics
from tests.bi_data import COMPETENCE, OTHER_TENANT, TENANT, all_rows

AGENT = "bi_situation_analyst"
OPA = "http://opa-test:8181"
INVOKE_URL = f"{OPA}/v1/data/sus/agents/invoke"
DECISION_URL = f"{OPA}/v1/data/sus/agents/decision"
HEADERS = {"X-Mock-Subject": "user:gestor-1", "X-Mock-Roles": "gestor", "X-Mock-Tenant": TENANT}
ADMIN = {"X-Mock-Subject": "user:dpo-1", "X-Mock-Roles": "dpo", "X-Mock-Tenant": TENANT}
BODY = {"competence": COMPETENCE, "trend_months": 3}


def _invoke(
    roles: list[str],
    subject_tenant: str | None = TENANT,
    agent: str = AGENT,
    ks: KillSwitchState | None = None,
) -> InvokeInput:
    return InvokeInput(
        agent=InvokeAgent(id=agent),
        subject=InvokeSubject(roles=roles, tenant=subject_tenant),
        tenant=TENANT,
        kill_switch=ks or KillSwitchState(),
    )


# ---------------------------------------------------------------------------
# Espelho local (mesmos casos de policies/sus/agents/agents_test.rego)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("role", ["gestor", "auditor", "admin_municipal"])
def test_local_invoke_allows_management_roles(role: str) -> None:
    decision = evaluate_invoke_locally(_invoke([role]))
    assert decision.allow and decision.reasons == ["granted"]


@pytest.mark.parametrize("role", ["profissional_aps", "acs", "regulador", "agendador", "dpo"])
def test_local_invoke_denies_other_roles(role: str) -> None:
    decision = evaluate_invoke_locally(_invoke([role]))
    assert not decision.allow and "role_not_allowed_to_invoke" in decision.reasons


def test_local_invoke_tenant_kill_switch_and_profile() -> None:
    assert evaluate_invoke_locally(_invoke(["gestor"], OTHER_TENANT)).reasons == ["tenant_mismatch"]
    assert "tenant_mismatch" in evaluate_invoke_locally(_invoke(["gestor"], None)).reasons
    assert "agent_without_invocation_profile" in (
        evaluate_invoke_locally(_invoke(["gestor"], agent="regulation_completeness")).reasons
    )
    for ks in (
        KillSwitchState(global_=True),
        KillSwitchState(agents=[AGENT]),
        KillSwitchState(tenants=[TENANT]),
    ):
        decision = evaluate_invoke_locally(_invoke(["gestor"], ks=ks))
        assert not decision.allow and decision.reasons == ["kill_switch"]


# ---------------------------------------------------------------------------
# Cliente OPA: contrato de data.sus.agents.invoke
# ---------------------------------------------------------------------------


@respx.mock
async def test_opa_invoke_contract_and_cache() -> None:
    route = respx.post(INVOKE_URL).mock(
        return_value=httpx.Response(
            200, json={"result": {"allow": True, "reasons": ["granted"], "policy_version": "1.1.0"}}
        )
    )
    client = AgentPolicyClient(OPA, cache_ttl_seconds=30)
    ks = KillSwitchState(tools=["core.create_task"])
    first = await client.decide_invoke(_invoke(["gestor"], ks=ks))
    second = await client.decide_invoke(_invoke(["gestor"], ks=ks))
    assert first.allow and first.policy_version == "1.1.0" and second == first
    assert route.call_count == 1
    sent = json.loads(route.calls[0].request.read())
    assert sent == {
        "input": {
            "agent": {"id": AGENT},
            "subject": {"roles": ["gestor"], "tenant": TENANT},
            "tenant": TENANT,
            "kill_switch": {
                "global": False,
                "agents": [],
                "tools": ["core.create_task"],
                "tenants": [],
            },
        }
    }
    await client.aclose()


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(500),
        httpx.Response(200, json={}),
        httpx.Response(200, json={"result": {"reasons": []}}),
        httpx.Response(200, text="not json"),
    ],
)
async def test_opa_invoke_unavailable_fails_closed(response: httpx.Response) -> None:
    with respx.mock:
        respx.post(INVOKE_URL).mock(return_value=response)
        client = AgentPolicyClient(OPA, cache_ttl_seconds=0)
        with pytest.raises(PolicyUnavailable):
            await client.decide_invoke(_invoke(["gestor"]))
        await client.aclose()


# ---------------------------------------------------------------------------
# Rota dedicada com OPA falso
# ---------------------------------------------------------------------------


class FakeOpa:
    """Responde decision/invoke usando o espelho local (equivalente ao Rego) ou respostas fixas."""

    def __init__(self) -> None:
        self.invoke_inputs: list[dict[str, Any]] = []
        self.invoke_override: Callable[[], httpx.Response] | None = None

    def invoke(self, request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.read())["input"]
        self.invoke_inputs.append(payload)
        if self.invoke_override is not None:
            return self.invoke_override()
        decision = evaluate_invoke_locally(InvokeInput.model_validate(payload))
        return httpx.Response(200, json={"result": decision.model_dump()})

    def decision(self, request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.read())["input"]
        decision = evaluate_locally(PolicyInput.model_validate(payload))
        return httpx.Response(200, json={"result": decision.model_dump()})


@pytest.fixture
def fake_opa() -> Iterator[FakeOpa]:
    fake = FakeOpa()
    with respx.mock(assert_all_called=False) as router:
        router.post(INVOKE_URL).mock(side_effect=fake.invoke)
        router.post(DECISION_URL).mock(side_effect=fake.decision)
        yield fake


@pytest.fixture
def opa_service(settings: Settings, kill_switch: KillSwitch) -> AIService:
    indicators, gaps = all_rows()
    return build_service(
        settings,
        kill_switch=kill_switch,
        analytics=InMemoryAggregatedAnalytics(indicators=indicators, care_gaps=gaps),
        policy=AgentPolicyClient(OPA, cache_ttl_seconds=0),
    )


@pytest.fixture
def opa_client(settings: Settings, opa_service: AIService) -> Iterator[TestClient]:
    with TestClient(create_app(settings, service=opa_service)) as client:
        yield client


def _run(client: TestClient, headers: dict[str, str] | None = None) -> Any:
    return client.post(f"/agents/{AGENT}/run", json=BODY, headers=headers or HEADERS)


def test_opa_allows_invocation_with_roles_tenant_and_kill_switch(
    fake_opa: FakeOpa, opa_client: TestClient, opa_service: AIService
) -> None:
    resp = _run(opa_client)
    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "completed"
    assert fake_opa.invoke_inputs == [
        {
            "agent": {"id": AGENT},
            "subject": {"roles": ["gestor"], "tenant": TENANT},
            "tenant": TENANT,
            "kill_switch": {"global": False, "agents": [], "tools": [], "tenants": []},
        }
    ]
    assert opa_service.repository.list_runs(agent_id=AGENT)


def test_opa_denial_is_403_even_with_valid_local_role(
    fake_opa: FakeOpa, opa_client: TestClient, opa_service: AIService
) -> None:
    fake_opa.invoke_override = lambda: httpx.Response(
        200,
        json={"result": {"allow": False, "reasons": ["role_not_allowed_to_invoke"]}},
    )
    resp = _run(opa_client)
    assert resp.status_code == 403
    assert resp.headers["content-type"].startswith("application/problem+json")
    assert "role_not_allowed_to_invoke" in resp.json()["detail"]
    assert opa_service.repository.list_runs(agent_id=AGENT) == []  # nada executado


def test_local_role_check_denies_before_opa(fake_opa: FakeOpa, opa_client: TestClient) -> None:
    resp = _run(opa_client, {**HEADERS, "X-Mock-Roles": "profissional_aps"})
    assert resp.status_code == 403
    assert fake_opa.invoke_inputs == []  # defesa em profundidade: nem chega ao OPA


@pytest.mark.parametrize(
    "failure",
    [
        lambda: httpx.Response(500),
        lambda: httpx.Response(200, json={}),
        lambda: (_ for _ in ()).throw(httpx.ConnectError("opa down")),
    ],
    ids=["http_500", "no_result", "connect_error"],
)
def test_opa_unavailable_is_503_fail_closed(
    fake_opa: FakeOpa,
    opa_client: TestClient,
    opa_service: AIService,
    failure: Callable[[], httpx.Response],
) -> None:
    fake_opa.invoke_override = failure
    resp = _run(opa_client)
    assert resp.status_code == 503
    assert resp.headers["content-type"].startswith("application/problem+json")
    assert resp.json()["title"] == "Service Unavailable"
    assert opa_service.repository.list_runs(agent_id=AGENT) == []


@pytest.mark.parametrize(
    "state",
    [{"global": True}, {"agents": [AGENT]}, {"tenants": [TENANT]}],
    ids=["global", "agent", "tenant"],
)
def test_kill_switch_is_sent_to_opa_and_denies(
    fake_opa: FakeOpa, opa_client: TestClient, opa_service: AIService, state: dict[str, Any]
) -> None:
    assert opa_client.post("/admin/kill-switch", json=state, headers=ADMIN).status_code == 200
    resp = _run(opa_client)
    assert resp.status_code == 403
    assert "kill_switch" in resp.json()["detail"]
    sent = fake_opa.invoke_inputs[-1]["kill_switch"]
    expected = {"global": False, "agents": [], "tools": [], "tenants": [], **state}
    assert sent == expected
    assert opa_service.repository.list_runs(agent_id=AGENT) == []
    # desligado o kill switch, a invocação volta a ser permitida
    opa_client.post("/admin/kill-switch", json={"global": False}, headers=ADMIN)
    assert _run(opa_client).status_code == 200


def test_local_policy_mode_also_denies_on_kill_switch(
    settings: Settings, kill_switch: KillSwitch
) -> None:
    indicators, gaps = all_rows()
    service = build_service(
        settings,
        kill_switch=kill_switch,
        analytics=InMemoryAggregatedAnalytics(indicators=indicators, care_gaps=gaps),
    )
    with TestClient(create_app(settings, service=service)) as client:
        kill_switch.set_admin_state(KillSwitchState(agents=[AGENT]))
        resp = _run(client)
        assert resp.status_code == 403 and "kill_switch" in resp.json()["detail"]
