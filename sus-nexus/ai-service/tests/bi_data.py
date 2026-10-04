"""Dados agregados sintéticos (shape de ``marts_aggregated``) e um Trino falso via respx.

O Trino falso interpreta o ``EXECUTE IMMEDIATE '<template>' USING <literais>`` enviado pelo
``TrinoHttpClient``: identifica o template pela tabela/nível, lê os parâmetros (o tenant inclusive)
e responde em duas páginas (``nextUri``), como a API real.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

import httpx

TENANT = "ibge_3143302"
OTHER_TENANT = "ibge_3106200"
COMPETENCE = "202609"
WINDOW = ["202604", "202605", "202606", "202607", "202608", "202609"]
TRINO_URL = "http://trino.test:8080"

UNIT_A, UNIT_B, UNIT_C, UNIT_D = "2143456", "2143457", "2143458", "2143459"
HOSPITAL = "7654321"
TEAM_A, TEAM_B, TEAM_C = "0001234567", "0001234568", "0001234569"
UNIT_NAMES = {
    UNIT_A: "UBS Centro",
    UNIT_B: "UBS Vila Nova",
    UNIT_C: "UBS Rural Lagoa",
    UNIT_D: "UBS Maria da Glória",
    HOSPITAL: "Hospital Municipal",
}
TARGETS = {
    "AGE_ABSENTEISMO": (0.15, "menor_melhor", "proporcao"),
    "REG_SLA_CUMPRIDO": (0.80, "maior_melhor", "proporcao"),
    "HOS_REINTERNACAO_30D": (0.10, "menor_melhor", "proporcao"),
    "REG_ESPERA_P50_DIAS": (30.0, "menor_melhor", "dias"),
    "EXA_CICLO_COMPLETO": (0.60, "maior_melhor", "proporcao"),
    "CUI_LACUNAS_RESOLVIDAS": (0.60, "maior_melhor", "proporcao"),
}

# Série municipal: None = célula suprimida (n < 5).
MUNICIPAL_SERIES: dict[str, list[float | None]] = {
    "AGE_ABSENTEISMO": [0.12, 0.13, 0.15, 0.16, 0.17, 0.1834],  # fora da meta, piora
    "REG_SLA_CUMPRIDO": [0.70, 0.72, 0.74, 0.75, 0.77, 0.78],  # fora da meta, melhora
    "HOS_REINTERNACAO_30D": [0.081, 0.079, 0.08, 0.082, 0.078, 0.0805],  # na meta, estável
    "REG_ESPERA_P50_DIAS": [41.5, 40.0, 38.5, 37.0, 35.5, 34.0],  # fora da meta, melhora
    "EXA_CICLO_COMPLETO": [0.55, 0.57, None, None, None, None],  # suprimido → indeterminado
    "CUI_LACUNAS_RESOLVIDAS": [0.61, 0.62, 0.63, 0.64, 0.64, 0.65],  # na meta, melhora
}

# Unidade × indicador na competência (None = suprimida).
UNIT_VALUES: dict[str, dict[str, float | None]] = {
    "AGE_ABSENTEISMO": {UNIT_A: 0.25, UNIT_B: 0.10, UNIT_C: None, UNIT_D: 0.16},
    "REG_SLA_CUMPRIDO": {UNIT_A: 0.9, UNIT_B: 0.6},
    "HOS_REINTERNACAO_30D": {HOSPITAL: 0.08},
    "EXA_CICLO_COMPLETO": {UNIT_A: None, UNIT_B: None},
}


def _on_target(code: str, value: float | None) -> bool | None:
    if value is None:
        return None
    target, direction, _unit = TARGETS[code]
    return value >= target if direction == "maior_melhor" else value <= target


def indicator_rows(tenant: str = TENANT, scale: float = 1.0) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for code, values in MUNICIPAL_SERIES.items():
        target, direction, unit = TARGETS[code]
        for comp, value in zip(WINDOW, values, strict=True):
            v = None if value is None else round(value * scale, 4)
            rows.append(
                {
                    "indicator_code": code,
                    "tenant_id": tenant,
                    "competence": comp,
                    "aggregation_level": "municipio",
                    "health_unit_cnes": None,
                    "indicator_value": v,
                    "numerator": None if v is None else 999,  # nunca deve chegar ao agente
                    "denominator": None if v is None else 1234,
                    "target_value": target,
                    "direction": direction,
                    "indicator_unit": unit,
                    "is_on_target": _on_target(code, v),
                    "is_suppressed": value is None,
                }
            )
    for code, units in UNIT_VALUES.items():
        target, direction, unit = TARGETS[code]
        for cnes, value in units.items():
            v = None if value is None else round(value * scale, 4)
            rows.append(
                {
                    "indicator_code": code,
                    "tenant_id": tenant,
                    "competence": COMPETENCE,
                    "aggregation_level": "unidade",
                    "health_unit_cnes": cnes,
                    "health_unit_name": UNIT_NAMES[cnes],
                    "unit_role": "hospital" if cnes == HOSPITAL else "aps",
                    "indicator_value": v,
                    "target_value": target,
                    "direction": direction,
                    "indicator_unit": unit,
                    "is_on_target": _on_target(code, v),
                    "is_suppressed": value is None,
                }
            )
    return rows


def care_gap_rows(tenant: str = TENANT) -> list[dict[str, Any]]:
    base = {"tenant_id": tenant, "competence": COMPETENCE}
    return [
        {
            **base,
            "health_unit_cnes": UNIT_A,
            "team_ine": TEAM_A,
            "care_line": "hipertensao",
            "n_detected": 10,
            "resolution_rate": 0.8,
        },
        {
            **base,
            "health_unit_cnes": UNIT_B,
            "team_ine": TEAM_B,
            "care_line": "hipertensao",
            "n_detected": 12,
            "resolution_rate": 0.4,
        },
        {
            **base,
            "health_unit_cnes": UNIT_C,
            "team_ine": TEAM_C,
            "care_line": "hipertensao",
            "n_detected": None,
            "resolution_rate": None,
        },
        {
            **base,
            "health_unit_cnes": UNIT_A,
            "team_ine": TEAM_A,
            "care_line": "diabetes",
            "n_detected": 8,
            "resolution_rate": 0.5,
        },
        {
            **base,
            "health_unit_cnes": UNIT_B,
            "team_ine": TEAM_B,
            "care_line": "diabetes",
            "n_detected": 6,
            "resolution_rate": 0.0,
        },
    ]


def all_rows() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Dois municípios: o do token e outro com valores diferentes (isolamento de tenant)."""
    indicators = indicator_rows(TENANT) + indicator_rows(OTHER_TENANT, scale=0.5)
    gaps = care_gap_rows(TENANT) + [
        {**r, "resolution_rate": 0.99} for r in care_gap_rows(OTHER_TENANT)
    ]
    return indicators, gaps


# ---------------------------------------------------------------------------
# Trino falso (respx side effect)
# ---------------------------------------------------------------------------

_LITERAL_RE = re.compile(r"'((?:[^']|'')*)'")


def parse_statement(statement: str) -> tuple[str, list[str]]:
    """``EXECUTE IMMEDIATE '<tpl>' USING 'a', 'b'`` → (template, ['a', 'b'])."""
    assert statement.startswith("EXECUTE IMMEDIATE '"), statement[:60]
    match = _LITERAL_RE.match(statement, len("EXECUTE IMMEDIATE "))
    assert match is not None
    template = match.group(1).replace("''", "'")
    rest = statement[match.end() :]
    assert rest.startswith(" USING "), rest[:30]
    params = [m.group(1).replace("''", "'") for m in _LITERAL_RE.finditer(rest)]
    return template, params


@dataclass
class FakeTrino:
    indicators: list[dict[str, Any]] = field(default_factory=list)
    care_gaps: list[dict[str, Any]] = field(default_factory=list)
    statements: list[str] = field(default_factory=list)
    headers: list[dict[str, str]] = field(default_factory=list)
    pending: dict[str, dict[str, Any]] = field(default_factory=dict)
    fail_with: str | None = None

    def _rows(self, template: str, params: list[str]) -> tuple[list[str], list[list[Any]]]:
        tenant = params[0]
        if "agg_care_gaps_monthly" in template:
            competence = params[1]
            line = params[2] if len(params) > 2 else None
            cols = [
                "competence",
                "health_unit_cnes",
                "team_ine",
                "care_line",
                "resolution_rate",
                "is_suppressed",
            ]
            data = [
                [
                    r["competence"],
                    r["health_unit_cnes"],
                    r["team_ine"],
                    r["care_line"],
                    r["resolution_rate"],
                    r["n_detected"] is None,
                ]
                for r in self.care_gaps
                if r["tenant_id"] == tenant
                and r["competence"] == competence
                and (line is None or r["care_line"] == line)
            ]
            return cols, data
        if "aggregation_level = 'municipio'" in template:
            start, end, codes = params[1], params[2], set(params[3:])
            cols = [
                "indicator_code",
                "competence",
                "indicator_value",
                "target_value",
                "direction",
                "indicator_unit",
                "is_on_target",
                "is_suppressed",
            ]
            data = [
                [
                    r[c]
                    for c in (
                        "indicator_code",
                        "competence",
                        "indicator_value",
                        "target_value",
                        "direction",
                        "indicator_unit",
                        "is_on_target",
                        "is_suppressed",
                    )
                ]
                for r in self.indicators
                if r["tenant_id"] == tenant
                and r["aggregation_level"] == "municipio"
                and start <= r["competence"] <= end
                and r["indicator_code"] in codes
            ]
            return cols, data
        assert "aggregation_level = 'unidade'" in template
        competence, codes = params[1], set(params[2:])
        cols = [
            "indicator_code",
            "competence",
            "health_unit_cnes",
            "health_unit_name",
            "unit_role",
            "indicator_value",
            "target_value",
            "direction",
            "indicator_unit",
            "is_on_target",
            "is_suppressed",
        ]
        data = [
            [r.get(c) for c in cols]
            for r in self.indicators
            if r["tenant_id"] == tenant
            and r["aggregation_level"] == "unidade"
            and r["competence"] == competence
            and r["indicator_code"] in codes
        ]
        return cols, data

    def post(self, request: httpx.Request) -> httpx.Response:
        statement = request.content.decode("utf-8")
        self.statements.append(statement)
        self.headers.append(dict(request.headers))
        if self.fail_with:
            return httpx.Response(
                200, json={"id": "q0", "error": {"errorName": self.fail_with, "errorType": "X"}}
            )
        template, params = parse_statement(statement)
        cols, data = self._rows(template, params)
        qid = f"q{len(self.statements)}"
        self.pending[qid] = {"columns": cols, "data": data}
        # 1ª resposta: só nextUri (fila), como o Trino real.
        return httpx.Response(
            200,
            json={
                "id": qid,
                "nextUri": f"{TRINO_URL}/v1/statement/queued/{qid}/1",
                "stats": {"state": "QUEUED"},
            },
        )

    def get(self, request: httpx.Request) -> httpx.Response:
        parts = request.url.path.strip("/").split("/")
        qid, page = parts[-2], int(parts[-1])
        result = self.pending[qid]
        columns = [{"name": c, "type": "varchar"} for c in result["columns"]]
        half = len(result["data"]) // 2
        if page == 1:
            return httpx.Response(
                200,
                json={
                    "id": qid,
                    "columns": columns,
                    "data": result["data"][:half] or None,
                    "nextUri": f"{TRINO_URL}/v1/statement/executing/{qid}/2",
                    "stats": {"state": "RUNNING"},
                },
            )
        body: dict[str, Any] = {"id": qid, "columns": columns, "stats": {"state": "FINISHED"}}
        if result["data"][half:]:
            body["data"] = result["data"][half:]
        return httpx.Response(200, json=body)

    def mount(self, router: Any) -> None:
        router.post(f"{TRINO_URL}/v1/statement").mock(side_effect=self.post)
        router.get(url__regex=rf"^{re.escape(TRINO_URL)}/v1/statement/.*").mock(
            side_effect=self.get
        )


def output_from_fake(context_json: str) -> dict[str, Any]:
    """Saída válida do respondedor fake para um contexto (base de casos adversariais)."""
    from sus_nexus_ai.agents.bi_situation_analyst import fake_responder
    from sus_nexus_ai.llm.client import LLMMessage

    data: dict[str, Any] = json.loads(
        fake_responder([LLMMessage("user", f"<dados>\n{context_json}\n</dados>")])
    )
    return data
