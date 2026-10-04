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
    assert len(core.issues) == 1
    issue = core.issues[0]
    assert issue.request_id == REQUEST_ID and issue.kind == "clinical_justification"
    assert issue.origin is not None and issue.origin.kind == "agent"
    assert issue.origin.id == "regulation_completeness" and issue.origin.version == "2.0.0"
    # o core registrou a pendência já aberta no pedido
    assert core.regulation_requests[REQUEST_ID].open_issue_kinds() == {"clinical_justification"}
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
    assert core.issues == [] and "add_regulation_issue" not in [c[0] for c in core.calls]
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
    assert core.issues == []


async def test_second_run_after_approval_does_not_duplicate_issue(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await _run_with_pending(service)
    await service.approve_action(
        run.id, run.actions[0].id, approver="user:regulador-1", justification="Aprovo a pendência."
    )
    assert len(core.issues) == 1
    # evento "updated" chega depois: a pendência já está aberta no pedido → nada novo
    again = await service.run_agent(
        "regulation_completeness",
        tenant=TENANT,
        trigger=Trigger(kind="event", ref="evt_01J8XE09ABCDEFGHJKMNPQRSTV"),
        input_data={"request_id": REQUEST_ID},
    )
    assert again.status == "completed" and again.actions == []
    assert len(core.issues) == 1
