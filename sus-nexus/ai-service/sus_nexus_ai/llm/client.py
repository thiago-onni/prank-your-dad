"""Clientes de LLM: interface, LiteLLM e Fake determinístico."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

from sus_nexus_ai.common import get_logger

log = get_logger(__name__)

Role = Literal["system", "user", "assistant"]


@dataclass(frozen=True)
class LLMMessage:
    role: Role
    content: str

    def to_dict(self) -> dict[str, str]:
        return {"role": self.role, "content": self.content}


@dataclass
class LLMUsage:
    input_tokens: int = 0
    output_tokens: int = 0


@dataclass
class LLMResponse:
    text: str
    model: str
    usage: LLMUsage = field(default_factory=LLMUsage)
    params: dict[str, Any] = field(default_factory=dict)


class LLMClient(Protocol):
    model: str

    async def complete(self, messages: list[LLMMessage], *, agent_id: str) -> LLMResponse: ...


FakeResponder = Callable[[list[LLMMessage]], str]


class FakeLLMClient:
    """Respostas determinísticas por agente (``responders``) ou roteiro (``scripted``).

    * ``scripted``: fila de respostas consumida em ordem (prioridade sobre responders);
    * ``responders[agent_id]``: função que lê as mensagens e devolve o texto.
    """

    model = "fake/deterministic-v1"

    def __init__(
        self,
        responders: dict[str, FakeResponder] | None = None,
        scripted: list[str] | None = None,
    ) -> None:
        self.responders: dict[str, FakeResponder] = dict(responders or {})
        self.scripted: list[str] = list(scripted or [])
        self.calls: list[list[LLMMessage]] = []

    async def complete(self, messages: list[LLMMessage], *, agent_id: str) -> LLMResponse:
        self.calls.append(list(messages))
        if self.scripted:
            text = self.scripted.pop(0)
        elif agent_id in self.responders:
            text = self.responders[agent_id](messages)
        else:
            text = "{}"
        tokens_in = sum(len(m.content) for m in messages) // 4
        return LLMResponse(
            text=text,
            model=self.model,
            usage=LLMUsage(input_tokens=tokens_in, output_tokens=len(text) // 4),
            params={"temperature": 0.0, "provider": "fake"},
        )


class LiteLLMClient:
    """Chamada via LiteLLM (modelo por agente, sem retenção; ``mock_response`` opcional)."""

    def __init__(
        self,
        model: str,
        temperature: float = 0.0,
        max_tokens: int = 1500,
        api_base: str | None = None,
        api_key: str | None = None,
        mock_response: str | None = None,
        timeout_seconds: float = 60.0,
    ) -> None:
        self.model = model
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.api_base = api_base
        self.api_key = api_key
        self.mock_response = mock_response
        self.timeout_seconds = timeout_seconds

    async def complete(self, messages: list[LLMMessage], *, agent_id: str) -> LLMResponse:
        import litellm  # import tardio: biblioteca pesada

        kwargs: dict[str, Any] = {
            "model": self.model,
            "messages": [m.to_dict() for m in messages],
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
            "timeout": self.timeout_seconds,
            "response_format": {"type": "json_object"},
            # Langfuse (callback do LiteLLM, ver observability.py): agrupa as gerações por agente.
            "metadata": {
                "agent_id": agent_id,
                "trace_name": agent_id,
                "generation_name": f"{agent_id}.reason",
                "tags": [agent_id],
            },
        }
        if self.api_base:
            kwargs["api_base"] = self.api_base
        if self.api_key:
            kwargs["api_key"] = self.api_key
        if self.mock_response is not None:
            kwargs["mock_response"] = self.mock_response
        response = await litellm.acompletion(**kwargs)
        text = response.choices[0].message.content or ""
        usage = getattr(response, "usage", None)
        return LLMResponse(
            text=text,
            model=str(getattr(response, "model", self.model)),
            usage=LLMUsage(
                input_tokens=int(getattr(usage, "prompt_tokens", 0) or 0),
                output_tokens=int(getattr(usage, "completion_tokens", 0) or 0),
            ),
            params={
                "temperature": self.temperature,
                "max_tokens": self.max_tokens,
                "provider": "litellm",
            },
        )
