"""Agente ``post_discharge_followup`` v2: pós-alta hospitalar sobre o episódio real do core.

Desde a Fase 3 o **core** já faz, na alta (``POST /hospital/episodes/{id}/discharge``):

* classificação de risco por regra aprovada e versionada (``risk_level`` + ``risk_rule_version``,
  HOS-005 — nunca por LLM);
* criação da tarefa ``post_discharge_followup`` (``followup.task_id``).

Por isso o agente **não recalcula risco e não cria tarefa por conta própria**. Ele lê o episódio
(``core.get_hospital_episode``), o resumo operacional (``core.get_citizen_summary``) e as lacunas
abertas (``core.list_care_gaps``) e aplica a regra determinística ``post_discharge_attention_v2``:

==========================  ===================================================================
modo                        quando / efeito
==========================  ===================================================================
``no_action_deceased``      ``disposition``/``status`` = óbito → nenhuma ação, nenhum roteiro.
``no_action_not_discharged`` episódio sem alta concluída (internado, transferido, cancelado).
``summary_only``            ``followup.task_id`` existe → só **resumo operacional** (sem ação).
``fallback_task``           alta sem ``followup.task_id`` → ``core.create_task`` (``auto``) com
                            prioridade derivada do ``risk_level`` **do core**.
==========================  ===================================================================

Pontos de atenção (derivados de regra, não do LLM): ``readmission_30d``, ``long_stay``
(LOS ≥ ``LONG_STAY_DAYS``), ``no_valid_contact``, ``open_care_gaps``, ``active_care_lines``.

Privacidade: o CID, o nome do hospital, AIH, leito e os nomes das linhas de cuidado **não** entram
no contexto do LLM nem na tarefa; o roteiro de contato sugerido é genérico (sem dado clínico) e a
saída é rejeitada (e re-solicitada) se contiver algo com formato de código CID-10.
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timedelta
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator

from sus_nexus_ai.agents.base import AgentDefinition, PlannedAction, ToolCaller
from sus_nexus_ai.llm.client import LLMMessage

AGENT_ID = "post_discharge_followup"
VERSION = "2.0.0"
PROMPT_VERSION = "v2"
RULES_VERSION = "post_discharge_attention_v2"

TASK_TYPE = "post_discharge_followup"
LONG_STAY_DAYS = 7
DUE_DAYS: dict[str, int] = {"high": 2, "medium": 5, "low": 10}
PRIORITY: dict[str, str] = {"high": "urgent", "medium": "high", "low": "medium"}
DEFAULT_RISK_FOR_FALLBACK = "medium"  # core sem risk_level: prazo/prioridade intermediários
FALLBACK_QUEUE = "aps_post_discharge"
DISCHARGED_STATUSES: frozenset[str] = frozenset({"discharged"})

Mode = Literal["no_action_deceased", "no_action_not_discharged", "summary_only", "fallback_task"]
AttentionCode = Literal[
    "readmission_30d", "long_stay", "no_valid_contact", "open_care_gaps", "active_care_lines"
]
ATTENTION_NOTES: dict[str, str] = {
    "readmission_30d": "Reinternação em até 30 dias da alta anterior: priorizar o contato.",
    "long_stay": f"Internação longa (≥ {LONG_STAY_DAYS} dias): avaliar necessidade de visita.",
    "no_valid_contact": "Sem contato válido no cadastro: atualizar contato (ACS/visita).",
    "open_care_gaps": "Há lacunas de cuidado abertas para o cidadão na lista de busca ativa.",
    "active_care_lines": "Cidadão vinculado a linha(s) de cuidado: conferir o plano de cuidado.",
}
ATTENTION_ORDER: tuple[AttentionCode, ...] = (
    "readmission_30d",
    "long_stay",
    "no_valid_contact",
    "open_care_gaps",
    "active_care_lines",
)
GENERIC_CONTACT_SCRIPT = (
    "Olá, aqui é da equipe da sua Unidade Básica de Saúde. Soubemos que você recebeu alta "
    "hospitalar recentemente e gostaríamos de saber como você está e combinar um acompanhamento "
    "com a equipe. Qual o melhor dia e horário para conversarmos?"
)

# Formato de código CID-10 (ex.: I50, I50.0, E11.9). Defesa em profundidade: o CID nunca entra no
# contexto, mas a saída do LLM é recusada se contiver algo com esse formato.
_CID_RE = re.compile(r"(?<![0-9A-Za-z_])[A-TV-Z][0-9]{2}(?:\.[0-9A-Za-z]{1,2})?(?![0-9A-Za-z])")


class PostDischargeInput(BaseModel):
    hospital_episode_id: str = Field(pattern=r"^hep_[0-9A-Za-z]+$")
    event_id: str | None = None
    citizen_id: str | None = None
    care_lines: list[str] = Field(
        default_factory=list,
        description="Linhas de cuidado do evento de alta (só a contagem chega ao LLM).",
    )


class AttentionPoint(BaseModel):
    code: AttentionCode
    note: str = Field(min_length=1, max_length=300)


class PostDischargeOutput(BaseModel):
    """Saída **informativa** para a equipe — não gera ação além do fallback de tarefa."""

    mode: Mode
    summary: str = Field(min_length=1, max_length=1500)
    suggested_contact_script: str = Field(default="", max_length=800)
    attention_points: list[AttentionPoint] = Field(default_factory=list)

    @field_validator("summary", "suggested_contact_script")
    @classmethod
    def _no_cid(cls, value: str) -> str:
        if _CID_RE.search(value):
            raise ValueError("texto não pode conter código CID-10 nem conteúdo clínico")
        return value


class AttentionAssessment(BaseModel):
    rules_version: str = RULES_VERSION
    mode: Mode
    attention_codes: list[AttentionCode] = Field(default_factory=list)
    risk_level: str | None = None
    risk_rule_version: str | None = None
    core_task_id: str | None = None
    priority: str | None = None
    due_at: str | None = None


def _parse_dt(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def assess(
    episode: dict[str, Any],
    summary: dict[str, Any],
    open_gaps: list[dict[str, Any]],
    care_lines: list[str],
) -> AttentionAssessment:
    """Regra ``post_discharge_attention_v2`` — determinística, auditável, sem LLM."""
    status = str(episode.get("status") or "")
    disposition = str(episode.get("disposition") or "")
    followup = episode.get("followup") or {}
    base: dict[str, Any] = {
        "risk_level": episode.get("risk_level"),
        "risk_rule_version": episode.get("risk_rule_version"),
        "core_task_id": followup.get("task_id"),
    }
    if status == "deceased" or disposition == "deceased":
        return AttentionAssessment(mode="no_action_deceased", **base)
    if status not in DISCHARGED_STATUSES or not episode.get("discharged_at"):
        return AttentionAssessment(mode="no_action_not_discharged", **base)

    codes: list[AttentionCode] = []
    if episode.get("readmission_within_30d"):
        codes.append("readmission_30d")
    if int(episode.get("length_of_stay_days") or 0) >= LONG_STAY_DAYS:
        codes.append("long_stay")
    if summary.get("contact_valid") is False or any(
        g.get("contact_valid") is False for g in open_gaps
    ):
        codes.append("no_valid_contact")
    if open_gaps:
        codes.append("open_care_gaps")
    if care_lines:
        codes.append("active_care_lines")
    codes = [c for c in ATTENTION_ORDER if c in codes]

    if followup.get("task_id"):
        return AttentionAssessment(mode="summary_only", attention_codes=codes, **base)

    level = str(episode.get("risk_level") or DEFAULT_RISK_FOR_FALLBACK)
    due = _parse_dt(followup.get("due_at"))
    if due is None:
        discharged = _parse_dt(episode.get("discharged_at"))
        due = discharged + timedelta(days=DUE_DAYS.get(level, 5)) if discharged else None
    return AttentionAssessment(
        mode="fallback_task",
        attention_codes=codes,
        priority=PRIORITY.get(level, "high"),
        due_at=due.isoformat() if due else None,
        **base,
    )


def resolve_assignee(episode: dict[str, Any], summary: dict[str, Any]) -> dict[str, str]:
    if episode.get("reference_team_ine"):
        return {"kind": "team", "id": str(episode["reference_team_ine"])}
    if episode.get("reference_health_unit_cnes"):
        return {"kind": "health_unit", "id": str(episode["reference_health_unit_cnes"])}
    if summary.get("team_ine"):
        return {"kind": "team", "id": str(summary["team_ine"])}
    if summary.get("health_unit_cnes"):
        return {"kind": "health_unit", "id": str(summary["health_unit_cnes"])}
    return {"kind": "queue", "id": FALLBACK_QUEUE}


async def build_context(tools: ToolCaller, inp: PostDischargeInput) -> dict[str, Any]:
    episode_model = await tools.call(
        "core.get_hospital_episode", episode_id=inp.hospital_episode_id
    )
    episode = episode_model.model_dump(mode="json")
    citizen_id = str(episode["citizen_id"])
    summary_model = await tools.call(
        "core.get_citizen_summary", citizen_id=citizen_id, purpose="care_coordination"
    )
    summary = summary_model.model_dump(mode="json")
    gap_filters: dict[str, Any] = {"citizen_id": citizen_id, "status": "open", "limit": 200}
    if episode.get("reference_team_ine"):
        gap_filters["team_ine"] = episode["reference_team_ine"]
    elif episode.get("reference_health_unit_cnes"):
        gap_filters["cnes"] = episode["reference_health_unit_cnes"]
    gaps_page = await tools.call("core.list_care_gaps", **gap_filters)
    open_gaps = [g for g in gaps_page.model_dump(mode="json").get("items", [])]
    raw_lines = episode.get("care_lines")
    care_lines = [str(x) for x in raw_lines] if isinstance(raw_lines, list) else []
    care_lines = care_lines or list(inp.care_lines)

    assessment = assess(episode, summary, open_gaps, care_lines)
    followup = episode.get("followup") or {}
    # Metadados mínimos para o LLM: sem CID, nome do hospital, AIH, leito ou nomes de linhas.
    episode_meta = {
        "id": episode["id"],
        "status": episode.get("status"),
        "episode_class": episode.get("episode_class"),
        "disposition": episode.get("disposition"),
        "admitted_at": episode.get("admitted_at"),
        "discharged_at": episode.get("discharged_at"),
        "length_of_stay_days": episode.get("length_of_stay_days"),
        "readmission_within_30d": episode.get("readmission_within_30d"),
        "risk_level": episode.get("risk_level"),
        "risk_rule_version": episode.get("risk_rule_version"),
        "followup": {
            "status": followup.get("status"),
            "task_id": followup.get("task_id"),
            "due_at": followup.get("due_at"),
        },
        "counter_referral_received": bool(
            (episode.get("counter_referral") or {}).get("received_at")
        ),
        "care_lines_count": len(care_lines),
    }
    summary_meta = {
        "contact_valid": summary.get("contact_valid"),
        "open_tasks": summary.get("open_tasks"),
        "next_appointment_at": summary.get("next_appointment_at"),
        "last_aps_encounter_at": summary.get("last_aps_encounter_at"),
    }
    gap_kinds: dict[str, int] = {}
    for g in open_gaps:
        kind = str(g.get("gap_kind"))
        gap_kinds[kind] = gap_kinds.get(kind, 0) + 1
    return {
        "episode": episode_meta,
        "citizen_summary": summary_meta,
        "open_care_gaps": {"total": len(open_gaps), "by_kind": gap_kinds},
        "assessment": assessment.model_dump(mode="json"),
        "attention_notes": {c: ATTENTION_NOTES[c] for c in assessment.attention_codes},
        "assignee": resolve_assignee(episode, summary),
        "citizen_id": citizen_id,
    }


def plan_actions(
    output: PostDischargeOutput, context: dict[str, Any], inp: PostDischargeInput
) -> list[PlannedAction]:
    # A regra é a fonte da verdade (não o LLM): só o fallback gera ação.
    assessment = AttentionAssessment.model_validate(context.get("assessment") or {})
    if assessment.mode != "fallback_task":
        return []
    level = assessment.risk_level or DEFAULT_RISK_FOR_FALLBACK
    rule = assessment.risk_rule_version or "sem regra de risco no core"
    points = ", ".join(assessment.attention_codes) or "nenhum"
    description = (
        f"Episódio {inp.hospital_episode_id}: alta sem tarefa de seguimento registrada pelo core "
        f"(fallback {assessment.rules_version}). Risco {level} ({rule}). Pontos de atenção: "
        f"{points}. Contatar o cidadão, agendar acompanhamento na APS e registrar o seguimento."
    )
    args: dict[str, Any] = {
        "task_type": TASK_TYPE,
        "priority": assessment.priority or "high",
        "title": f"Acompanhamento pós-alta (risco {level})",
        "description": description[:2000],
        "citizen_id": context.get("citizen_id"),
        "assignee": context.get("assignee"),
    }
    if assessment.due_at:
        args["due_at"] = assessment.due_at
    return [PlannedAction(tool="core.create_task", args=args, rationale=output.summary)]


def fake_responder(messages: list[LLMMessage]) -> str:
    context = _context_from_messages(messages)
    assessment = context.get("assessment", {})
    episode = context.get("episode", {})
    mode = assessment.get("mode", "no_action_not_discharged")
    codes = assessment.get("attention_codes", [])
    notes = context.get("attention_notes", {})
    if mode == "no_action_deceased":
        summary, script = "Óbito registrado no episódio; não há seguimento pós-alta.", ""
    elif mode == "no_action_not_discharged":
        summary, script = "Episódio sem alta concluída; nenhum seguimento pós-alta agora.", ""
    else:
        risk = assessment.get("risk_level") or "não informado"
        rule = assessment.get("risk_rule_version") or "sem versão"
        los = episode.get("length_of_stay_days")
        task_line = (
            "Tarefa de seguimento já criada pelo core na alta "
            f"(status {(episode.get('followup') or {}).get('status') or 'pendente'})."
            if mode == "summary_only"
            else "O core não registrou tarefa de seguimento: tarefa criada como fallback."
        )
        summary = (
            f"Alta hospitalar após {los if los is not None else '?'} dia(s) de internação; "
            f"risco {risk} pela regra {rule} do core. {task_line} "
            f"Pontos de atenção: {len(codes)}."
        )
        script = GENERIC_CONTACT_SCRIPT
    return json.dumps(
        {
            "mode": mode,
            "summary": summary,
            "suggested_contact_script": script,
            "attention_points": [
                {"code": c, "note": notes.get(c, ATTENTION_NOTES.get(c, c))} for c in codes
            ],
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


def definition() -> AgentDefinition[PostDischargeInput, PostDischargeOutput]:
    return AgentDefinition(
        id=AGENT_ID,
        version=VERSION,
        prompt_version=PROMPT_VERSION,
        description=(
            "Pós-alta: lê o episódio do core (risco e tarefa já definidos na alta) e produz resumo "
            "operacional para a equipe; cria tarefa só como fallback se o core não criou."
        ),
        input_model=PostDischargeInput,
        output_model=PostDischargeOutput,
        tools=[
            "core.get_hospital_episode",
            "core.get_citizen_summary",
            "core.list_care_gaps",
            "core.create_task",
        ],
        build_context=build_context,
        plan_actions=plan_actions,
        rule_versions={"attention": RULES_VERSION},
    )
