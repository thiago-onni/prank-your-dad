"""Verificação numérica pós-geração do agente de BI (``bi_output_guardrails_v1``).

Todo número citado em texto livre pela saída deve vir dos dados consultados (contexto minimizado
que o LLM recebeu). Aceita a mesma grandeza com arredondamento compatível com as casas decimais
citadas e a forma percentual (proporção × 100). Formatos pt-BR (``18,3``; ``1.234,5``) e en
(``18.3``) são considerados. Números do campo ``question`` (texto do usuário) **não** contam como
fonte.
"""

from __future__ import annotations

import math
import re
from collections.abc import Iterable, Iterator
from typing import Any

# Número "solto": não colado a letra/dígito/sublinhado à esquerda (evita P50, _30D, ibge_).
_NUMBER_RE = re.compile(r"(?<![\w])[-+−]?\d+(?:[.,]\d+)*")
_COMPETENCE_RE = re.compile(r"^(19|20)\d{2}(0[1-9]|1[0-2])$")

EXCLUDED_SOURCE_KEYS = frozenset({"question"})


def _interpretations(token: str) -> list[tuple[float, int]]:
    """Possíveis leituras (valor, casas decimais) de um número citado."""
    sign = -1.0 if token[:1] in {"-", "−"} else 1.0
    body = token.lstrip("+-−")
    candidates: list[tuple[float, int]] = []

    def add(text: str, decimals: int) -> None:
        try:
            value = float(text)
        except ValueError:
            return
        if math.isfinite(value):
            candidates.append((sign * value, decimals))

    if "," not in body and "." not in body:
        add(body, 0)
        return candidates
    # pt-BR: "." milhar, "," decimal
    if "," in body:
        int_part, _, dec = body.rpartition(",")
        add(int_part.replace(".", "") + "." + dec, len(dec))
    else:
        # só pontos: decimal en ("18.3") ou milhar pt-BR ("1.234")
        int_part, _, dec = body.rpartition(".")
        add(int_part.replace(".", "") + "." + dec, len(dec))
        if all(len(g) == 3 for g in body.split(".")[1:]):
            add(body.replace(".", ""), 0)
    # en com milhar: "1,234.5"
    if "," in body and "." in body and body.rfind(".") > body.rfind(","):
        int_part, _, dec = body.rpartition(".")
        add(int_part.replace(",", "") + "." + dec, len(dec))
    return candidates


def extract_numbers(text: str) -> list[str]:
    return [m.group(0) for m in _NUMBER_RE.finditer(text)]


def _walk(node: Any, key: str | None = None) -> Iterator[tuple[str | None, Any]]:
    if isinstance(node, dict):
        for k, v in node.items():
            if k in EXCLUDED_SOURCE_KEYS:
                continue
            yield from _walk(v, k)
    elif isinstance(node, list):
        for item in node:
            yield from _walk(item, key)
    else:
        yield key, node


def allowed_numbers(context: dict[str, Any]) -> set[float]:
    """Números que a saída pode citar: folhas numéricas do contexto, ×100, e números em textos
    de origem (títulos, competências — também ano e mês)."""
    allowed: set[float] = set()
    for _key, value in _walk(context):
        if isinstance(value, bool) or value is None:
            continue
        if isinstance(value, int | float):
            if math.isfinite(float(value)):
                allowed.add(float(value))
                allowed.add(round(float(value) * 100, 10))
            continue
        if isinstance(value, str):
            if _COMPETENCE_RE.fullmatch(value):
                allowed.update({float(value), float(value[:4]), float(value[4:])})
            for token in extract_numbers(value):
                for number, _dec in _interpretations(token):
                    allowed.add(number)
    return allowed


def number_is_sourced(token: str, allowed: Iterable[float]) -> bool:
    values = list(allowed)
    for number, decimals in _interpretations(token):
        tolerance = 0.5 * 10 ** (-decimals) + 1e-9
        for source in values:
            if abs(abs(number) - abs(source)) <= tolerance:
                return True
    return False


def unsourced_numbers(texts: Iterable[str], allowed: set[float]) -> list[str]:
    missing: list[str] = []
    for text in texts:
        for token in extract_numbers(text):
            if not number_is_sourced(token, allowed) and token not in missing:
                missing.append(token)
    return missing


def matches_source(cited: float | None, source: float | None) -> bool:
    """Número em campo estruturado: igual à fonte ou à fonte arredondada nas casas citadas."""
    if cited is None or source is None:
        return cited is None and source is None
    if abs(cited - source) <= 1e-9:
        return True
    text = repr(float(cited))
    decimals = 0 if "e" in text else len(text.split(".")[1].rstrip("0")) if "." in text else 0
    decimals = min(decimals, 6)
    return abs(round(source, decimals) - cited) <= 1e-9
