"""Agente ``exam_critical_result``: resultado crítico de exame sem conduta (EXA-007/008).

Gatilho: ``sus.exam.result.critical_flagged``. Lê ``GET /exams/orders/{id}`` (só metadados —
nunca valores nem laudo) e ``GET /citizens/{id}/summary``; classifica a urgência pela regra
versionada ``exam_critical_rule_v1``:

* resultado crítico **sem** ``followup_task_id`` → ``urgent`` → ação ``auto``
  ``core.create_task`` (``exam_result_followup``) para a unidade/equipe solicitante;
* resultado crítico **com** ``followup_task_id`` (o core já criou a tarefa) → ``tracked``, sem ação;
* sem resultado crítico, ou pedido cancelado → ``none``.

O título e a descrição da tarefa NUNCA contêm conteúdo clínico (código/descrição do exame,
valores, observações) — EXA-008: a equipe consulta o laudo no sistema de origem.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta
from typing import Any, Literal

from pydantic import BaseModel, Field

from sus_nexus_ai.agents.base import AgentDefinition, PlannedAction, ToolCaller
from sus_nexus_ai.common import utcnow
from sus_nexus_ai.llm.client import LLMMessage

AGENT_ID = "exam_critical_result"
VERSION = "1.0.0"
PROMPT_VERSION = "v1"
RULES_VERSION = "exam_critical_rule_v1"

TASK_TYPE = "exam_result_followup"
TASK_TITLE = "Resultado de exame crítico aguardando conduta"
FOLLOWUP_HOURS = 24
FALLBACK_QUEUE = "exam_result_followup"

Urgency = Literal["urgent", "tracked", "none"]


class ExamCriticalInput(BaseModel):
    order_id: str = Field(min_length=1)
    result_id: str | None = None
    requesting_cnes: str | None = None


class ExamCriticalOutput(BaseModel):
    urgency: Urgency
    rationale: str = Field(min_length=1, max_length=1000)
    create_task: bool


class ExamAssessment(BaseModel):
    rules_version: str = RULES_VERSION
    urgency: Urgency
    critical: bool
    result_id: str | None = None
    result_status: str | None = None
    reported_at: str | None = None
    followup_task_id: str | None = None
    factors: list[str] = Field(default_factory=list)


def _select_result(order: dict[str, Any], result_id: str | None) -> dict[str, Any] | None:
    results = [r for r in order.get("results") or [] if isinstance(r, dict)]
    if not results:
        return None
    if result_id:
        for r in results:
            if r.get("id") == result_id:
                return r
    critical = [r for r in results if r.get("critical") is True]
    pool = critical or results
    return max(pool, key=lambda r: str(r.get("reported_at") or ""))


def assess(order: dict[str, Any], result_id: str | None = None) -> ExamAssessment:
    """Regra ``exam_critical_rule_v1`` — determinística e auditável."""
    result = _select_result(order, result_id)
    factors: list[str] = []
    if order.get("status") == "cancelled":
        return ExamAssessment(urgency="none", critical=False, factors=["pedido_cancelado"])
    critical = bool(result and result.get("critical") is True)
    if critical:
        factors.append("resultado_critico")
    elif "critical" in (order.get("issues") or []):
        critical = True
        factors.append("pedido_sinalizado_critico")
    followup = (
        str(result.get("followup_task_id")) if result and result.get("followup_task_id") else None
    )
    if result and result.get("status") == "cancelled":
        return ExamAssessment(
            urgency="none",
            critical=False,
            result_id=result.get("id"),
            result_status="cancelled",
            factors=["resultado_cancelado"],
        )
    if not critical:
        urgency: Urgency = "none"
        factors.append("sem_resultado_critico")
    elif followup:
        urgency = "tracked"
        factors.append("tarefa_de_seguimento_ja_existe")
    else:
        urgency = "urgent"
        factors.append("sem_tarefa_de_seguimento")
    return ExamAssessment(
        urgency=urgency,
        critical=critical,
        result_id=result.get("id") if result else None,
        result_status=result.get("status") if result else None,
        reported_at=str(result.get("reported_at"))
        if result and result.get("reported_at")
        else None,
        followup_task_id=followup,
        factors=factors,
    )


def resolve_assignee(
    order: dict[str, Any], summary: dict[str, Any], inp: ExamCriticalInput
) -> dict[str, str]:
    cnes = order.get("requesting_cnes") or inp.requesting_cnes
    if cnes:
        return {"kind": "health_unit", "id": str(cnes)}
    if summary.get("team_ine"):
        return {"kind": "team", "id": str(summary["team_ine"])}
    if summary.get("health_unit_cnes"):
        return {"kind": "health_unit", "id": str(summary["health_unit_cnes"])}
    return {"kind": "queue", "id": FALLBACK_QUEUE}


async def build_context(tools: ToolCaller, inp: ExamCriticalInput) -> dict[str, Any]:
    order_model = await tools.call("core.get_exam_order", order_id=inp.order_id)
    order = order_model.model_dump(mode="json")
    summary_model = await tools.call(
        "core.get_citizen_summary", citizen_id=order["citizen_id"], purpose="care_coordination"
    )
    summary = summary_model.model_dump(mode="json")
    assessment = assess(order, inp.result_id)
    # Metadados mínimos do pedido para o LLM: sem código/descrição do exame (EXA-008).
    order_meta = {
        "id": order["id"],
        "status": order.get("status"),
        "category": order.get("category"),
        "priority": order.get("priority"),
        "requested_at": order.get("requested_at"),
        "reported_at": order.get("reported_at"),
        "issues": order.get("issues") or [],
        "results": [
            {
                "id": r.get("id"),
                "status": r.get("status"),
                "critical": r.get("critical"),
                "reported_at": r.get("reported_at"),
                "has_document": r.get("has_document"),
                "followup_task_id": r.get("followup_task_id"),
            }
            for r in order.get("results") or []
        ],
    }
    return {
        "order": order_meta,
        "citizen_summary": summary,
        "exam_assessment": assessment.model_dump(mode="json"),
        "assignee": resolve_assignee(order, summary, inp),
        "citizen_id": order["citizen_id"],
    }


def plan_actions(
    output: ExamCriticalOutput, context: dict[str, Any], inp: ExamCriticalInput
) -> list[PlannedAction]:
    # A regra versionada é a fonte da verdade (não o LLM).
    assessment = ExamAssessment.model_validate(context.get("exam_assessment") or {})
    if assessment.urgency != "urgent" or not output.create_task:
        return []
    reported = _parse_dt(assessment.reported_at) or utcnow()
    due_at = reported + timedelta(hours=FOLLOWUP_HOURS)
    description = (
        f"Pedido de exame {inp.order_id}: resultado sinalizado como crítico pela origem e sem "
        f"tarefa de seguimento registrada ({assessment.rules_version}). Consultar o laudo no "
        "sistema de origem, contatar o cidadão e registrar a conduta."
    )
    return [
        PlannedAction(
            tool="core.create_task",
            args={
                "task_type": TASK_TYPE,
                "priority": "urgent",
                "title": TASK_TITLE,
                "description": description[:2000],
                "citizen_id": context.get("citizen_id"),
                "assignee": context.get("assignee"),
                "due_at": due_at.isoformat(),
            },
            rationale=output.rationale,
        )
    ]


def _parse_dt(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def fake_responder(messages: list[LLMMessage]) -> str:
    context = _context_from_messages(messages)
    assessment = context.get("exam_assessment", {})
    urgency = assessment.get("urgency", "none")
    factors = ", ".join(assessment.get("factors", [])) or "sem fatores"
    rationale = {
        "urgent": f"Resultado crítico sem tarefa de seguimento ({factors}); criar tarefa urgente.",
        "tracked": f"Resultado crítico já tem tarefa de seguimento ({factors}); nada a criar.",
        "none": f"Sem resultado crítico pendente ({factors}); nenhuma ação.",
    }[urgency]
    return json.dumps(
        {"urgency": urgency, "rationale": rationale, "create_task": urgency == "urgent"},
        ensure_ascii=False,
    )


def _context_from_messages(messages: list[LLMMessage]) -> dict[str, Any]:
    for m in messages:
        if m.role == "user" and "<dados>" in m.content:
            raw = m.content.split("<dados>", 1)[1].split("</dados>", 1)[0]
            data = json.loads(raw)
            return data if isinstance(data, dict) else {}
    return {}


def definition() -> AgentDefinition[ExamCriticalInput, ExamCriticalOutput]:
    return AgentDefinition(
        id=AGENT_ID,
        version=VERSION,
        prompt_version=PROMPT_VERSION,
        description=(
            "Resultado crítico de exame: classifica urgência por regra versionada e cria tarefa "
            "de seguimento (sem conteúdo clínico) se o core ainda não criou."
        ),
        input_model=ExamCriticalInput,
        output_model=ExamCriticalOutput,
        tools=["core.get_exam_order", "core.get_citizen_summary", "core.create_task"],
        build_context=build_context,
        plan_actions=plan_actions,
        rule_versions={"urgency": RULES_VERSION},
    )
