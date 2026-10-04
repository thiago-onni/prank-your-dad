"""Agente ``regulation_completeness`` v2: completude de pedido de regulação (contrato real).

Entrada: ``RegulationRequest`` de ``GET /regulation/requests/{id}`` (status, priority,
``justification_present``, ``attached_documents_count``, ``issues[]`` já abertas, ``kind``,
``requesting_cnes``, ``specialty``, ``requested_service_code``, ``sla_due_at``, ``waiting_days``).

Regra determinística **pré-LLM** (``completeness_rules_v2``) decide *o que* falta; o LLM só
redige a descrição de cada item e o resumo. Cada item vira uma ação ``core.create_pending_issue``
(``requires_approval``) → ao aprovar, ``POST /regulation/requests/{id}/issues``.

* ``justification_present == false`` → ``clinical_justification``;
* ``attached_documents_count == 0`` e ``kind`` ∈ ``DOCUMENTS_REQUIRED_KINDS`` →
  ``missing_document``;
* ``requesting_cnes`` ausente, ou ``specialty`` ausente em consulta → ``missing_field``;
* pendência do mesmo ``kind`` já **aberta** no pedido → não duplica (``already_open``);
* pedido em status não acionável (autorizado, negado, cancelado, realizado, expirado…) → nada.

Autonomia: sugestão + pendência com aprovação humana. Nunca altera prioridade nem decide
(ferramentas ``forbidden``). ``duplicate_suspected`` é só sinal para o regulador — não gera ação.
"""

from __future__ import annotations

import json
from typing import Any, Literal

from pydantic import BaseModel, Field

from sus_nexus_ai.agents.base import AgentDefinition, PlannedAction, ToolCaller
from sus_nexus_ai.llm.client import LLMMessage
from sus_nexus_ai.tools.core_client import IssueKind

AGENT_ID = "regulation_completeness"
VERSION = "2.0.0"
PROMPT_VERSION = "v2"
RULES_VERSION = "completeness_rules_v2"

DOCUMENTS_REQUIRED_KINDS: frozenset[str] = frozenset({"exam", "procedure", "surgery", "admission"})
ACTIONABLE_STATUSES: frozenset[str] = frozenset(
    {"requested", "pending_documents", "returned", "under_review"}
)
ACTION_KIND_ORDER: tuple[IssueKind, ...] = (
    "clinical_justification",
    "missing_document",
    "missing_field",
)
DEFAULT_DESCRIPTIONS: dict[str, str] = {
    "clinical_justification": (
        "Pedido sem justificativa clínica registrada. Solicitar à unidade solicitante que "
        "complemente a justificativa antes da análise reguladora."
    ),
    "missing_document": (
        "Pedido sem documentos anexados, obrigatórios para este tipo de solicitação. "
        "Solicitar à unidade solicitante o envio dos documentos."
    ),
    "missing_field": (
        "Pedido com campos administrativos obrigatórios ausentes ({codes}). "
        "Solicitar à unidade solicitante a complementação."
    ),
}
DuplicateSignal = Literal["none", "possible"]


class RegulationCompletenessInput(BaseModel):
    request_id: str = Field(min_length=1)
    citizen_id: str | None = None


class MissingItem(BaseModel):
    kind: IssueKind
    description: str = Field(min_length=10, max_length=1000)


class RegulationCompletenessOutput(BaseModel):
    missing_items: list[MissingItem] = Field(default_factory=list)
    summary: str = Field(min_length=1, max_length=2000)
    duplicate_suspected: bool = False
    confidence: float = Field(ge=0.0, le=1.0)


class Finding(BaseModel):
    kind: IssueKind
    code: str
    already_open: bool = False


class RuleResult(BaseModel):
    rules_version: str = RULES_VERSION
    actionable: bool
    open_issue_kinds: list[str] = Field(default_factory=list)
    findings: list[Finding] = Field(default_factory=list)
    duplicate_signal: DuplicateSignal = "none"

    def pending_kinds(self) -> list[IssueKind]:
        """Kinds a abrir (ordem fixa, sem repetição, sem os já abertos)."""
        wanted = {f.kind for f in self.findings if not f.already_open}
        return [k for k in ACTION_KIND_ORDER if k in wanted]

    def codes_for(self, kind: str) -> list[str]:
        return [f.code for f in self.findings if f.kind == kind]


def evaluate_rules(request: dict[str, Any], summary: dict[str, Any] | None = None) -> RuleResult:
    """Regra determinística de referência (``completeness_rules_v2``)."""
    status = str(request.get("status") or "")
    actionable = status in ACTIONABLE_STATUSES
    open_kinds = sorted(
        {
            str(i.get("kind"))
            for i in request.get("issues") or []
            if isinstance(i, dict) and i.get("status", "open") == "open"
        }
    )
    result = RuleResult(actionable=actionable, open_issue_kinds=open_kinds)
    if not actionable:
        return result

    def add(kind: IssueKind, code: str) -> None:
        result.findings.append(Finding(kind=kind, code=code, already_open=kind in open_kinds))

    if request.get("justification_present") is False:
        add("clinical_justification", "justification_present=false")
    kind = str(request.get("kind") or "")
    attached = request.get("attached_documents_count")
    if attached == 0 and kind in DOCUMENTS_REQUIRED_KINDS:
        add("missing_document", "attached_documents_count=0")
    if not request.get("requesting_cnes"):
        add("missing_field", "requesting_cnes")
    if kind == "consultation" and not request.get("specialty"):
        add("missing_field", "specialty")

    if int((summary or {}).get("open_regulation_requests") or 0) >= 2:
        result.duplicate_signal = "possible"
    return result


async def build_context(tools: ToolCaller, inp: RegulationCompletenessInput) -> dict[str, Any]:
    request = await tools.call("core.get_regulation_request", request_id=inp.request_id)
    request_data = request.model_dump(mode="json")
    citizen_id = inp.citizen_id or request_data.get("citizen_id")
    summary_data: dict[str, Any] | None = None
    if citizen_id:
        summary = await tools.call(
            "core.get_citizen_summary", citizen_id=citizen_id, purpose="regulation"
        )
        summary_data = summary.model_dump(mode="json")
    rules = evaluate_rules(request_data, summary_data)
    context: dict[str, Any] = {
        "request": request_data,
        "rule_findings": rules.model_dump(mode="json"),
        "documents_required_kinds": sorted(DOCUMENTS_REQUIRED_KINDS),
    }
    if summary_data is not None:
        context["citizen_summary"] = summary_data
    return context


def plan_actions(
    output: RegulationCompletenessOutput,
    context: dict[str, Any],
    inp: RegulationCompletenessInput,
) -> list[PlannedAction]:
    rules = RuleResult.model_validate(context.get("rule_findings") or {"actionable": False})
    if not rules.actionable:
        return []
    described = {item.kind: item.description for item in output.missing_items}
    actions: list[PlannedAction] = []
    for kind in rules.pending_kinds():
        description = described.get(kind) or DEFAULT_DESCRIPTIONS[kind].format(
            codes=", ".join(rules.codes_for(kind))
        )
        actions.append(
            PlannedAction(
                tool="core.create_pending_issue",
                args={
                    "request_id": inp.request_id,
                    "kind": kind,
                    "description": description[:1000],
                },
                rationale=output.summary,
            )
        )
    return actions


def fake_responder(messages: list[LLMMessage]) -> str:
    """LLM fake determinístico: redige descrição/sumário a partir de ``rule_findings``."""
    context = _context_from_messages(messages)
    request = context.get("request", {})
    rules = RuleResult.model_validate(context.get("rule_findings") or {"actionable": False})
    items = [
        {
            "kind": kind,
            "description": DEFAULT_DESCRIPTIONS[kind].format(
                codes=", ".join(rules.codes_for(kind))
            ),
        }
        for kind in rules.pending_kinds()
    ]
    skipped = sorted({f.kind for f in rules.findings if f.already_open})
    specialty = request.get("specialty") or "especialidade não informada"
    summary = (
        f"Pedido {request.get('id', '?')} ({request.get('kind', '?')}): {specialty}, "
        f"serviço {request.get('requested_service_code') or '—'}, prioridade "
        f"{request.get('priority') or 'não informada'}, status {request.get('status')}, "
        f"{request.get('waiting_days', 0)} dia(s) em espera. "
    )
    if not rules.actionable:
        summary += "Pedido fora da fase de completude; nada a fazer."
    elif items:
        summary += f"Faltam {len(items)} item(ns): {', '.join(i['kind'] for i in items)}."
    else:
        summary += "Pedido completo para análise do regulador."
    if skipped:
        summary += f" Pendência(s) já aberta(s), não duplicada(s): {', '.join(skipped)}."
    return json.dumps(
        {
            "missing_items": items,
            "summary": summary,
            "duplicate_suspected": rules.duplicate_signal == "possible",
            "confidence": 0.95 if not items else 0.9,
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
        description=(
            "Verifica completude administrativa de um pedido de regulação (regra versionada) "
            "e propõe pendências para aprovação humana, sem duplicar as já abertas."
        ),
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
