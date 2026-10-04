from __future__ import annotations

from sus_nexus_ai.agents.catalog import build_fake_llm
from sus_nexus_ai.persistence.schemas import Trigger
from sus_nexus_ai.security.kill_switch import KillSwitchState
from sus_nexus_ai.service import AIService, build_service
from sus_nexus_ai.tools.core_client import InMemoryCoreClient
from tests.conftest import CASE_ID, CITIZEN_ID, REQUEST_ID, TENANT, discharge_input


async def test_regulation_completeness_queues_pending_issue_for_approval(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await service.run_agent(
        "regulation_completeness",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"request_id": REQUEST_ID},
    )
    assert run.status == "completed" and run.validation_status == "valid"
    assert run.output is not None
    assert set(run.output["missing_fields"]) == {"cid10", "document:ecg"}
    assert run.output["suggested_pending_issue"] is not None
    assert [a.tool for a in run.actions] == ["core.create_pending_issue"]
    action = run.actions[0]
    assert action.action_class == "requires_approval" and action.status == "pending_approval"
    assert core.pending_issues == []  # nada executado sem humano
    approvals = service.repository.list_approvals(status="pending")
    assert len(approvals) == 1 and approvals[0].action_id == action.id
    tools = [t["tool"] for t in run.tools_called]
    assert tools[:2] == ["core.get_regulation_request", "core.get_citizen_summary"]
    assert run.tools_called[-1]["status"] == "requires_approval"
    assert run.prompt_version == "v1" and run.rule_versions == {
        "completeness": "completeness_rules_v1"
    }
    assert run.input_ref.hash and run.input_ref.ref == REQUEST_ID


async def test_mpi_duplicate_suggestion_only_suggests(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await service.run_agent(
        "mpi_duplicate_suggestion",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"case_id": CASE_ID},
    )
    assert run.status == "completed"
    assert run.output is not None and run.output["verdict"] == "probable_same_person"
    assert run.actions == []
    assert [c[0] for c in core.calls] == ["get_merge_case"]
    # nomes dos candidatos foram pseudonimizados no contexto enviado ao LLM
    names = [c["display_name"] for c in run.minimized_context["case"]["candidates"]]
    assert all(n.startswith("[PESSOA_") for n in names)


async def test_post_discharge_creates_task_automatically(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="event", ref="evt_01J8XE01ABCDEFGHJKMNPQRSTV"),
        input_data=discharge_input(),
    )
    assert run.status == "completed"
    assert run.output is not None and run.output["risk_level"] == "high"
    assert len(run.actions) == 1
    action = run.actions[0]
    assert action.tool == "core.create_task" and action.action_class == "auto"
    assert action.status == "executed" and action.result_hash
    assert len(core.tasks) == 1
    task = core.tasks[0]
    assert task.task_type == "post_discharge_followup" and task.priority == "urgent"
    assert task.citizen_id == CITIZEN_ID
    assert task.assignee is not None and task.assignee.kind == "team"
    assert task.origin is not None and task.origin.kind == "agent"
    assert task.due_at is not None and task.due_at.day == 3  # alta 01/10 + 2 dias


async def test_post_discharge_death_creates_no_task(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data=discharge_input(discharge_type="death"),
    )
    assert run.status == "completed"
    assert run.output is not None and run.output["risk_level"] == "none"
    assert run.actions == [] and core.tasks == []


async def test_invalid_output_retries_then_discards(settings, core: InMemoryCoreClient) -> None:  # type: ignore[no-untyped-def]
    llm = build_fake_llm(scripted=["sem json", '{"verdict": 1}', "ainda não"])
    service = build_service(settings, core=core, llm=llm)
    run = await service.run_agent(
        "mpi_duplicate_suggestion",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"case_id": CASE_ID},
    )
    assert run.status == "invalid_output"
    assert run.validation_status == "invalid_output"
    assert run.validation_attempts == 3  # 1 + 2 retries
    assert run.output is None and run.actions == []
    assert len(llm.calls) == 3
    assert llm.calls[2][-1].role == "user" and "schema" in llm.calls[2][-1].content.lower()


async def test_kill_switch_blocks_run_before_any_tool(
    service: AIService, core: InMemoryCoreClient
) -> None:
    service.kill_switch.set_admin_state(KillSwitchState(agents=["post_discharge_followup"]))
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data=discharge_input(),
    )
    assert run.status == "denied"
    assert run.error and "kill switch" in run.error
    assert core.calls == [] and run.tools_called == []


async def test_tool_denied_during_context_marks_run_denied(
    service: AIService, core: InMemoryCoreClient
) -> None:
    service.kill_switch.set_admin_state(KillSwitchState(tools=["core.list_merge_case"]))
    run = await service.run_agent(
        "mpi_duplicate_suggestion",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"case_id": CASE_ID},
    )
    assert run.status == "denied"
    assert run.tools_called[0]["status"] == "denied"
    assert run.tools_called[0]["decision"]["reasons"] == ["kill_switch:tool"]
