"""Agente ``regulation_completeness``: completude e resumo de pedido de regulação (F2).

Autonomia: sugestão + pendência com aprovação humana (``core.create_pending_issue`` é
``requires_approval``). Nunca altera prioridade nem decide (ferramentas ``forbidden``).
"""

from __future__ import annotations

import json
from typing import Any, Literal

from pydantic import BaseModel, Field

from sus_nexus_ai.agents.base import AgentDefinition, PlannedAction, ToolCaller
from sus_nexus_ai.llm.client import LLMMessage

AGENT_ID = "regulation_completeness"
VERSION = "1.0.0"
PROMPT_VERSION = "v1"
RULES_VERSION = "completeness_rules_v1"

REQUIRED_FIELDS: tuple[str, ...] = (
    "specialty",
    "procedure_code",
    "clinical_justification",
    "cid10",
    "requesting_unit_cnes",
    "requesting_professional_cbo",
)
MIN_JUSTIFICATION_CHARS = 20


class RegulationCompletenessInput(BaseModel):
    request_id: str
    citizen_id: str | None = None


class SuggestedPendingIssue(BaseModel):
    reason: str = Field(min_length=10, max_length=1000)
    missing_fields: list[str] = Field(default_factory=list)
    return_to: Literal["requesting_unit", "citizen"] = "requesting_unit"


class RegulationCompletenessOutput(BaseModel):
    missing_fields: list[str] = Field(default_factory=list)
    summary: str = Field(min_length=1, max_length=2000)
    suggested_pending_issue: SuggestedPendingIssue | None = None
    confidence: float = Field(ge=0.0, le=1.0)


async def build_context(tools: ToolCaller, inp: RegulationCompletenessInput) -> dict[str, Any]:
    request = await tools.call("core.get_regulation_request", request_id=inp.request_id)
    request_data = request.model_dump(mode="json")
    citizen_id = inp.citizen_id or request_data.get("citizen_id")
    context: dict[str, Any] = {
        "request": request_data,
        "required_fields": list(REQUIRED_FIELDS),
        "min_justification_chars": MIN_JUSTIFICATION_CHARS,
        "rules_version": RULES_VERSION,
    }
    if citizen_id:
        summary = await tools.call(
            "core.get_citizen_summary", citizen_id=citizen_id, purpose="regulation"
        )
        context["citizen_summary"] = summary.model_dump(mode="json")
    return context


def plan_actions(
    output: RegulationCompletenessOutput,
    context: dict[str, Any],
    inp: RegulationCompletenessInput,
) -> list[PlannedAction]:
    if output.suggested_pending_issue is None or not output.missing_fields:
        return []
    issue = output.suggested_pending_issue
    return [
        PlannedAction(
            tool="core.create_pending_issue",
            args={
                "request_id": inp.request_id,
                "reason": issue.reason,
                "missing_fields": sorted(set(issue.missing_fields) | set(output.missing_fields)),
                "return_to": issue.return_to,
            },
            rationale=output.summary,
        )
    ]


def compute_missing_fields(request: dict[str, Any]) -> list[str]:
    """Regra determinística de referência (``completeness_rules_v1``)."""
    missing: list[str] = []
    for field_name in REQUIRED_FIELDS:
        value = request.get(field_name)
        if value in (None, "", []) or (
            field_name == "clinical_justification"
            and (not isinstance(value, str) or len(value.strip()) < MIN_JUSTIFICATION_CHARS)
        ):
            missing.append(field_name)
    attachments = {str(a).lower() for a in request.get("attachments") or []}
    for doc in request.get("required_documents") or []:
        if str(doc).lower() not in attachments:
            missing.append(f"document:{doc}")
    return missing


def fake_responder(messages: list[LLMMessage]) -> str:
    """LLM fake determinístico: aplica a regra de referência ao contexto minimizado."""
    context = _context_from_messages(messages)
    request = context.get("request", {})
    missing = compute_missing_fields(request)
    specialty = request.get("specialty") or "especialidade não informada"
    procedure = request.get("procedure_description") or request.get("procedure_code") or "—"
    summary = (
        f"Pedido {request.get('id', '?')}: {specialty}, procedimento {procedure}, "
        f"prioridade solicitada {request.get('priority_requested') or 'não informada'}. "
        + (
            f"Faltam {len(missing)} item(ns): {', '.join(missing)}."
            if missing
            else "Pedido completo para análise do regulador."
        )
    )
    issue: dict[str, Any] | None = None
    if missing:
        issue = {
            "reason": "Pedido incompleto: " + ", ".join(missing) + ". Devolver à unidade.",
            "missing_fields": missing,
            "return_to": "requesting_unit",
        }
    return json.dumps(
        {
            "missing_fields": missing,
            "summary": summary,
            "suggested_pending_issue": issue,
            "confidence": 0.95 if not missing else 0.9,
        },
        ensure_ascii=False,
    )


def _context_from_messages(messages: list[LLMMessage]) -> dict[str, Any]:
    for m in messages:
        if m.role == "user" and "<dados>" in m.content:
            raw = m.content.split("<dados>", 1)[1].split("</dados>", 1)[0]
            data = json.loads(raw)
            return data if isinstance(data, dict) else {}
    return {}


def definition() -> AgentDefinition[RegulationCompletenessInput, RegulationCompletenessOutput]:
    return AgentDefinition(
        id=AGENT_ID,
        version=VERSION,
        prompt_version=PROMPT_VERSION,
        description="Verifica campos/documentos faltantes num pedido de regulação e o resume.",
        input_model=RegulationCompletenessInput,
        output_model=RegulationCompletenessOutput,
        tools=[
            "core.get_regulation_request",
            "core.get_citizen_summary",
            "core.create_pending_issue",
        ],
        build_context=build_context,
        plan_actions=plan_actions,
        rule_versions={"completeness": RULES_VERSION},
    )
