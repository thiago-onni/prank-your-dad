"""Agente ``bi_situation_analyst`` v1: análise de situação da competência (PLANO S26, F4).

Somente **dados agregados** (``marts_aggregated`` via Trino, ferramentas ``bi.*`` ``auto``/leitura).
O município é sempre o tenant autenticado; o agente não recebe município, SQL nem tabela.

Fluxo:

1. ``build_context`` consulta a série municipal (3–6 competências), os valores por unidade na
   competência e a resolução de lacunas por território (unidade × equipe);
2. a regra determinística ``bi_situation_rules_v1`` calcula: indicadores fora da meta (com
   distância à meta), tendência (melhora/piora/estável/indeterminado — mínimo 3 pontos publicados),
   desigualdade (maior × menor e razão) entre unidades e entre equipes; células suprimidas
   (n < 5) ficam nulas, **nunca** viram zero e são excluídas das comparações; numerador e
   denominador nem são lidos (sem inferência por diferença);
3. o LLM redige resumo, comentários, **hipóteses (marcadas como hipóteses)** e recomendações
   gerenciais textuais a partir dessa análise;
4. ``output_check`` (``bi_output_guardrails_v1``) rejeita e pede reparo quando: um número citado
   não vem das fontes; um achado estruturado diverge da regra; uma célula suprimida recebe valor;
   ou a saída contém CPF/CNS/telefone/e-mail/nome de pessoa. Esgotados os reparos →
   ``invalid_output`` (saída descartada).

Ações: nenhuma (``plan_actions`` → ``[]``). Toda sugestão de tarefa é recomendação textual.
"""

from __future__ import annotations

import json
import re
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from sus_nexus_ai.agents.base import AgentDefinition, PlannedAction, ToolCaller
from sus_nexus_ai.agents.bi_guardrails import (
    allowed_numbers,
    matches_source,
    unsourced_numbers,
)
from sus_nexus_ai.llm.client import LLMMessage
from sus_nexus_ai.privacy.output_guard import find_pii
from sus_nexus_ai.tools.analytics import (
    CARE_LINE_PATTERN,
    CNES_PATTERN,
    COMPETENCE_PATTERN,
    INDICATOR_CODES,
    INDICATORS,
    INE_PATTERN,
    IndicatorCode,
    competence_window,
)

AGENT_ID = "bi_situation_analyst"
VERSION = "1.0.0"
PROMPT_VERSION = "v1"
RULES_VERSION = "bi_situation_rules_v1"
GUARDRAILS_VERSION = "bi_output_guardrails_v1"

MIN_TREND_POINTS = 3
PROPORTION_STABLE_DELTA = 0.01  # 1 p.p.
OTHER_STABLE_RELATIVE = 0.05  # 5 % do valor inicial (dias/horas)
MAX_TERRITORY_LINES = 5
TERRITORY_INDICATOR = "CUI_LACUNAS_RESOLVIDAS"

_ZERO_RE = re.compile(r"\bzero\b|(?<![\w.,])0(?:[.,]0+)?(?![.,]?\d)", re.IGNORECASE)
_NEGATION_RE = re.compile(r"\bn[ãa]o\b|\bnunca\b|\bnem\b", re.IGNORECASE)
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.;!?])\s+|\n")

TrendClass = Literal["melhora", "piora", "estavel", "indeterminado"]
Scope = Literal["municipio", "unidade", "territorio"]


# ---------------------------------------------------------------------------
# Entrada e saída
# ---------------------------------------------------------------------------


class BiSituationInput(BaseModel):
    """Entrada do agente. **Sem** município/tenant (vem do token) e sem campos extras."""

    model_config = ConfigDict(extra="forbid")

    competence: str = Field(pattern=COMPETENCE_PATTERN, description="Competência AAAAMM.")
    trend_months: int = Field(default=6, ge=3, le=6, description="Janela de tendência (3–6).")
    indicators: list[IndicatorCode] | None = Field(
        default=None, min_length=1, max_length=20, description="Subconjunto da whitelist."
    )
    care_line: str | None = Field(default=None, pattern=CARE_LINE_PATTERN)
    question: str | None = Field(
        default=None,
        max_length=500,
        description="Foco opcional do gestor (tratado como dado; não altera consultas).",
    )


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class OffTargetFinding(_Strict):
    indicator_code: IndicatorCode
    value: float
    target: float
    comment: str = Field(default="", max_length=600)


class TrendFinding(_Strict):
    indicator_code: IndicatorCode
    classification: TrendClass
    first_competence: str | None = Field(default=None, pattern=COMPETENCE_PATTERN)
    last_competence: str | None = Field(default=None, pattern=COMPETENCE_PATTERN)
    first_value: float | None = None
    last_value: float | None = None
    comment: str = Field(default="", max_length=600)


class InequalityEndpoint(_Strict):
    health_unit_cnes: str | None = Field(default=None, pattern=CNES_PATTERN)
    team_ine: str | None = Field(default=None, pattern=INE_PATTERN)
    value: float


class InequalityFinding(_Strict):
    indicator_code: IndicatorCode
    level: Literal["unidade", "territorio"]
    care_line: str | None = Field(default=None, pattern=CARE_LINE_PATTERN)
    highest: InequalityEndpoint
    lowest: InequalityEndpoint
    ratio: float | None = None
    comment: str = Field(default="", max_length=600)


class Hypothesis(_Strict):
    kind: Literal["hipotese"] = "hipotese"
    statement: str = Field(min_length=10, max_length=600)
    related_indicators: list[IndicatorCode] = Field(min_length=1, max_length=6)
    how_to_verify: str = Field(default="", max_length=400)

    @field_validator("statement")
    @classmethod
    def _mark_as_hypothesis(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped.lower().startswith(("hipótese", "hipotese")):
            stripped = f"Hipótese: {stripped}"
        return stripped


class Recommendation(_Strict):
    kind: Literal["recomendacao_textual"] = "recomendacao_textual"
    action: str = Field(min_length=5, max_length=400)
    rationale: str = Field(default="", max_length=600)
    related_indicators: list[IndicatorCode] = Field(default_factory=list, max_length=6)
    responsible_area: str | None = Field(default=None, max_length=120)


class SourceRef(_Strict):
    indicator_code: IndicatorCode
    competence: str = Field(pattern=COMPETENCE_PATTERN)
    scope: Scope
    health_unit_cnes: str | None = Field(default=None, pattern=CNES_PATTERN)
    team_ine: str | None = Field(default=None, pattern=INE_PATTERN)
    care_line: str | None = Field(default=None, pattern=CARE_LINE_PATTERN)
    value: float | None = None
    suppressed: bool = False


class BiSituationOutput(_Strict):
    competence: str = Field(pattern=COMPETENCE_PATTERN)
    summary: str = Field(min_length=20, max_length=2000)
    off_target: list[OffTargetFinding] = Field(default_factory=list, max_length=20)
    trends: list[TrendFinding] = Field(default_factory=list, max_length=20)
    inequalities: list[InequalityFinding] = Field(default_factory=list, max_length=30)
    hypotheses: list[Hypothesis] = Field(default_factory=list, max_length=10)
    recommendations: list[Recommendation] = Field(default_factory=list, max_length=10)
    data_limitations: list[str] = Field(default_factory=list, max_length=10)
    sources: list[SourceRef] = Field(default_factory=list, max_length=200)


# ---------------------------------------------------------------------------
# Regra determinística (bi_situation_rules_v1)
# ---------------------------------------------------------------------------


def _round(value: float | None, unit: str | None) -> float | None:
    if value is None:
        return None
    return round(float(value), 4 if unit == "proporcao" else 2)


def _unit_of(code: str, row_unit: str | None) -> str:
    return row_unit or INDICATORS[code].unit


def _direction_of(code: str, row_direction: str | None) -> str:
    return row_direction or INDICATORS[code].direction


def classify_trend(
    points: list[tuple[str, float | None]], unit: str, direction: str
) -> dict[str, Any]:
    published = [(c, v) for c, v in points if v is not None]
    if len(published) < MIN_TREND_POINTS:
        return {
            "classification": "indeterminado",
            "reason": f"menos de {MIN_TREND_POINTS} competências com valor publicado",
            "first_competence": published[0][0] if published else None,
            "last_competence": published[-1][0] if published else None,
            "first_value": published[0][1] if published else None,
            "last_value": published[-1][1] if published else None,
        }
    (c0, v0), (c1, v1) = published[0], published[-1]
    delta = v1 - v0
    tolerance = (
        PROPORTION_STABLE_DELTA
        if unit == "proporcao"
        else max(abs(v0) * OTHER_STABLE_RELATIVE, 0.5)
    )
    if abs(delta) < tolerance:
        cls: TrendClass = "estavel"
    elif (delta > 0) == (direction == "maior_melhor"):
        cls = "melhora"
    else:
        cls = "piora"
    out: dict[str, Any] = {
        "classification": cls,
        "first_competence": c0,
        "last_competence": c1,
        "first_value": v0,
        "last_value": v1,
        "delta": _round(delta, unit),
    }
    if unit == "proporcao":
        out["delta_pp"] = round(delta * 100, 1)
    return out


def _extremes(items: list[dict[str, Any]]) -> dict[str, Any] | None:
    published = [i for i in items if i["value"] is not None]
    if len(published) < 2:
        return None
    highest = max(published, key=lambda i: (i["value"], i.get("key", "")))
    lowest = min(published, key=lambda i: (i["value"], i.get("key", "")))
    ratio = round(highest["value"] / lowest["value"], 2) if lowest["value"] > 0 else None
    return {"highest": highest, "lowest": lowest, "ratio": ratio, "compared": len(published)}


def analyze(
    competence: str,
    window: list[str],
    codes: list[str],
    series_rows: list[dict[str, Any]],
    unit_rows: list[dict[str, Any]],
    territory_rows: list[dict[str, Any]],
    *,
    truncated: bool = False,
) -> dict[str, Any]:
    """Aplica ``bi_situation_rules_v1`` às linhas publicadas (somente valores, nunca contagens)."""
    by_key = {(r["indicator_code"], r["competence"]): r for r in series_rows}
    facts: list[dict[str, Any]] = []
    municipal: list[dict[str, Any]] = []
    off_target: list[dict[str, Any]] = []
    trends: list[dict[str, Any]] = []
    suppressed: list[dict[str, Any]] = []
    limitations: list[str] = []
    no_data: list[str] = []

    for code in codes:
        info = INDICATORS[code]
        current = by_key.get((code, competence))
        unit = _unit_of(code, current.get("unit") if current else None)
        direction = _direction_of(code, current.get("direction") if current else None)
        points: list[tuple[str, float | None]] = []
        for comp in window:
            row = by_key.get((code, comp))
            value = _round(row.get("value"), unit) if row else None
            is_supp = bool(row and row.get("is_suppressed"))
            points.append((comp, value))
            if row is not None:
                facts.append(
                    {
                        "indicator_code": code,
                        "competence": comp,
                        "scope": "municipio",
                        "value": value,
                        "suppressed": is_supp,
                    }
                )
            if is_supp:
                suppressed.append(
                    {"indicator_code": code, "competence": comp, "scope": "municipio"}
                )
        if current is None:
            no_data.append(code)
        value = _round(current.get("value"), unit) if current else None
        target = _round(current.get("target"), unit) if current else None
        entry: dict[str, Any] = {
            "indicator_code": code,
            "indicator_title": info.title,
            "unit": unit,
            "direction": direction,
            "value": value,
            "target": target,
            "suppressed": bool(current and current.get("is_suppressed")),
            "status": (
                "sem_dado"
                if current is None
                else "suprimido"
                if current.get("is_suppressed")
                else "sem_meta_ou_valor"
                if current.get("is_on_target") is None or value is None
                else "na_meta"
                if current.get("is_on_target")
                else "fora_da_meta"
            ),
        }
        if unit == "proporcao" and value is not None:
            entry["value_pct"] = round(value * 100, 1)
            if target is not None:
                entry["target_pct"] = round(target * 100, 1)
        municipal.append(entry)
        if entry["status"] == "fora_da_meta" and value is not None and target is not None:
            finding: dict[str, Any] = {
                "indicator_code": code,
                "indicator_title": info.title,
                "unit": unit,
                "direction": direction,
                "value": value,
                "target": target,
                "gap": _round(value - target, unit),
            }
            if unit == "proporcao":
                finding["value_pct"] = entry["value_pct"]
                finding["target_pct"] = entry["target_pct"]
                finding["gap_pp"] = round((value - target) * 100, 1)
            off_target.append(finding)
        trend = classify_trend(points, unit, direction)
        trends.append(
            {
                "indicator_code": code,
                "unit": unit,
                "direction": direction,
                "points": [
                    {"competence": c, "value": v, "suppressed": (code, c) in _supp_keys(suppressed)}
                    for c, v in points
                ],
                **trend,
            }
        )

    inequalities: list[dict[str, Any]] = []
    for code in codes:
        unit = _unit_of(code, None)
        rows = [r for r in unit_rows if r["indicator_code"] == code]
        if not rows:
            continue
        items = []
        suppressed_units = []
        for r in rows:
            value = _round(r.get("value"), r.get("unit") or unit)
            if r.get("is_suppressed"):
                suppressed_units.append(r["health_unit_cnes"])
                facts.append(
                    {
                        "indicator_code": code,
                        "competence": competence,
                        "scope": "unidade",
                        "health_unit_cnes": r["health_unit_cnes"],
                        "value": None,
                        "suppressed": True,
                    }
                )
                continue
            items.append(
                {
                    "key": r["health_unit_cnes"],
                    "health_unit_cnes": r["health_unit_cnes"],
                    "health_unit_name": r.get("health_unit_name"),
                    "value": value,
                }
            )
        if suppressed_units:
            suppressed.append(
                {
                    "indicator_code": code,
                    "competence": competence,
                    "scope": "unidade",
                    "health_unit_cnes": sorted(suppressed_units),
                }
            )
        ext = _extremes(items)
        if ext is None:
            continue
        for side in ("highest", "lowest"):
            facts.append(
                {
                    "indicator_code": code,
                    "competence": competence,
                    "scope": "unidade",
                    "health_unit_cnes": ext[side]["health_unit_cnes"],
                    "value": ext[side]["value"],
                    "suppressed": False,
                }
            )
        inequalities.append(
            {
                "indicator_code": code,
                "level": "unidade",
                "care_line": None,
                "direction": _direction_of(code, None),
                "highest": {k: v for k, v in ext["highest"].items() if k != "key"},
                "lowest": {k: v for k, v in ext["lowest"].items() if k != "key"},
                "ratio": ext["ratio"],
                "units_compared": ext["compared"],
                "units_suppressed_excluded": len(suppressed_units),
            }
        )

    # Território: resolução de lacunas por equipe, por linha de cuidado (nunca soma células).
    lines: dict[str, list[dict[str, Any]]] = {}
    territory_suppressed: dict[str, int] = {}
    for r in territory_rows:
        line = r.get("care_line") or "sem_linha"
        if r.get("is_suppressed") or r.get("resolution_rate") is None:
            if r.get("is_suppressed"):
                territory_suppressed[line] = territory_suppressed.get(line, 0) + 1
            continue
        lines.setdefault(line, []).append(
            {
                "key": f"{r.get('health_unit_cnes') or '-'}:{r.get('team_ine') or '-'}",
                "health_unit_cnes": r.get("health_unit_cnes"),
                "team_ine": r.get("team_ine"),
                "value": _round(r.get("resolution_rate"), "proporcao"),
            }
        )
    territory: list[dict[str, Any]] = []
    for line, items in lines.items():
        ext = _extremes(items)
        if ext is None:
            continue
        territory.append(
            {
                "indicator_code": TERRITORY_INDICATOR,
                "level": "territorio",
                "care_line": line if line != "sem_linha" else None,
                "direction": "maior_melhor",
                "highest": {k: v for k, v in ext["highest"].items() if k != "key"},
                "lowest": {k: v for k, v in ext["lowest"].items() if k != "key"},
                "ratio": ext["ratio"],
                "units_compared": ext["compared"],
                "units_suppressed_excluded": territory_suppressed.get(line, 0),
            }
        )
    territory.sort(key=lambda t: (-(t["ratio"] or 0), str(t["care_line"])))
    territory = territory[:MAX_TERRITORY_LINES]
    for t in territory:
        for side in ("highest", "lowest"):
            facts.append(
                {
                    "indicator_code": TERRITORY_INDICATOR,
                    "competence": competence,
                    "scope": "territorio",
                    "health_unit_cnes": t[side]["health_unit_cnes"],
                    "team_ine": t[side]["team_ine"],
                    "care_line": t["care_line"],
                    "value": t[side]["value"],
                    "suppressed": False,
                }
            )
    inequalities.extend(territory)

    if suppressed or territory_suppressed:
        limitations.append(
            "Células com menos de 5 registros são suprimidas (valor nulo): não equivalem a zero e "
            "foram excluídas das comparações."
        )
    if no_data:
        limitations.append(
            "Indicadores sem dado publicado na competência: " + ", ".join(sorted(no_data)) + "."
        )
    if truncated:
        limitations.append("Consulta truncada no limite de linhas; análise parcial.")

    evaluable = [m for m in municipal if m["status"] in {"na_meta", "fora_da_meta"}]
    return {
        "municipal": municipal,
        "off_target": off_target,
        "trends": trends,
        "inequalities": inequalities,
        "suppressed": suppressed,
        "facts": facts,
        "data_limitations": limitations,
        "summary_counts": {
            "indicators_requested": len(codes),
            "indicators_evaluable": len(evaluable),
            "indicators_off_target": len(off_target),
            "indicators_on_target": len(evaluable) - len(off_target),
            "window_size": len(window),
        },
    }


def _supp_keys(suppressed: list[dict[str, Any]]) -> set[tuple[str, str]]:
    return {(s["indicator_code"], s["competence"]) for s in suppressed if s["scope"] == "municipio"}


# ---------------------------------------------------------------------------
# Contexto, ações
# ---------------------------------------------------------------------------


async def build_context(tools: ToolCaller, inp: BiSituationInput) -> dict[str, Any]:
    codes: list[str] = list(dict.fromkeys(inp.indicators or INDICATOR_CODES))
    window = competence_window(inp.competence, inp.trend_months)
    series = await tools.call(
        "bi.get_indicator_series",
        indicator_codes=codes,
        competence_from=window[0],
        competence_to=window[-1],
    )
    units = await tools.call(
        "bi.get_unit_indicators", indicator_codes=codes, competence=inp.competence
    )
    # Território (equipes) só quando o indicador de lacunas está no escopo da análise.
    territory_rows: dict[str, Any] = {"rows": [], "truncated": False}
    if TERRITORY_INDICATOR in codes:
        territory_args: dict[str, Any] = {"competence": inp.competence}
        if inp.care_line:
            territory_args["care_line"] = inp.care_line
        territory = await tools.call("bi.get_territory_care_gaps", **territory_args)
        territory_rows = territory.model_dump(mode="json")
    s, u, t = series.model_dump(mode="json"), units.model_dump(mode="json"), territory_rows
    analysis = analyze(
        inp.competence,
        window,
        codes,
        s["rows"],
        u["rows"],
        t["rows"],
        truncated=bool(s["truncated"] or u["truncated"] or t["truncated"]),
    )
    context: dict[str, Any] = {
        "competence": inp.competence,
        "window": window,
        "rules_version": RULES_VERSION,
        "guardrails_version": GUARDRAILS_VERSION,
        **analysis,
    }
    if inp.question:
        context["question"] = inp.question
    return context


def plan_actions(
    output: BiSituationOutput, context: dict[str, Any], inp: BiSituationInput
) -> list[PlannedAction]:
    return []  # somente leitura: recomendações ficam como texto (sem tarefas/escritas)


# ---------------------------------------------------------------------------
# Verificação pós-geração (bi_output_guardrails_v1)
# ---------------------------------------------------------------------------


def _fact_key(
    code: str,
    competence: str,
    scope: str,
    cnes: str | None = None,
    ine: str | None = None,
    line: str | None = None,
) -> tuple[str, str, str, str, str, str]:
    return (code, competence, scope, cnes or "", ine or "", line or "")


def _texts(output: BiSituationOutput) -> list[str]:
    texts = [output.summary, *output.data_limitations]
    texts += [f.comment for f in output.off_target]
    texts += [f.comment for f in output.trends]
    texts += [f.comment for f in output.inequalities]
    for h in output.hypotheses:
        texts += [h.statement, h.how_to_verify]
    for r in output.recommendations:
        texts += [r.action, r.rationale, r.responsible_area or ""]
    return [t for t in texts if t]


def _source_texts(context: dict[str, Any]) -> list[str]:
    out: list[str] = []

    def walk(node: Any, key: str | None = None) -> None:
        if key == "question":
            return
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, k)
        elif isinstance(node, list):
            for v in node:
                walk(v, key)
        elif isinstance(node, str):
            out.append(node)

    walk(context)
    return out


def check_output(output: BiSituationOutput, context: dict[str, Any]) -> list[str]:
    """Problemas encontrados (lista vazia = saída aprovada)."""
    problems: list[str] = []
    if output.competence != context.get("competence"):
        problems.append("competence difere da competência analisada")

    facts = {
        _fact_key(
            f["indicator_code"],
            f["competence"],
            f["scope"],
            f.get("health_unit_cnes"),
            f.get("team_ine"),
            f.get("care_line"),
        ): f
        for f in context.get("facts", [])
    }

    # 1) Fontes: cada uma deve existir nos dados e ter o mesmo valor; suprimida → sem valor.
    for s in output.sources:
        key = _fact_key(
            s.indicator_code, s.competence, s.scope, s.health_unit_cnes, s.team_ine, s.care_line
        )
        fact = facts.get(key)
        if fact is None:
            problems.append(f"fonte inexistente nos dados: {s.indicator_code}/{s.competence}")
            continue
        if fact["suppressed"]:
            if s.value is not None or not s.suppressed:
                problems.append(
                    f"célula suprimida citada com valor ({s.indicator_code}/{s.competence}): "
                    "suprimido não é zero nem número"
                )
        elif s.suppressed or not matches_source(s.value, fact["value"]):
            problems.append(f"valor da fonte {s.indicator_code}/{s.competence} difere dos dados")

    # 2) Achados estruturados = regra determinística.
    rule_off = {f["indicator_code"]: f for f in context.get("off_target", [])}
    for o in output.off_target:
        rule = rule_off.get(o.indicator_code)
        if rule is None:
            problems.append(f"{o.indicator_code} não está fora da meta nos dados")
            continue
        if not matches_source(o.value, rule["value"]) or not matches_source(
            o.target, rule["target"]
        ):
            problems.append(f"valor/meta de {o.indicator_code} difere dos dados")
    rule_trends = {t["indicator_code"]: t for t in context.get("trends", [])}
    for t in output.trends:
        rule_t = rule_trends.get(t.indicator_code)
        if rule_t is None:
            problems.append(f"tendência de {t.indicator_code} não consta dos dados")
            continue
        if t.classification != rule_t["classification"]:
            problems.append(
                f"tendência de {t.indicator_code} deve ser '{rule_t['classification']}'"
            )
        if not matches_source(t.first_value, rule_t.get("first_value")) or not matches_source(
            t.last_value, rule_t.get("last_value")
        ):
            problems.append(f"valores da tendência de {t.indicator_code} diferem dos dados")
    rule_ineq = {
        (i["indicator_code"], i["level"], i.get("care_line")): i
        for i in context.get("inequalities", [])
    }
    for i in output.inequalities:
        rule_i = rule_ineq.get((i.indicator_code, i.level, i.care_line))
        if rule_i is None:
            problems.append(f"desigualdade de {i.indicator_code} ({i.level}) não consta dos dados")
            continue
        for side, cited in (("highest", i.highest), ("lowest", i.lowest)):
            ref = rule_i[side]
            if (cited.health_unit_cnes or None) != (ref.get("health_unit_cnes") or None) or (
                cited.team_ine or None
            ) != (ref.get("team_ine") or None):
                problems.append(f"extremo '{side}' de {i.indicator_code} difere dos dados")
            elif not matches_source(cited.value, ref["value"]):
                problems.append(f"valor '{side}' de {i.indicator_code} difere dos dados")
        if not matches_source(i.ratio, rule_i["ratio"]):
            problems.append(f"razão de {i.indicator_code} difere dos dados")

    cited_codes = {s.indicator_code for s in output.sources}
    finding_codes = (
        {o.indicator_code for o in output.off_target}
        | {t.indicator_code for t in output.trends if t.classification != "indeterminado"}
        | {i.indicator_code for i in output.inequalities}
    )
    for code in sorted(finding_codes - cited_codes):
        problems.append(f"achado sobre {code} sem fonte em sources")

    # 3) Números em texto livre precisam vir das fontes consultadas.
    texts = _texts(output)
    missing = unsourced_numbers(texts, allowed_numbers(context))
    if missing:
        problems.append("números sem fonte nos dados consultados: " + ", ".join(missing[:10]))

    # 4) Supressão: célula suprimida não pode ser tratada como zero no texto.
    suppressed_units = {
        cnes
        for s in context.get("suppressed", [])
        if s.get("scope") == "unidade"
        for cnes in (s.get("health_unit_cnes") or [])
    }
    if any(
        _ZERO_RE.search(sentence)
        and not _NEGATION_RE.search(sentence)
        and ("suprimid" in sentence.lower() or any(c in sentence for c in suppressed_units))
        for text in texts
        for sentence in _SENTENCE_SPLIT_RE.split(text)
    ):
        problems.append("célula suprimida interpretada como zero")

    # 5) PII: nunca CPF/CNS/telefone/e-mail/nomes de pessoas na saída.
    allowed_texts = _source_texts(context)
    pii: set[str] = set()
    for text in texts:
        pii.update(find_pii(text, allowed_texts))
    if pii:
        problems.append("saída contém dado pessoal (" + ", ".join(sorted(pii)) + "): remova")
    return problems


def output_check(output: Any, context: dict[str, Any]) -> list[str]:
    return check_output(BiSituationOutput.model_validate(output), context)


# ---------------------------------------------------------------------------
# LLM fake determinístico (evals/testes)
# ---------------------------------------------------------------------------


def _fmt_pct(value: float) -> str:
    return f"{value:.1f}".replace(".", ",")


def _fmt_competence(comp: str) -> str:
    return f"{comp[4:]}/{comp[:4]}"


def fake_responder(messages: list[LLMMessage]) -> str:
    context = _context_from_messages(messages)
    competence = str(context.get("competence", "200001"))
    counts = context.get("summary_counts", {})
    off = context.get("off_target", [])
    sources: list[dict[str, Any]] = []
    seen: set[tuple[Any, ...]] = set()

    def add_source(fact: dict[str, Any]) -> None:
        key = (
            fact["indicator_code"],
            fact["competence"],
            fact["scope"],
            fact.get("health_unit_cnes"),
            fact.get("team_ine"),
            fact.get("care_line"),
        )
        if key in seen:
            return
        seen.add(key)
        sources.append(
            {
                "indicator_code": fact["indicator_code"],
                "competence": fact["competence"],
                "scope": fact["scope"],
                "health_unit_cnes": fact.get("health_unit_cnes"),
                "team_ine": fact.get("team_ine"),
                "care_line": fact.get("care_line"),
                "value": fact.get("value"),
                "suppressed": bool(fact.get("suppressed")),
            }
        )

    # Prioridade: fatos da competência analisada (não suprimidos, depois suprimidos), depois série.
    facts = sorted(
        context.get("facts", []),
        key=lambda f: (f["competence"] != competence, bool(f.get("suppressed"))),
    )
    for fact in facts:
        add_source(fact)

    off_target = []
    for f in off:
        if f["unit"] == "proporcao":
            comment = (
                f"{f['indicator_title']}: {_fmt_pct(f['value_pct'])}% frente à meta de "
                f"{_fmt_pct(f['target_pct'])}% ({_fmt_pct(abs(f['gap_pp']))} p.p. de distância)."
            )
        else:
            comment = f"{f['indicator_title']}: {f['value']} {f['unit']} (meta {f['target']})."
        off_target.append(
            {
                "indicator_code": f["indicator_code"],
                "value": f["value"],
                "target": f["target"],
                "comment": comment,
            }
        )
    trends = [
        {
            "indicator_code": t["indicator_code"],
            "classification": t["classification"],
            "first_competence": t.get("first_competence"),
            "last_competence": t.get("last_competence"),
            "first_value": t.get("first_value"),
            "last_value": t.get("last_value"),
            "comment": (
                "Série insuficiente: células suprimidas não foram tratadas como zero."
                if t["classification"] == "indeterminado"
                else f"Tendência de {t['classification']} na janela analisada."
            ),
        }
        for t in context.get("trends", [])
        if t["classification"] != "indeterminado" or t.get("first_value") is not None
    ]
    inequalities = []
    for i in context.get("inequalities", []):
        ratio = i.get("ratio")
        inequalities.append(
            {
                "indicator_code": i["indicator_code"],
                "level": i["level"],
                "care_line": i.get("care_line"),
                "highest": {
                    "health_unit_cnes": i["highest"].get("health_unit_cnes"),
                    "team_ine": i["highest"].get("team_ine"),
                    "value": i["highest"]["value"],
                },
                "lowest": {
                    "health_unit_cnes": i["lowest"].get("health_unit_cnes"),
                    "team_ine": i["lowest"].get("team_ine"),
                    "value": i["lowest"]["value"],
                },
                "ratio": ratio,
                "comment": (
                    f"Razão maior/menor de {str(ratio).replace('.', ',')}."
                    if ratio is not None
                    else "Menor valor igual a zero: razão indefinida."
                ),
            }
        )
    hypotheses = [
        {
            "kind": "hipotese",
            "statement": (
                f"Hipótese: o resultado de {f['indicator_title'].lower()} pode refletir "
                "fluxos de trabalho heterogêneos entre as unidades."
            ),
            "related_indicators": [f["indicator_code"]],
            "how_to_verify": "Comparar processos das unidades nos extremos da distribuição.",
        }
        for f in off[:3]
    ]
    recommendations = [
        {
            "kind": "recomendacao_textual",
            "action": (
                f"Pactuar plano de ação com as coordenações responsáveis por "
                f"{f['indicator_title'].lower()}."
            ),
            "rationale": "Indicador fora da meta na competência analisada.",
            "related_indicators": [f["indicator_code"]],
            "responsible_area": None,
        }
        for f in off[:3]
    ]
    summary = (
        f"Competência {_fmt_competence(competence)}: "
        f"{counts.get('indicators_off_target', 0)} de {counts.get('indicators_evaluable', 0)} "
        "indicadores com meta avaliável estão fora da meta."
    )
    return json.dumps(
        {
            "competence": competence,
            "summary": summary,
            "off_target": off_target,
            "trends": trends,
            "inequalities": inequalities,
            "hypotheses": hypotheses,
            "recommendations": recommendations,
            "data_limitations": list(context.get("data_limitations", [])),
            "sources": sources[:200],
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


def definition() -> AgentDefinition[BiSituationInput, BiSituationOutput]:
    return AgentDefinition(
        id=AGENT_ID,
        version=VERSION,
        prompt_version=PROMPT_VERSION,
        description=(
            "Análise de situação da competência só com dados agregados: indicadores fora da meta, "
            "tendência, desigualdade entre unidades/territórios, hipóteses e recomendações "
            "gerenciais (sem ações)."
        ),
        input_model=BiSituationInput,
        output_model=BiSituationOutput,
        tools=["bi.get_indicator_series", "bi.get_unit_indicators", "bi.get_territory_care_gaps"],
        build_context=build_context,
        plan_actions=plan_actions,
        rule_versions={"analysis": RULES_VERSION, "guardrails": GUARDRAILS_VERSION},
        output_check=output_check,
    )
