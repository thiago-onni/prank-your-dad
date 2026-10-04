from __future__ import annotations

import pytest

from sus_nexus_ai.security.identity import FakeIdentityProvider
from sus_nexus_ai.security.kill_switch import KillSwitch, KillSwitchState
from sus_nexus_ai.security.policy import (
    AgentIdentity,
    LocalPolicyEvaluator,
    PolicyDecision,
    PolicyInput,
)
from sus_nexus_ai.tools.core_client import InMemoryCoreClient
from sus_nexus_ai.tools.core_tools import build_default_registry
from sus_nexus_ai.tools.executor import ApprovalRequired, ToolDenied, ToolExecutor
from tests.conftest import CITIZEN_ID, PII_PHONE, REQUEST_ID, TENANT

AGENT = AgentIdentity(
    id="t",
    version="1",
    tools_granted=["core.get_citizen_summary", "core.create_pending_issue", "mpi.merge"],
)


class AllowEverything:
    """Política permissiva (simula OPA mal configurado) para provar a defesa em profundidade."""

    async def decide(self, policy_input: PolicyInput) -> PolicyDecision:
        return PolicyDecision(allow=True, action_class="auto", requires_approval=False)


def _executor(
    core: InMemoryCoreClient, policy: object | None = None, ks: KillSwitch | None = None
) -> ToolExecutor:
    return ToolExecutor(
        build_default_registry(),
        policy or LocalPolicyEvaluator(),  # type: ignore[arg-type]
        ks or KillSwitch(env_var="AI_KS_UNSET"),
        FakeIdentityProvider(),
        core,
    )


async def test_forbidden_tool_is_denied_even_if_policy_allows(core: InMemoryCoreClient) -> None:
    executor = _executor(core, policy=AllowEverything())
    with pytest.raises(ToolDenied) as exc:
        await executor.call(
            agent=AGENT,
            tenant=TENANT,
            tool_name="mpi.merge",
            args={"case_id": "c", "surviving_citizen_id": CITIZEN_ID, "reason": "x" * 12},
            run_id="run_1",
        )
    assert "action_class:forbidden" in exc.value.reasons
    assert exc.value.record.status == "denied"
    assert core.calls == []


async def test_kill_switch_denies_before_policy(core: InMemoryCoreClient) -> None:
    ks = KillSwitch(env_var="AI_KS_UNSET")
    ks.set_admin_state(KillSwitchState(tools=["core.get_citizen_summary"]))
    executor = _executor(core, ks=ks)
    with pytest.raises(ToolDenied) as exc:
        await executor.call(
            agent=AGENT,
            tenant=TENANT,
            tool_name="core.get_citizen_summary",
            args={"citizen_id": CITIZEN_ID},
            run_id="run_1",
        )
    assert exc.value.reasons == ["kill_switch:tool"]


async def test_tool_not_granted_or_unknown_is_denied(core: InMemoryCoreClient) -> None:
    executor = _executor(core)
    with pytest.raises(ToolDenied, match="tool_not_granted"):
        await executor.call(
            agent=AGENT,
            tenant=TENANT,
            tool_name="core.create_task",
            args={"task_type": "x", "priority": "low", "title": "t"},
            run_id="run_1",
        )
    with pytest.raises(ToolDenied, match="tool_not_registered"):
        await executor.call(
            agent=AGENT, tenant=TENANT, tool_name="core.nope", args={}, run_id="run_1"
        )


async def test_requires_approval_needs_approver_identity(core: InMemoryCoreClient) -> None:
    executor = _executor(core)
    args = {
        "request_id": REQUEST_ID,
        "reason": "Pedido incompleto: falta cid10.",
        "missing_fields": ["cid10"],
    }
    with pytest.raises(ApprovalRequired) as exc:
        await executor.call(
            agent=AGENT,
            tenant=TENANT,
            tool_name="core.create_pending_issue",
            args=args,
            run_id="run_1",
        )
    assert exc.value.record.status == "requires_approval"
    assert core.pending_issues == []

    result = await executor.call(
        agent=AGENT,
        tenant=TENANT,
        tool_name="core.create_pending_issue",
        args=args,
        run_id="run_1",
        approved_by="user:regulador-1",
    )
    assert result.record.status == "executed"
    assert result.record.approved_by == "user:regulador-1"
    assert len(core.pending_issues) == 1
    # identidade do agente (client credentials fake) foi usada na chamada ao core
    assert core.calls[-1] == ("create_pending_issue", f"fake-token-t-{TENANT}", TENANT)


async def test_record_masks_args_and_hashes_result(core: InMemoryCoreClient) -> None:
    executor = _executor(core)
    result = await executor.call(
        agent=AGENT,
        tenant=TENANT,
        tool_name="core.get_citizen_summary",
        args={"citizen_id": CITIZEN_ID, "purpose": "regulation", "phone": PII_PHONE},
        run_id="run_1",
    )
    rec = result.record.to_dict()
    assert rec["status"] == "executed"
    assert rec["decision"]["allow"] is True
    assert "phone" not in rec["args_masked"] and PII_PHONE not in str(rec)
    assert rec["result_hash"] and len(rec["result_hash"]) == 64
