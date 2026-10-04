from __future__ import annotations

import httpx
import pytest
import respx

from sus_nexus_ai.security.kill_switch import KillSwitchState
from sus_nexus_ai.security.policy import (
    AgentIdentity,
    AgentPolicyClient,
    PolicyInput,
    PolicyUnavailable,
    evaluate_locally,
)

AGENT = AgentIdentity(
    id="regulation_completeness", version="1.0.0", tools_granted=["core.create_task"]
)


def _input(tool: str, action_class: str, ks: KillSwitchState | None = None) -> PolicyInput:
    return PolicyInput(
        agent=AGENT,
        tool=tool,
        action_class_requested=action_class,
        tenant="ibge_3143302",
        kill_switch=ks or KillSwitchState(),
    )


def test_forbidden_is_always_denied() -> None:
    agent = AgentIdentity(id="x", version="1", tools_granted=["mpi.merge"])
    decision = evaluate_locally(
        _input("mpi.merge", "forbidden").model_copy(update={"agent": agent})
    )
    assert decision.allow is False
    assert decision.action_class == "forbidden"
    assert "action_class:forbidden" in decision.reasons


def test_tool_not_granted_is_denied() -> None:
    decision = evaluate_locally(_input("core.get_citizen_summary", "auto"))
    assert decision.allow is False
    assert "tool_not_granted" in decision.reasons


@pytest.mark.parametrize(
    "state",
    [
        KillSwitchState(global_=True),
        KillSwitchState(agents=["regulation_completeness"]),
        KillSwitchState(tools=["core.create_task"]),
        KillSwitchState(tenants=["ibge_3143302"]),
    ],
)
def test_kill_switch_denies(state: KillSwitchState) -> None:
    decision = evaluate_locally(_input("core.create_task", "auto", state))
    assert decision.allow is False
    assert any(r.startswith("kill_switch:") for r in decision.reasons)


def test_auto_and_requires_approval_classes() -> None:
    auto = evaluate_locally(_input("core.create_task", "auto"))
    assert auto.allow and auto.action_class == "auto" and not auto.requires_approval
    agent = AGENT.model_copy(update={"tools_granted": ["core.create_pending_issue"]})
    pending = evaluate_locally(
        _input("core.create_pending_issue", "requires_approval").model_copy(update={"agent": agent})
    )
    assert pending.allow and pending.requires_approval
    assert pending.action_class == "requires_approval"


@respx.mock
async def test_opa_client_parses_contract_and_caches() -> None:
    route = respx.post("http://opa:8181/v1/data/sus/agents/decision").mock(
        return_value=httpx.Response(
            200,
            json={
                "result": {
                    "allow": True,
                    "action_class": "requires_approval",
                    "requires_approval": True,
                    "reasons": ["ok"],
                }
            },
        )
    )
    client = AgentPolicyClient("http://opa:8181", cache_ttl_seconds=30)
    first = await client.decide(_input("core.create_task", "auto"))
    second = await client.decide(_input("core.create_task", "auto"))
    assert first.requires_approval and second == first
    assert route.call_count == 1  # cache curto
    sent = route.calls[0].request
    body = sent.read().decode()
    assert (
        '"kill_switch"' in body and '"tools_granted"' in body and '"action_class_requested"' in body
    )
    await client.aclose()


@respx.mock
async def test_opa_unavailable_fails_closed() -> None:
    respx.post("http://opa:8181/v1/data/sus/agents/decision").mock(return_value=httpx.Response(500))
    client = AgentPolicyClient("http://opa:8181", cache_ttl_seconds=0)
    with pytest.raises(PolicyUnavailable):
        await client.decide(_input("core.create_task", "auto"))
    respx.post("http://opa:8181/v1/data/sus/agents/decision").mock(
        return_value=httpx.Response(200, json={})
    )
    with pytest.raises(PolicyUnavailable):
        await client.decide(_input("core.create_task", "auto"))
    await client.aclose()
