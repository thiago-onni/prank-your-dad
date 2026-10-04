from __future__ import annotations

import pytest

from sus_nexus_ai.persistence.schemas import AgentRunRecord, Trigger
from sus_nexus_ai.service import AIService, ApprovalError
from sus_nexus_ai.tools.core_client import InMemoryCoreClient
from tests.conftest import REQUEST_ID, TENANT


async def _run_with_pending(service: AIService) -> AgentRunRecord:
    run = await service.run_agent(
        "regulation_completeness",
        tenant=TENANT,
        trigger=Trigger(kind="user", ref="user:regulador-1"),
        input_data={"request_id": REQUEST_ID},
    )
    assert run.actions[0].status == "pending_approval"
    return run


async def test_approve_executes_tool_with_approver_identity(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await _run_with_pending(service)
    action_id = run.actions[0].id
    updated = await service.approve_action(
        run.id,
        action_id,
        approver="user:regulador-1",
        justification="Pendência procede: falta CID e ECG.",
    )
    action = updated.action(action_id)
    assert action is not None
    assert action.status == "approved" and action.approver == "user:regulador-1"
    assert action.justification and action.decided_at is not None and action.result_hash
    assert len(core.pending_issues) == 1
    assert core.pending_issues[0].request_id == REQUEST_ID
    assert "cid10" in core.pending_issues[0].missing_fields
    approval = service.repository.get_approval(run.id, action_id)
    assert approval is not None and approval.status == "approved"
    assert approval.approver == "user:regulador-1"
    persisted = service.repository.get_run(run.id)
    assert persisted is not None and persisted.tools_called[-1]["approved_by"] == "user:regulador-1"


async def test_reject_records_justification_and_executes_nothing(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await _run_with_pending(service)
    action_id = run.actions[0].id
    updated = await service.reject_action(
        run.id,
        action_id,
        approver="user:regulador-2",
        justification="Documentos chegaram por outro canal.",
    )
    action = updated.action(action_id)
    assert action is not None and action.status == "rejected"
    assert action.approver == "user:regulador-2"
    assert core.pending_issues == []
    approval = service.repository.get_approval(run.id, action_id)
    assert approval is not None and approval.status == "rejected"


async def test_decision_is_final_and_requires_justification(service: AIService) -> None:
    run = await _run_with_pending(service)
    action_id = run.actions[0].id
    with pytest.raises(ApprovalError) as exc:
        await service.approve_action(run.id, action_id, approver="u", justification="   ")
    assert exc.value.status == 422
    await service.reject_action(run.id, action_id, approver="u", justification="Não procede.")
    with pytest.raises(ApprovalError) as exc:
        await service.approve_action(
            run.id, action_id, approver="u", justification="Mudei de ideia."
        )
    assert exc.value.status == 409
    with pytest.raises(ApprovalError) as exc:
        await service.approve_action("run_nope", action_id, approver="u", justification="x" * 10)
    assert exc.value.status == 404


async def test_kill_switch_at_approval_time_denies_execution(
    service: AIService, core: InMemoryCoreClient
) -> None:
    from sus_nexus_ai.security.kill_switch import KillSwitchState

    run = await _run_with_pending(service)
    action_id = run.actions[0].id
    service.kill_switch.set_admin_state(KillSwitchState(tools=["core.create_pending_issue"]))
    updated = await service.approve_action(
        run.id, action_id, approver="user:regulador-1", justification="Aprovo a pendência."
    )
    action = updated.action(action_id)
    assert action is not None and action.status == "denied"
    assert action.reasons == ["kill_switch:tool"]
    assert core.pending_issues == []
