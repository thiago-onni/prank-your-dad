"""Agente ``mpi_duplicate_suggestion`` v2: sugestão sobre caso de possível duplicidade (F2).

Lê o ``MergeCase`` real (``GET /mpi/cases/{id}``: ``evidence[]`` por atributo e ``conflicts[]``)
e aplica a heurística versionada ``duplicate_heuristics_v1`` como referência para o LLM.

Autonomia: SOMENTE sugestão. Não planeja nenhuma ação; ``mpi.merge`` é ``forbidden``.
Gatilho: ``sus.identity.merge.case_opened``.
"""

from __future__ import annotations

import json
from typing import Any, Literal

from pydantic import BaseModel, Field

from sus_nexus_ai.agents.base import AgentDefinition, PlannedAction, ToolCaller
from sus_nexus_ai.llm.client import LLMMessage

AGENT_ID = "mpi_duplicate_suggestion"
VERSION = "2.0.0"
PROMPT_VERSION = "v1"
RULES_VERSION = "duplicate_heuristics_v1"

STRONG_ATTRIBUTES = frozenset({"cpf", "cns", "birthdate", "data_nascimento"})

Verdict = Literal["probable_same_person", "probable_different_person", "inconclusive"]


class MpiDuplicateInput(BaseModel):
    case_id: str


class MpiDuplicateOutput(BaseModel):
    verdict: Verdict
    justification: str = Field(min_length=1, max_length=2000)
    confidence: float = Field(ge=0.0, le=1.0)
    key_evidence: list[str] = Field(default_factory=list)


async def build_context(tools: ToolCaller, inp: MpiDuplicateInput) -> dict[str, Any]:
    case_model = await tools.call("core.get_merge_case", case_id=inp.case_id)
    case = case_model.model_dump(mode="json")
    verdict, confidence, key = suggest(case)
    return {
        "case": case,
        "rules_version": RULES_VERSION,
        "heuristic_reference": {"verdict": verdict, "confidence": confidence, "key": key[:8]},
    }


def plan_actions(
    output: MpiDuplicateOutput, context: dict[str, Any], inp: MpiDuplicateInput
) -> list[PlannedAction]:
    return []  # somente sugestão (AIA-004): a decisão é sempre humana


def suggest(case: dict[str, Any]) -> tuple[Verdict, float, list[str]]:
    """Heurística determinística de referência (``duplicate_heuristics_v1``)."""
    evidence = case.get("evidence") or []
    conflicts = [str(c).lower() for c in case.get("conflicts") or []]
    key: list[str] = []
    strong_disagree = [
        e["attribute"]
        for e in evidence
        if e.get("agreement") == "disagree"
        and str(e.get("attribute", "")).lower() in STRONG_ATTRIBUTES
    ]
    if strong_disagree or any(any(a in c for a in STRONG_ATTRIBUTES) for c in conflicts):
        key = [f"divergência forte em {a}" for a in strong_disagree] or conflicts
        return "probable_different_person", 0.9, key

    total = sum(float(e.get("weight", 0)) for e in evidence if e.get("agreement") != "missing")
    agree = sum(float(e.get("weight", 0)) for e in evidence if e.get("agreement") == "agree")
    ratio = agree / total if total else 0.0
    score = float(case.get("score") or 0.0)
    key = [f"{e['attribute']} concorda" for e in evidence if e.get("agreement") == "agree"]
    if ratio >= 0.8 and score >= 0.85:
        return "probable_same_person", min(0.95, round(score, 2)), key
    missing = [e["attribute"] for e in evidence if e.get("agreement") == "missing"]
    key += [f"{a} ausente" for a in missing]
    return "inconclusive", 0.5, key


def fake_responder(messages: list[LLMMessage]) -> str:
    context = _context_from_messages(messages)
    case = context.get("case", {})
    verdict, confidence, key = suggest(case)
    justification = {
        "probable_same_person": "Atributos fortes e demográficos concordam; sem divergências.",
        "probable_different_person": "Há divergência em identificador forte ou data de nascimento.",
        "inconclusive": "Evidências insuficientes ou parcialmente ausentes para concluir.",
    }[verdict]
    return json.dumps(
        {
            "verdict": verdict,
            "justification": justification,
            "confidence": confidence,
            "key_evidence": key[:8],
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


def definition() -> AgentDefinition[MpiDuplicateInput, MpiDuplicateOutput]:
    return AgentDefinition(
        id=AGENT_ID,
        version=VERSION,
        prompt_version=PROMPT_VERSION,
        description="Sugere se um caso de duplicidade do MPI é a mesma pessoa (sem ação).",
        input_model=MpiDuplicateInput,
        output_model=MpiDuplicateOutput,
        tools=["core.get_merge_case"],
        build_context=build_context,
        plan_actions=plan_actions,
        rule_versions={"heuristics": RULES_VERSION},
    )
