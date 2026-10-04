from __future__ import annotations

import pytest
from pydantic import BaseModel, Field

from sus_nexus_ai.llm.client import FakeLLMClient, LLMMessage
from sus_nexus_ai.llm.structured import InvalidOutputError, complete_structured, parse_structured


class Out(BaseModel):
    verdict: str
    confidence: float = Field(ge=0, le=1)


def test_parse_tolerates_fences_and_surrounding_text() -> None:
    text = 'Claro! ```json\n{"verdict": "ok", "confidence": 0.5}\n``` fim.'
    assert parse_structured(text, Out).verdict == "ok"
    assert parse_structured('x {"verdict": "y", "confidence": 1} z', Out).confidence == 1.0


async def test_retry_with_correction_then_success() -> None:
    llm = FakeLLMClient(
        scripted=[
            "não é json",
            '{"verdict": "ok", "confidence": 2}',
            '{"verdict": "ok", "confidence": 0.9}',
        ]
    )
    result = await complete_structured(
        llm, [LLMMessage("user", "oi")], Out, agent_id="x", max_retries=2
    )
    assert result.attempts == 3
    assert result.output.confidence == 0.9
    # a 2ª chamada recebeu a resposta anterior + mensagem de correção com o schema
    second_call = llm.calls[1]
    assert second_call[-2].role == "assistant" and second_call[-1].role == "user"
    assert "schema" in second_call[-1].content.lower()


async def test_gives_up_after_max_retries() -> None:
    llm = FakeLLMClient(scripted=["a", "b", "c", "d"])
    with pytest.raises(InvalidOutputError) as exc:
        await complete_structured(llm, [LLMMessage("user", "oi")], Out, agent_id="x", max_retries=2)
    assert exc.value.attempts == 3
    assert len(llm.calls) == 3
