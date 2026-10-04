"""Acesso somente leitura à camada **agregada** do lakehouse (gold ``marts_aggregated``).

Fonte: modelos dbt em ``data/dbt/models/marts_aggregated`` e ``marts`` (dimensões):

* ``marts_aggregated.agg_indicadores_mensais`` — formato longo, grão
  ``indicator_code × tenant_id × month_start × aggregation_level × health_unit_key``; colunas
  ``indicator_code, indicator_name, tenant_id, month_start, competence (AAAAMM), aggregation_level
  (unidade|municipio), health_unit_cnes, health_unit_key, numerator, denominator, indicator_value,
  is_suppressed, indicator_unit, direction, target_value, is_on_target``;
* ``marts_aggregated.agg_care_gaps_monthly`` — território (unidade × equipe INE) × linha de
  cuidado: ``competence, health_unit_cnes, team_ine, care_line, n_detected, resolution_rate``;
* ``marts.dim_health_unit`` — ``health_unit_key, health_unit_name, unit_role`` (o Trino só libera
  ``marts.dim_*`` ao grupo ``bi``).

Proibido: ``marts_identified``, ``marts.fct_*``, silver/bronze/staging e o core por cidadão.
As consultas são **templates fixos** (``SQL_TEMPLATES``) com parâmetros validados; o município vem
sempre do tenant autenticado (``ToolContext.tenant``), nunca de argumento de ferramenta.

Supressão (n < 5): ``numerator``/``denominator`` NUNCA são lidos (não vão ao LLM e não permitem
inferência por diferença); célula suprimida é publicada com ``value = None`` e ``is_suppressed``.
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from pydantic import BaseModel, Field, ValidationError, model_validator

from sus_nexus_ai.tools.trino_client import SqlParam, TrinoHttpClient

# ---------------------------------------------------------------------------
# Whitelists e validação de parâmetros
# ---------------------------------------------------------------------------

IndicatorCode = Literal[
    "AGE_ABSENTEISMO",
    "AGE_COMPARECIMENTO",
    "AGE_CANCELAMENTO",
    "AGE_REAPROVEITAMENTO",
    "REG_ESPERA_P50_DIAS",
    "REG_ESPERA_P90_DIAS",
    "REG_SLA_CUMPRIDO",
    "REG_DEVOLUCAO",
    "REG_REALIZACAO",
    "EXA_CICLO_COMPLETO",
    "EXA_RESULTADO_SEM_RETORNO",
    "EXA_DIAS_PEDIDO_RESULTADO_P50",
    "HOS_REINTERNACAO_30D",
    "HOS_CONTATO_POS_ALTA_7D",
    "HOS_PERMANENCIA_MEDIA_DIAS",
    "CUI_LACUNAS_RESOLVIDAS",
    "TAR_SLA_CUMPRIDO",
    "TAR_AUTOMACAO",
    "TAR_AGENTE_SLA_CUMPRIDO",
    "PRO_GLOSA",
]

Direction = Literal["maior_melhor", "menor_melhor"]
UnitOfMeasure = Literal["proporcao", "dias", "horas"]


@dataclass(frozen=True)
class IndicatorInfo:
    title: str
    unit: UnitOfMeasure
    direction: Direction


# Espelho de ``data/dbt/seeds/seed_metas_indicadores.csv`` (títulos/unidade/direção). A meta
# usada na análise é sempre a publicada em ``target_value`` (a seed pode mudar sem mudar o código).
INDICATORS: dict[str, IndicatorInfo] = {
    "AGE_ABSENTEISMO": IndicatorInfo("Taxa de absenteísmo (no-show)", "proporcao", "menor_melhor"),
    "AGE_COMPARECIMENTO": IndicatorInfo("Taxa de comparecimento", "proporcao", "maior_melhor"),
    "AGE_CANCELAMENTO": IndicatorInfo("Taxa de cancelamento", "proporcao", "menor_melhor"),
    "AGE_REAPROVEITAMENTO": IndicatorInfo(
        "Reaproveitamento de vagas canceladas", "proporcao", "maior_melhor"
    ),
    "REG_ESPERA_P50_DIAS": IndicatorInfo(
        "Tempo de espera até agendamento (mediana)", "dias", "menor_melhor"
    ),
    "REG_ESPERA_P90_DIAS": IndicatorInfo(
        "Tempo de espera até agendamento (p90)", "dias", "menor_melhor"
    ),
    "REG_SLA_CUMPRIDO": IndicatorInfo(
        "Solicitações reguladas dentro do SLA", "proporcao", "maior_melhor"
    ),
    "REG_DEVOLUCAO": IndicatorInfo(
        "Taxa de devolução de solicitações", "proporcao", "menor_melhor"
    ),
    "REG_REALIZACAO": IndicatorInfo(
        "Solicitações encerradas com realização", "proporcao", "maior_melhor"
    ),
    "EXA_CICLO_COMPLETO": IndicatorInfo(
        "Pedidos de exame com ciclo completo (retorno)", "proporcao", "maior_melhor"
    ),
    "EXA_RESULTADO_SEM_RETORNO": IndicatorInfo(
        "Resultados sem retorno ao solicitante", "proporcao", "menor_melhor"
    ),
    "EXA_DIAS_PEDIDO_RESULTADO_P50": IndicatorInfo(
        "Dias do pedido ao resultado (mediana)", "dias", "menor_melhor"
    ),
    "HOS_REINTERNACAO_30D": IndicatorInfo(
        "Reinternação em até 30 dias", "proporcao", "menor_melhor"
    ),
    "HOS_CONTATO_POS_ALTA_7D": IndicatorInfo(
        "Contato da APS em até 7 dias após a alta", "proporcao", "maior_melhor"
    ),
    "HOS_PERMANENCIA_MEDIA_DIAS": IndicatorInfo(
        "Tempo médio de permanência (internação)", "dias", "menor_melhor"
    ),
    "CUI_LACUNAS_RESOLVIDAS": IndicatorInfo(
        "Lacunas de cuidado resolvidas", "proporcao", "maior_melhor"
    ),
    "TAR_SLA_CUMPRIDO": IndicatorInfo(
        "Tarefas concluídas dentro do SLA", "proporcao", "maior_melhor"
    ),
    "TAR_AUTOMACAO": IndicatorInfo("Tarefas criadas por automação", "proporcao", "maior_melhor"),
    "TAR_AGENTE_SLA_CUMPRIDO": IndicatorInfo(
        "Tarefas criadas por agentes concluídas no SLA", "proporcao", "maior_melhor"
    ),
    "PRO_GLOSA": IndicatorInfo("Taxa de glosa da produção", "proporcao", "menor_melhor"),
}
INDICATOR_CODES: tuple[str, ...] = tuple(INDICATORS)

COMPETENCE_PATTERN = r"^(19|20)\d{2}(0[1-9]|1[0-2])$"
TENANT_PATTERN = r"^ibge_\d{7}$"
CNES_PATTERN = r"^\d{7}$"
INE_PATTERN = r"^\d{10}$"
CARE_LINE_PATTERN = r"^[a-z][a-z0-9_]{1,39}$"
CATALOG_PATTERN = r"^[a-z][a-z0-9_]{0,63}$"

_COMPETENCE_RE = re.compile(COMPETENCE_PATTERN)
_TENANT_RE = re.compile(TENANT_PATTERN)
_CARE_LINE_RE = re.compile(CARE_LINE_PATTERN)
_CATALOG_RE = re.compile(CATALOG_PATTERN)

MAX_ROWS = 2000
MAX_WINDOW_MONTHS = 12


class InvalidAnalyticsParameter(ValueError):
    pass


def validate_tenant(tenant: str) -> str:
    if not isinstance(tenant, str) or not _TENANT_RE.fullmatch(tenant):
        raise InvalidAnalyticsParameter("tenant inválido (esperado ibge_<7 dígitos>)")
    return tenant


def validate_competence(value: str) -> str:
    if not isinstance(value, str) or not _COMPETENCE_RE.fullmatch(value):
        raise InvalidAnalyticsParameter("competência inválida (esperado AAAAMM)")
    return value


def validate_indicator_codes(codes: Sequence[str]) -> list[str]:
    if not codes:
        raise InvalidAnalyticsParameter("lista de indicadores vazia")
    unique: list[str] = []
    for code in codes:
        if code not in INDICATORS:
            raise InvalidAnalyticsParameter("indicador fora da whitelist")
        if code not in unique:
            unique.append(code)
    return unique


def validate_care_line(value: str | None) -> str | None:
    if value is None:
        return None
    if not _CARE_LINE_RE.fullmatch(value):
        raise InvalidAnalyticsParameter("linha de cuidado inválida")
    return value


def competence_index(value: str) -> int:
    validate_competence(value)
    return int(value[:4]) * 12 + int(value[4:]) - 1


def competence_from_index(index: int) -> str:
    return f"{index // 12:04d}{index % 12 + 1:02d}"


def competence_window(end: str, months: int) -> list[str]:
    """Competências ``[end-months+1 … end]`` em ordem cronológica."""
    last = competence_index(end)
    return [competence_from_index(i) for i in range(last - months + 1, last + 1)]


def months_between(start: str, end: str) -> int:
    return competence_index(end) - competence_index(start) + 1


# ---------------------------------------------------------------------------
# Linhas publicadas (já sem numerador/denominador)
# ---------------------------------------------------------------------------


class _SuppressedCell(BaseModel):
    is_suppressed: bool = False

    @model_validator(mode="after")
    def _suppressed_has_no_value(self) -> _SuppressedCell:
        # Nunca confiar num valor em célula suprimida: publica-se nulo (≠ zero).
        if self.is_suppressed:
            for name in ("value", "resolution_rate"):
                if getattr(self, name, None) is not None:
                    object.__setattr__(self, name, None)
        return self


class IndicatorPoint(_SuppressedCell):
    indicator_code: IndicatorCode
    competence: str = Field(pattern=COMPETENCE_PATTERN)
    value: float | None = None
    target: float | None = None
    direction: Direction | None = None
    unit: UnitOfMeasure | None = None
    is_on_target: bool | None = None


class UnitIndicatorPoint(IndicatorPoint):
    health_unit_cnes: str = Field(pattern=CNES_PATTERN)
    health_unit_name: str | None = Field(default=None, max_length=200)
    unit_role: Literal["aps", "hospital", "outro"] | None = None


class TerritoryCareGapPoint(_SuppressedCell):
    competence: str = Field(pattern=COMPETENCE_PATTERN)
    health_unit_cnes: str | None = Field(default=None, pattern=CNES_PATTERN)
    team_ine: str | None = Field(default=None, pattern=INE_PATTERN)
    care_line: str | None = Field(default=None, pattern=CARE_LINE_PATTERN)
    resolution_rate: float | None = None


@dataclass
class QueryResult:
    rows: list[Any]
    truncated: bool = False
    discarded: int = 0


class AggregatedAnalytics(Protocol):
    async def indicator_series(
        self,
        *,
        tenant: str,
        indicator_codes: Sequence[str],
        competence_from: str,
        competence_to: str,
        correlation_id: str | None = None,
    ) -> QueryResult: ...

    async def unit_indicators(
        self,
        *,
        tenant: str,
        indicator_codes: Sequence[str],
        competence: str,
        correlation_id: str | None = None,
    ) -> QueryResult: ...

    async def territory_care_gaps(
        self,
        *,
        tenant: str,
        competence: str,
        care_line: str | None = None,
        correlation_id: str | None = None,
    ) -> QueryResult: ...


# ---------------------------------------------------------------------------
# Templates SQL fixos (somente marts_aggregated + dimensões liberadas ao grupo bi)
# ---------------------------------------------------------------------------

ALLOWED_RELATIONS: frozenset[str] = frozenset(
    {
        "marts_aggregated.agg_indicadores_mensais",
        "marts_aggregated.agg_care_gaps_monthly",
        "marts.dim_health_unit",
    }
)
FORBIDDEN_SQL_FRAGMENTS: tuple[str, ...] = (
    "marts_identified",
    "bronze",
    "staging",
    "intermediate",
    "silver",
    "fct_",
    "rpt_",
    "postgresql",
    "numerator",
    "denominator",
    "citizen",
)

_IN_LIST = "{in_list}"

SQL_TEMPLATES: dict[str, str] = {
    "indicator_series": (
        "SELECT a.indicator_code, a.competence, a.indicator_value, a.target_value, a.direction, "
        "a.indicator_unit, a.is_on_target, a.is_suppressed "
        "FROM {catalog}.marts_aggregated.agg_indicadores_mensais a "
        "WHERE a.tenant_id = ? AND a.aggregation_level = 'municipio' "
        "AND a.competence BETWEEN ? AND ? AND a.indicator_code IN ({in_list}) "
        "ORDER BY a.indicator_code, a.competence "
        f"LIMIT {MAX_ROWS + 1}"
    ),
    "unit_indicators": (
        "SELECT a.indicator_code, a.competence, a.health_unit_cnes, u.health_unit_name, "
        "u.unit_role, a.indicator_value, a.target_value, a.direction, a.indicator_unit, "
        "a.is_on_target, a.is_suppressed "
        "FROM {catalog}.marts_aggregated.agg_indicadores_mensais a "
        "LEFT JOIN {catalog}.marts.dim_health_unit u "
        "ON u.health_unit_key = a.health_unit_key AND u.tenant_id = a.tenant_id "
        "WHERE a.tenant_id = ? AND a.aggregation_level = 'unidade' AND a.competence = ? "
        "AND a.indicator_code IN ({in_list}) "
        "ORDER BY a.indicator_code, a.health_unit_cnes "
        f"LIMIT {MAX_ROWS + 1}"
    ),
    "territory_care_gaps": (
        "SELECT g.competence, g.health_unit_cnes, g.team_ine, g.care_line, g.resolution_rate, "
        "g.n_detected IS NULL AS is_suppressed "
        "FROM {catalog}.marts_aggregated.agg_care_gaps_monthly g "
        "WHERE g.tenant_id = ? AND g.competence = ? "
        "ORDER BY g.care_line, g.health_unit_cnes, g.team_ine "
        f"LIMIT {MAX_ROWS + 1}"
    ),
    "territory_care_gaps_by_line": (
        "SELECT g.competence, g.health_unit_cnes, g.team_ine, g.care_line, g.resolution_rate, "
        "g.n_detected IS NULL AS is_suppressed "
        "FROM {catalog}.marts_aggregated.agg_care_gaps_monthly g "
        "WHERE g.tenant_id = ? AND g.competence = ? AND g.care_line = ? "
        "ORDER BY g.health_unit_cnes, g.team_ine "
        f"LIMIT {MAX_ROWS + 1}"
    ),
}

_RELATION_RE = re.compile(r"\{catalog\}\.([a-z_]+\.[a-z_]+)")


def check_sql_template(template: str) -> None:
    """Garante que o template só referencia relações agregadas permitidas."""
    lowered = template.lower()
    for fragment in FORBIDDEN_SQL_FRAGMENTS:
        if fragment in lowered:
            raise AssertionError(f"template referencia fragmento proibido: {fragment}")
    relations = set(_RELATION_RE.findall(template))
    if not relations or not relations <= ALLOWED_RELATIONS:
        raise AssertionError(f"relações não permitidas: {sorted(relations - ALLOWED_RELATIONS)}")
    if "tenant_id = ?" not in template:
        raise AssertionError("template sem filtro de tenant parametrizado")


def _render_template(name: str, catalog: str, in_size: int = 0) -> str:
    template = SQL_TEMPLATES[name]
    in_list = ", ".join("?" for _ in range(in_size))
    return template.replace("{catalog}", catalog).replace(_IN_LIST, in_list)


def _parse_rows(model: type[BaseModel], raw_rows: list[dict[str, Any]]) -> QueryResult:
    truncated = len(raw_rows) > MAX_ROWS
    rows: list[Any] = []
    discarded = 0
    for raw in raw_rows[:MAX_ROWS]:
        try:
            rows.append(model.model_validate(raw))
        except ValidationError:
            discarded += 1  # linha fora do shape esperado (ex.: indicador novo fora da whitelist)
    return QueryResult(rows=rows, truncated=truncated, discarded=discarded)


def _indicator_row(raw: dict[str, Any]) -> dict[str, Any]:
    return {
        "indicator_code": raw.get("indicator_code"),
        "competence": None if raw.get("competence") is None else str(raw["competence"]),
        "value": raw.get("indicator_value"),
        "target": raw.get("target_value"),
        "direction": raw.get("direction"),
        "unit": raw.get("indicator_unit"),
        "is_on_target": raw.get("is_on_target"),
        "is_suppressed": bool(raw.get("is_suppressed")),
        "health_unit_cnes": raw.get("health_unit_cnes"),
        "health_unit_name": raw.get("health_unit_name"),
        "unit_role": raw.get("unit_role"),
    }


class TrinoAggregatedAnalytics:
    """Implementação real: templates fixos executados no Trino (usuário do grupo ``bi``)."""

    def __init__(self, client: TrinoHttpClient, catalog: str) -> None:
        if not _CATALOG_RE.fullmatch(catalog):
            raise InvalidAnalyticsParameter("catálogo inválido")
        for template in SQL_TEMPLATES.values():
            check_sql_template(template)
        self.client = client
        self.catalog = catalog

    async def _run(
        self, name: str, params: list[SqlParam], in_size: int, correlation_id: str | None
    ) -> list[dict[str, Any]]:
        sql = _render_template(name, self.catalog, in_size)
        return await self.client.query(sql, params, correlation_id=correlation_id)

    async def indicator_series(
        self,
        *,
        tenant: str,
        indicator_codes: Sequence[str],
        competence_from: str,
        competence_to: str,
        correlation_id: str | None = None,
    ) -> QueryResult:
        tenant = validate_tenant(tenant)
        codes = validate_indicator_codes(indicator_codes)
        start, end = validate_competence(competence_from), validate_competence(competence_to)
        if not 1 <= months_between(start, end) <= MAX_WINDOW_MONTHS:
            raise InvalidAnalyticsParameter("janela de competências inválida")
        raw = await self._run(
            "indicator_series", [tenant, start, end, *codes], len(codes), correlation_id
        )
        return _parse_rows(IndicatorPoint, [_indicator_row(r) for r in raw])

    async def unit_indicators(
        self,
        *,
        tenant: str,
        indicator_codes: Sequence[str],
        competence: str,
        correlation_id: str | None = None,
    ) -> QueryResult:
        tenant = validate_tenant(tenant)
        codes = validate_indicator_codes(indicator_codes)
        competence = validate_competence(competence)
        raw = await self._run(
            "unit_indicators", [tenant, competence, *codes], len(codes), correlation_id
        )
        return _parse_rows(UnitIndicatorPoint, [_indicator_row(r) for r in raw])

    async def territory_care_gaps(
        self,
        *,
        tenant: str,
        competence: str,
        care_line: str | None = None,
        correlation_id: str | None = None,
    ) -> QueryResult:
        tenant = validate_tenant(tenant)
        competence = validate_competence(competence)
        care_line = validate_care_line(care_line)
        if care_line is None:
            raw = await self._run("territory_care_gaps", [tenant, competence], 0, correlation_id)
        else:
            raw = await self._run(
                "territory_care_gaps_by_line", [tenant, competence, care_line], 0, correlation_id
            )
        rows = [
            {
                "competence": None if r.get("competence") is None else str(r["competence"]),
                "health_unit_cnes": r.get("health_unit_cnes"),
                "team_ine": r.get("team_ine"),
                "care_line": r.get("care_line"),
                "resolution_rate": r.get("resolution_rate"),
                "is_suppressed": bool(r.get("is_suppressed")),
            }
            for r in raw
        ]
        return _parse_rows(TerritoryCareGapPoint, rows)

    async def aclose(self) -> None:
        await self.client.aclose()


@dataclass
class InMemoryAggregatedAnalytics:
    """Camada agregada em memória (dev/test/evals) com a mesma semântica dos templates.

    ``indicators``: linhas no shape de ``agg_indicadores_mensais`` (+ ``health_unit_name``/
    ``unit_role`` da dimensão); ``care_gaps``: shape de ``agg_care_gaps_monthly``.
    ``calls`` registra (método, tenant, parâmetros) para testes de isolamento de tenant.
    """

    indicators: list[dict[str, Any]] = field(default_factory=list)
    care_gaps: list[dict[str, Any]] = field(default_factory=list)
    calls: list[tuple[str, str, dict[str, Any]]] = field(default_factory=list)

    async def indicator_series(
        self,
        *,
        tenant: str,
        indicator_codes: Sequence[str],
        competence_from: str,
        competence_to: str,
        correlation_id: str | None = None,
    ) -> QueryResult:
        tenant = validate_tenant(tenant)
        codes = validate_indicator_codes(indicator_codes)
        start, end = validate_competence(competence_from), validate_competence(competence_to)
        if not 1 <= months_between(start, end) <= MAX_WINDOW_MONTHS:
            raise InvalidAnalyticsParameter("janela de competências inválida")
        self.calls.append(("indicator_series", tenant, {"codes": codes, "from": start, "to": end}))
        raw = [
            r
            for r in self.indicators
            if r.get("tenant_id") == tenant
            and r.get("aggregation_level") == "municipio"
            and start <= str(r.get("competence")) <= end
            and r.get("indicator_code") in codes
        ]
        raw.sort(key=lambda r: (str(r["indicator_code"]), str(r["competence"])))
        return _parse_rows(IndicatorPoint, [_indicator_row(r) for r in raw])

    async def unit_indicators(
        self,
        *,
        tenant: str,
        indicator_codes: Sequence[str],
        competence: str,
        correlation_id: str | None = None,
    ) -> QueryResult:
        tenant = validate_tenant(tenant)
        codes = validate_indicator_codes(indicator_codes)
        competence = validate_competence(competence)
        self.calls.append(("unit_indicators", tenant, {"codes": codes, "competence": competence}))
        raw = [
            r
            for r in self.indicators
            if r.get("tenant_id") == tenant
            and r.get("aggregation_level") == "unidade"
            and str(r.get("competence")) == competence
            and r.get("indicator_code") in codes
        ]
        raw.sort(key=lambda r: (str(r["indicator_code"]), str(r.get("health_unit_cnes"))))
        return _parse_rows(UnitIndicatorPoint, [_indicator_row(r) for r in raw])

    async def territory_care_gaps(
        self,
        *,
        tenant: str,
        competence: str,
        care_line: str | None = None,
        correlation_id: str | None = None,
    ) -> QueryResult:
        tenant = validate_tenant(tenant)
        competence = validate_competence(competence)
        care_line = validate_care_line(care_line)
        self.calls.append(
            ("territory_care_gaps", tenant, {"competence": competence, "care_line": care_line})
        )
        raw = [
            {
                "competence": str(r.get("competence")),
                "health_unit_cnes": r.get("health_unit_cnes"),
                "team_ine": r.get("team_ine"),
                "care_line": r.get("care_line"),
                "resolution_rate": r.get("resolution_rate"),
                "is_suppressed": r.get("n_detected") is None,
            }
            for r in self.care_gaps
            if r.get("tenant_id") == tenant
            and str(r.get("competence")) == competence
            and (care_line is None or r.get("care_line") == care_line)
        ]
        return _parse_rows(TerritoryCareGapPoint, raw)
