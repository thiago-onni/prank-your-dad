"""Saída estruturada (AIA-003): JSON → Pydantic estrito, com retries de correção limitados.

Falha após ``max_retries`` → ``InvalidOutputError`` e o run é descartado com
``status=invalid_output``.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Generic, TypeVar

from pydantic import BaseModel, ValidationError

from sus_nexus_ai.llm.client import LLMClient, LLMMessage, LLMResponse

T = TypeVar("T", bound=BaseModel)

_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL)


class InvalidOutputError(Exception):
    def __init__(self, attempts: int, last_error: str) -> None:
        self.attempts = attempts
        self.last_error = last_error
        super().__init__(f"saída inválida após {attempts} tentativa(s): {last_error}")


def extract_json(text: str) -> str:
    """Extrai o bloco JSON de uma resposta (tolera cercas ``` e texto ao redor)."""
    fenced = _FENCE_RE.search(text)
    if fenced:
        return fenced.group(1).strip()
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end > start:
        return text[start : end + 1]
    return text.strip()


def parse_structured(text: str, model: type[T]) -> T:
    """Parse estrito. Levanta ``ValueError`` (JSON) ou ``ValidationError`` (schema)."""
    raw = extract_json(text)
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("a resposta deve ser um objeto JSON")
    return model.model_validate(data, strict=False)


def correction_message(error: str, model: type[BaseModel]) -> LLMMessage:
    schema = json.dumps(model.model_json_schema(), ensure_ascii=False)
    return LLMMessage(
        role="user",
        content=(
            "Sua resposta anterior não é um JSON válido conforme o schema. "
            f"Erro: {error}. Responda SOMENTE com um objeto JSON que satisfaça este schema, "
            f"sem comentários nem texto adicional: {schema}"
        ),
    )


@dataclass
class StructuredResult(Generic[T]):
    output: T
    attempts: int
    responses: list[LLMResponse] = field(default_factory=list)


async def complete_structured(
    client: LLMClient,
    messages: list[LLMMessage],
    model: type[T],
    *,
    agent_id: str,
    max_retries: int = 2,
) -> StructuredResult[T]:
    conversation = list(messages)
    responses: list[LLMResponse] = []
    last_error = ""
    for attempt in range(1, max_retries + 2):
        response = await client.complete(conversation, agent_id=agent_id)
        responses.append(response)
        try:
            parsed = parse_structured(response.text, model)
        except (ValueError, ValidationError) as exc:
            last_error = _summarize_error(exc)
            conversation.append(LLMMessage(role="assistant", content=response.text))
            conversation.append(correction_message(last_error, model))
            continue
        return StructuredResult(output=parsed, attempts=attempt, responses=responses)
    raise InvalidOutputError(attempts=max_retries + 1, last_error=last_error)


def _summarize_error(exc: Exception) -> str:
    if isinstance(exc, ValidationError):
        parts = [f"{'.'.join(str(p) for p in e['loc'])}: {e['msg']}" for e in exc.errors()[:5]]
        return "; ".join(parts)
    return str(exc)[:200]
