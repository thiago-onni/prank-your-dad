"""Agente ``post_discharge_followup``: pós-alta hospitalar (F3).

Classifica risco por regra simples **versionada em código** (``post_discharge_risk_v1``) e cria a
tarefa ``post_discharge_followup`` para a equipe de referência (``core.create_task`` é ``auto``).
A comunicação com o cidadão NÃO é feita por este agente.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta
from typing import Any, Literal

from pydantic import BaseModel, Field

from sus_nexus_ai.agents.base import AgentDefinition, PlannedAction, ToolCaller
from sus_nexus_ai.llm.client import LLMMessage

AGENT_ID = "post_discharge_followup"
VERSION = "1.0.0"
PROMPT_VERSION = "v1"
RULES_VERSION = "post_discharge_risk_v1"

HIGH_RISK_DIAGNOSIS_PREFIXES: tuple[str, ...] = ("I", "J", "E10", "E11", "N18", "F")
RiskLevel = Literal["none", "low", "medium", "high"]
DUE_DAYS: dict[str, int] = {"high": 2, "medium": 5, "low": 10}
PRIORITY: dict[str, str] = {"high": "urgent", "medium": "high", "low": "medium"}


class ReferenceTeam(BaseModel):
    team_ine: str
    health_unit_cnes: str | None = None


class DischargeEvent(BaseModel):
    event_id: str | None = None
    citizen_id: str
    episode_id: str
    discharged_at: datetime
    discharge_type: Literal["home", "transfer", "death", "evasion"] = "home"
    length_of_stay_days: int = Field(ge=0, default=0)
    primary_diagnosis_cid10: str | None = None
    readmissions_30d: int = Field(ge=0, default=0)
    age_years: int | None = Field(ge=0, le=130, default=None)
    comorbidities_count: int = Field(ge=0, default=0)
    has_care_plan: bool = False


class PostDischargeInput(BaseModel):
    event: DischargeEvent
    reference_team: ReferenceTeam


class PostDischargeOutput(BaseModel):
    risk_level: RiskLevel
    risk_score: int = Field(ge=0)
    rationale: str = Field(min_length=1, max_length=2000)
    followup_due_days: int = Field(ge=0, le=30)
    create_task: bool


class RiskAssessment(BaseModel):
    level: RiskLevel
    score: int
    factors: list[str]
    rules_version: str = RULES_VERSION


def assess_risk(event: DischargeEvent, summary: dict[str, Any]) -> RiskAssessment:
    """Regra ``post_discharge_risk_v1`` — pontuação simples, auditável."""
    if event.discharge_type == "death":
        return RiskAssessment(level="none", score=0, factors=["óbito: sem seguimento"])
    score = 0
    factors: list[str] = []
    if event.age_years is not None and event.age_years >= 65:
        score += 2
        factors.append("idade>=65")
    if event.length_of_stay_days >= 7:
        score += 1
        factors.append("internação>=7d")
    if event.readmissions_30d >= 1:
        score += 2
        factors.append("reinternação_30d")
    if event.comorbidities_count >= 2:
        score += 1
        factors.append("comorbidades>=2")
    cid = (event.primary_diagnosis_cid10 or "").upper()
    if cid and cid.startswith(HIGH_RISK_DIAGNOSIS_PREFIXES):
        score += 1
        factors.append(f"cid_alto_risco:{cid[:3]}")
    if not event.has_care_plan:
        score += 1
        factors.append("sem_plano_de_cuidado")
    if summary.get("contact_valid") is False:
        score += 1
        factors.append("contato_inválido")
    if int(summary.get("care_gaps") or 0) > 0:
        score += 1
        factors.append("lacunas_de_cuidado")
    if event.discharge_type == "evasion":
        score += 1
        factors.append("evasão")
    level: RiskLevel = "high" if score >= 5 else "medium" if score >= 3 else "low"
    return RiskAssessment(level=level, score=score, factors=factors)


async def build_context(tools: ToolCaller, inp: PostDischargeInput) -> dict[str, Any]:
    summary_model = await tools.call(
        "core.get_citizen_summary", citizen_id=inp.event.citizen_id, purpose="care_coordination"
    )
    summary = summary_model.model_dump(mode="json")
    assessment = assess_risk(inp.event, summary)
    return {
        "event": inp.event.model_dump(mode="json"),
        "reference_team": inp.reference_team.model_dump(mode="json"),
        "citizen_summary": summary,
        "risk_assessment": assessment.model_dump(mode="json"),
        "due_days_by_level": DUE_DAYS,
    }


def plan_actions(
    output: PostDischargeOutput, context: dict[str, Any], inp: PostDischargeInput
) -> list[PlannedAction]:
    # A regra versionada é a fonte da verdade para prioridade e prazo (não o LLM).
    level = str(context.get("risk_assessment", {}).get("level", output.risk_level))
    if level == "none" or not output.create_task:
        return []
    due_days = DUE_DAYS.get(level, 10)
    due_at = inp.event.discharged_at + timedelta(days=due_days)
    return [
        PlannedAction(
            tool="core.create_task",
            args={
                "task_type": "post_discharge_followup",
                "priority": PRIORITY.get(level, "medium"),
                "title": f"Acompanhamento pós-alta (risco {level})",
                "description": (
                    f"Episódio {inp.event.episode_id}. Risco {level} "
                    f"({context.get('risk_assessment', {}).get('rules_version', RULES_VERSION)}). "
                    f"{output.rationale}"
                )[:2000],
                "citizen_id": inp.event.citizen_id,
                "assignee": {"kind": "team", "id": inp.reference_team.team_ine},
                "due_at": due_at.isoformat(),
            },
            rationale=output.rationale,
        )
    ]


def fake_responder(messages: list[LLMMessage]) -> str:
    context = _context_from_messages(messages)
    assessment = context.get("risk_assessment", {})
    level = assessment.get("level", "low")
    factors = assessment.get("factors", [])
    rationale = (
        "Óbito registrado; sem seguimento pós-alta."
        if level == "none"
        else (
            f"Risco {level} (score {assessment.get('score', 0)}): "
            f"{', '.join(factors) or 'sem fatores'}."
        )
    )
    return json.dumps(
        {
            "risk_level": level,
            "risk_score": int(assessment.get("score", 0)),
            "rationale": rationale,
            "followup_due_days": DUE_DAYS.get(level, 0),
            "create_task": level != "none",
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
        description="Classifica risco pós-alta por regra versionada e cria tarefa de seguimento.",
        input_model=PostDischargeInput,
        output_model=PostDischargeOutput,
        tools=["core.get_citizen_summary", "core.create_task"],
        build_context=build_context,
        plan_actions=plan_actions,
        rule_versions={"risk": RULES_VERSION},
    )
