from __future__ import annotations

from sus_nexus_ai.agents.catalog import build_fake_llm
from sus_nexus_ai.config import Settings
from sus_nexus_ai.persistence.schemas import Trigger
from sus_nexus_ai.security.kill_switch import KillSwitchState
from sus_nexus_ai.service import AIService, build_service
from sus_nexus_ai.tools.core_client import InMemoryCoreClient
from tests.conftest import (
    CASE_ID,
    CITIZEN_ID,
    DIAGNOSIS_CID,
    EPISODE_ID,
    EPISODE_ID_TRACKED,
    EXAM_ORDER_ID,
    REQUEST_ID,
    REQUESTING_CNES,
    TEAM_INE,
    TENANT,
    discharge_input,
    exam_order_fixture,
    regulation_request_fixture,
)


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
    assert [i["kind"] for i in run.output["missing_items"]] == ["clinical_justification"]
    assert run.output["duplicate_suspected"] is False
    assert [a.tool for a in run.actions] == ["core.create_pending_issue"]
    action = run.actions[0]
    assert action.action_class == "requires_approval" and action.status == "pending_approval"
    assert action.args["kind"] == "clinical_justification"
    assert action.args["request_id"] == REQUEST_ID and len(action.args["description"]) >= 10
    assert core.issues == []  # nada executado sem humano
    approvals = service.repository.list_approvals(status="pending")
    assert len(approvals) == 1 and approvals[0].action_id == action.id
    tools = [t["tool"] for t in run.tools_called]
    assert tools[:2] == ["core.get_regulation_request", "core.get_citizen_summary"]
    assert run.tools_called[-1]["status"] == "requires_approval"
    assert run.agent_version == "2.0.0" and run.prompt_version == "v2"
    assert run.rule_versions == {"completeness": "completeness_rules_v2"}
    assert run.input_ref.hash and run.input_ref.ref == REQUEST_ID
    findings = run.minimized_context["rule_findings"]
    assert findings["actionable"] is True and findings["findings"][0]["already_open"] is False


async def test_regulation_completeness_does_not_duplicate_open_issue(
    service: AIService, core: InMemoryCoreClient
) -> None:
    core.regulation_requests[REQUEST_ID] = regulation_request_fixture(
        kind="exam",
        status="pending_documents",
        attached_documents_count=0,
        issues=[
            {
                "id": "iss_01J8XI01ABCDEFGHJKMNPQRSTV",
                "kind": "clinical_justification",
                "status": "open",
                "origin": {"kind": "user", "id": "user:regulador-1"},
                "created_at": "2026-10-01T09:00:00-03:00",
            }
        ],
    )
    run = await service.run_agent(
        "regulation_completeness",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"request_id": REQUEST_ID},
    )
    assert run.status == "completed" and run.output is not None
    # justificativa já tem pendência aberta → só documento é proposto
    assert [i["kind"] for i in run.output["missing_items"]] == ["missing_document"]
    assert [a.args["kind"] for a in run.actions] == ["missing_document"]
    findings = {
        f["kind"]: f["already_open"] for f in run.minimized_context["rule_findings"]["findings"]
    }
    assert findings == {"clinical_justification": True, "missing_document": False}


async def test_regulation_completeness_terminal_status_plans_nothing(
    service: AIService, core: InMemoryCoreClient
) -> None:
    core.regulation_requests[REQUEST_ID] = regulation_request_fixture(status="authorized")
    run = await service.run_agent(
        "regulation_completeness",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"request_id": REQUEST_ID},
    )
    assert run.status == "completed" and run.actions == []
    assert run.output is not None and run.output["missing_items"] == []


async def test_exam_critical_result_creates_followup_task_without_clinical_content(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await service.run_agent(
        "exam_critical_result",
        tenant=TENANT,
        trigger=Trigger(kind="event", ref="evt_01J8XE05ABCDEFGHJKMNPQRSTV"),
        input_data={"order_id": EXAM_ORDER_ID, "result_id": "exr_01J8XY01ABCDEFGHJKMNPQRSTV"},
    )
    assert run.status == "completed" and run.output is not None
    assert run.output["urgency"] == "urgent" and run.output["create_task"] is True
    assert run.rule_versions == {"urgency": "exam_critical_rule_v1"}
    assert [t["tool"] for t in run.tools_called][:2] == [
        "core.get_exam_order",
        "core.get_citizen_summary",
    ]
    assert len(run.actions) == 1 and run.actions[0].status == "executed"
    assert run.actions[0].action_class == "auto"
    task = core.tasks[0]
    assert task.task_type == "exam_result_followup" and task.priority == "urgent"
    assert task.citizen_id == CITIZEN_ID
    assert task.assignee is not None and task.assignee.kind == "health_unit"
    assert task.assignee.id == REQUESTING_CNES
    assert task.origin is not None and task.origin.kind == "agent"
    assert task.origin.id == "exam_critical_result" and task.origin.version == "1.0.0"
    assert task.due_at is not None and task.due_at.day == 2  # laudo 01/10 13:00Z + 24 h
    # EXA-008: nada clínico no título/descrição
    blob = f"{task.title} {task.description}"
    assert "0202010473" not in blob and "potássio" not in blob.lower()
    # o LLM também não recebe código/descrição do exame
    assert "exam_code" not in run.minimized_context["order"]
    assert "0202010473" not in str(run.minimized_context)


async def test_exam_critical_result_skips_when_core_already_created_task(
    service: AIService, core: InMemoryCoreClient
) -> None:
    order = exam_order_fixture()
    order.results[0].followup_task_id = "task_01J8XT01ABCDEFGHJKMNPQRSTV"
    core.exam_orders[EXAM_ORDER_ID] = order
    run = await service.run_agent(
        "exam_critical_result",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"order_id": EXAM_ORDER_ID},
    )
    assert run.status == "completed" and run.output is not None
    assert run.output["urgency"] == "tracked" and run.actions == [] and core.tasks == []


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
    assert run.agent_version == "2.0.0"
    assert run.minimized_context["heuristic_reference"]["verdict"] == "probable_same_person"
    # nomes dos candidatos foram pseudonimizados no contexto enviado ao LLM
    names = [c["display_name"] for c in run.minimized_context["case"]["candidates"]]
    assert all(n.startswith("[PESSOA_") for n in names)


async def test_post_discharge_does_not_duplicate_core_task(
    service: AIService, core: InMemoryCoreClient
) -> None:
    """Core já criou a tarefa na alta → só resumo operacional, sem ação nem nova tarefa."""
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="event", ref="evt_01J8XE01ABCDEFGHJKMNPQRSTV"),
        input_data=discharge_input(EPISODE_ID_TRACKED),
    )
    assert run.status == "completed" and run.agent_version == "2.0.0"
    assert run.prompt_version == "v2"
    assert run.rule_versions == {"attention": "post_discharge_attention_v2"}
    assert run.output is not None and run.output["mode"] == "summary_only"
    assert run.actions == [] and core.tasks == []
    assert [p["code"] for p in run.output["attention_points"]] == [
        "readmission_30d",
        "long_stay",
        "no_valid_contact",
        "open_care_gaps",
    ]
    assert "hospital_risk_v1" in run.output["summary"]
    script = run.output["suggested_contact_script"]
    assert script and DIAGNOSIS_CID not in script and "Hospital" not in script
    # o risco vem do core: o agente não recalcula
    assert run.minimized_context["assessment"]["risk_level"] == "high"
    assert [c[0] for c in core.calls] == [
        "get_hospital_episode",
        "get_citizen_summary",
        "list_care_gaps",
    ]


async def test_post_discharge_context_has_no_clinical_content(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data=discharge_input(EPISODE_ID_TRACKED, care_lines=["insuficiencia_cardiaca"]),
    )
    import json

    blob = json.dumps(run.minimized_context, ensure_ascii=False) + json.dumps(run.output)
    for forbidden in (
        DIAGNOSIS_CID,
        "Hospital Regional",
        "3126100012345",
        "12B",
        "insuficiencia_cardiaca",
        "hipertensao",
        "Maria",
    ):
        assert forbidden not in blob, forbidden
    assert run.minimized_context["episode"]["care_lines_count"] == 1
    assert run.output is not None
    assert [p["code"] for p in run.output["attention_points"]][-1] == "active_care_lines"


async def test_post_discharge_fallback_creates_task_when_core_did_not(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="event", ref="evt_01J8XE01ABCDEFGHJKMNPQRSTV"),
        input_data=discharge_input(),
    )
    assert run.status == "completed"
    assert run.output is not None and run.output["mode"] == "fallback_task"
    assert len(run.actions) == 1
    action = run.actions[0]
    assert action.tool == "core.create_task" and action.action_class == "auto"
    assert action.status == "executed" and action.result_hash
    assert len(core.tasks) == 1
    task = core.tasks[0]
    assert task.task_type == "post_discharge_followup" and task.priority == "urgent"
    assert task.citizen_id == CITIZEN_ID
    assert task.assignee is not None and task.assignee.kind == "team"
    assert task.assignee.id == TEAM_INE  # INE de 10 dígitos não é telefone: chega intacto
    assert task.origin is not None and task.origin.kind == "agent"
    assert task.origin.version == "2.0.0"
    assert task.due_at is not None and task.due_at.day == 3  # alta 01/10 + 2 dias (risco high)
    assert DIAGNOSIS_CID not in f"{task.title} {task.description}"
    assert "hospital_risk_v1" in (task.description or "")


async def test_post_discharge_death_creates_no_action(
    service: AIService, core: InMemoryCoreClient
) -> None:
    core.hospital_episodes[EPISODE_ID] = core.hospital_episodes[EPISODE_ID].model_copy(
        update={"disposition": "deceased", "status": "deceased"}
    )
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data=discharge_input(),
    )
    assert run.status == "completed"
    assert run.output is not None and run.output["mode"] == "no_action_deceased"
    assert run.output["suggested_contact_script"] == ""
    assert run.output["attention_points"] == []
    assert run.actions == [] and core.tasks == []


async def test_post_discharge_rejects_cid_in_llm_output(
    settings: Settings, core: InMemoryCoreClient
) -> None:
    import json

    leaked = json.dumps(
        {"mode": "summary_only", "summary": "Alta por I50.0, acompanhar.", "attention_points": []}
    )
    llm = build_fake_llm(scripted=[leaked, leaked, leaked])
    service = build_service(settings, core=core, llm=llm)
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data=discharge_input(EPISODE_ID_TRACKED),
    )
    assert run.status == "invalid_output" and run.actions == [] and core.tasks == []


async def test_post_discharge_unknown_episode_fails_without_actions(
    service: AIService, core: InMemoryCoreClient
) -> None:
    run = await service.run_agent(
        "post_discharge_followup",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data=discharge_input("hep_01J8XH99ABCDEFGHJKMNPQRSTV"),
    )
    assert run.status == "failed" and run.actions == [] and core.tasks == []


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
    service.kill_switch.set_admin_state(KillSwitchState(tools=["core.get_merge_case"]))
    run = await service.run_agent(
        "mpi_duplicate_suggestion",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"case_id": CASE_ID},
    )
    assert run.status == "denied"
    assert run.tools_called[0]["status"] == "denied"
    assert run.tools_called[0]["decision"]["reasons"] == ["kill_switch:tool"]
