"""Garantia: nenhum dado pessoal direto no ``agent_run``/``agent_approval`` persistidos."""

from __future__ import annotations

import json

from sus_nexus_ai.persistence.schemas import Trigger
from sus_nexus_ai.service import AIService
from tests.conftest import CASE_ID, PII_STRINGS, REQUEST_ID, TENANT


def _assert_no_pii(blob: str) -> None:
    lowered = blob.lower()
    for pii in PII_STRINGS:
        assert pii.lower() not in lowered, f"PII vazou no registro: {pii}"
    # CPF/CNS numéricos em qualquer formato
    digits_only = "".join(ch for ch in blob if ch.isdigit() or ch == " ")
    assert "12345678909" not in digits_only.replace(" ", "")
    assert "898001234567890" not in blob


async def test_agent_run_payload_has_no_pii_after_full_flow(service: AIService) -> None:
    run = await service.run_agent(
        "regulation_completeness",
        tenant=TENANT,
        trigger=Trigger(kind="user", ref="user:regulador-1"),
        input_data={"request_id": REQUEST_ID},
    )
    await service.approve_action(
        run.id,
        run.actions[0].id,
        approver="user:regulador-1",
        justification="Pendência confirmada.",
    )
    raw = service.repository.get_run_raw(run.id)
    assert raw is not None
    blob = json.dumps(raw, ensure_ascii=False)
    _assert_no_pii(blob)
    # o input bruto não é guardado: só hash + referência
    assert raw["input_ref"]["hash"] and raw["input_ref"]["ref"] == REQUEST_ID
    assert "input" not in raw
    ctx = raw["minimized_context"]["request"]
    assert ctx["requesting_professional_name"].startswith("[PESSOA_")
    assert ctx["patient_name"].startswith("[PESSOA_")
    assert "cpf" not in ctx and "cns" not in ctx and "phone" not in ctx and "email" not in ctx
    assert "[REMOVIDO]" in ctx["notes"]
    approvals = service.repository.list_approvals(status="approved")
    _assert_no_pii(json.dumps([a.model_dump(mode="json") for a in approvals], ensure_ascii=False))


async def test_prompt_sent_to_llm_has_no_pii(service: AIService) -> None:
    await service.run_agent(
        "mpi_duplicate_suggestion",
        tenant=TENANT,
        trigger=Trigger(kind="manual"),
        input_data={"case_id": CASE_ID},
    )
    calls = service.llm.calls  # type: ignore[attr-defined]
    assert calls, "LLM fake deveria ter sido chamado"
    for messages in calls:
        _assert_no_pii("\n".join(m.content for m in messages))
