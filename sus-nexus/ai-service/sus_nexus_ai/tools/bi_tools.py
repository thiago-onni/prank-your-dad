"""Ferramentas do agente de BI — somente leitura da camada **agregada** (``action_class=auto``).

| ferramenta                   | fonte (Trino, templates fixos)                                   |
|------------------------------|------------------------------------------------------------------|
| bi.get_indicator_series      | ``marts_aggregated.agg_indicadores_mensais`` (nível município)   |
| bi.get_unit_indicators       | idem (nível unidade) + ``marts.dim_health_unit`` (nome, papel)    |
| bi.get_territory_care_gaps   | ``marts_aggregated.agg_care_gaps_monthly`` (unidade × equipe)     |

Nenhuma ferramenta recebe SQL, tabela, coluna ou município: os argumentos são indicadores da
whitelist, competências ``AAAAMM`` e linha de cuidado (regex). O tenant é sempre
``ToolContext.tenant`` (contexto autenticado). Entradas com campos extras são recusadas.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator

from sus_nexus_ai.tools.analytics import (
    CARE_LINE_PATTERN,
    COMPETENCE_PATTERN,
    MAX_WINDOW_MONTHS,
    AggregatedAnalytics,
    IndicatorCode,
    IndicatorPoint,
    TerritoryCareGapPoint,
    UnitIndicatorPoint,
    months_between,
)
from sus_nexus_ai.tools.registry import ToolContext, ToolRegistry, ToolSpec

BI_SCOPE = "analytics:aggregated:read"
BI_OWNER = "analytics"


class AnalyticsUnavailable(RuntimeError):
    pass


class _StrictInput(BaseModel):
    model_config = ConfigDict(extra="forbid")


class IndicatorSeriesInput(_StrictInput):
    indicator_codes: list[IndicatorCode] = Field(min_length=1, max_length=20)
    competence_from: str = Field(pattern=COMPETENCE_PATTERN)
    competence_to: str = Field(pattern=COMPETENCE_PATTERN)

    @model_validator(mode="after")
    def _window(self) -> IndicatorSeriesInput:
        span = months_between(self.competence_from, self.competence_to)
        if not 1 <= span <= MAX_WINDOW_MONTHS:
            raise ValueError(f"janela deve ter de 1 a {MAX_WINDOW_MONTHS} competências")
        return self


class UnitIndicatorsInput(_StrictInput):
    indicator_codes: list[IndicatorCode] = Field(min_length=1, max_length=20)
    competence: str = Field(pattern=COMPETENCE_PATTERN)


class TerritoryCareGapsInput(_StrictInput):
    competence: str = Field(pattern=COMPETENCE_PATTERN)
    care_line: str | None = Field(default=None, pattern=CARE_LINE_PATTERN)


class IndicatorSeriesOutput(BaseModel):
    rows: list[IndicatorPoint] = Field(default_factory=list)
    truncated: bool = False


class UnitIndicatorsOutput(BaseModel):
    rows: list[UnitIndicatorPoint] = Field(default_factory=list)
    truncated: bool = False


class TerritoryCareGapsOutput(BaseModel):
    rows: list[TerritoryCareGapPoint] = Field(default_factory=list)
    truncated: bool = False


def _analytics(ctx: ToolContext) -> AggregatedAnalytics:
    if ctx.analytics is None:
        raise AnalyticsUnavailable("camada analítica não configurada")
    return ctx.analytics


async def _indicator_series(ctx: ToolContext, args: IndicatorSeriesInput) -> IndicatorSeriesOutput:
    result = await _analytics(ctx).indicator_series(
        tenant=ctx.tenant,
        indicator_codes=list(args.indicator_codes),
        competence_from=args.competence_from,
        competence_to=args.competence_to,
        correlation_id=ctx.correlation_id,
    )
    return IndicatorSeriesOutput(rows=result.rows, truncated=result.truncated)


async def _unit_indicators(ctx: ToolContext, args: UnitIndicatorsInput) -> UnitIndicatorsOutput:
    result = await _analytics(ctx).unit_indicators(
        tenant=ctx.tenant,
        indicator_codes=list(args.indicator_codes),
        competence=args.competence,
        correlation_id=ctx.correlation_id,
    )
    return UnitIndicatorsOutput(rows=result.rows, truncated=result.truncated)


async def _territory_care_gaps(
    ctx: ToolContext, args: TerritoryCareGapsInput
) -> TerritoryCareGapsOutput:
    result = await _analytics(ctx).territory_care_gaps(
        tenant=ctx.tenant,
        competence=args.competence,
        care_line=args.care_line,
        correlation_id=ctx.correlation_id,
    )
    return TerritoryCareGapsOutput(rows=result.rows, truncated=result.truncated)


BI_TOOLS: tuple[tuple[str, str, type[BaseModel], type[BaseModel], Any], ...] = (
    (
        "bi.get_indicator_series",
        "Série mensal (município) de indicadores da whitelist em agg_indicadores_mensais: valor, "
        "meta, direção, situação e supressão (n<5 → nulo). Sem numerador/denominador.",
        IndicatorSeriesInput,
        IndicatorSeriesOutput,
        _indicator_series,
    ),
    (
        "bi.get_unit_indicators",
        "Indicadores por unidade (CNES) numa competência em agg_indicadores_mensais + nome da "
        "unidade (dim_health_unit). Células suprimidas vêm nulas.",
        UnitIndicatorsInput,
        UnitIndicatorsOutput,
        _unit_indicators,
    ),
    (
        "bi.get_territory_care_gaps",
        "Resolução de lacunas de cuidado por território (unidade × equipe INE × linha de cuidado) "
        "em agg_care_gaps_monthly. Células suprimidas vêm nulas.",
        TerritoryCareGapsInput,
        TerritoryCareGapsOutput,
        _territory_care_gaps,
    ),
)


def register_bi_tools(reg: ToolRegistry) -> ToolRegistry:
    for name, description, input_model, output_model, handler in BI_TOOLS:
        reg.register(
            ToolSpec(
                name=name,
                description=description,
                input_model=input_model,
                output_model=output_model,
                risk="low",
                action_class="auto",
                scope=BI_SCOPE,
                handler=handler,
                kind="read",
                owner=BI_OWNER,
                data_layer="aggregated",
            )
        )
    return reg
