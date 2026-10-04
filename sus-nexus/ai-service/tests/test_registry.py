from __future__ import annotations

import pytest
from pydantic import BaseModel

from sus_nexus_ai.agents.catalog import build_catalog
from sus_nexus_ai.tools.core_tools import build_default_registry
from sus_nexus_ai.tools.registry import ToolNotRegistered, ToolSpec

EXPECTED = {
    "core.get_citizen_summary": ("auto", "low", "read"),
    "core.get_regulation_request": ("auto", "low", "read"),
    "core.get_exam_order": ("auto", "low", "read"),
    "core.get_merge_case": ("auto", "low", "read"),
    "core.list_merge_case": ("auto", "low", "read"),
    "core.create_task": ("auto", "low", "write"),
    "core.create_pending_issue": ("requires_approval", "medium", "write"),
    "communication.request_message": ("requires_approval", "medium", "write"),
    "regulation.change_priority": ("forbidden", "high", "write"),
    "regulation.decide": ("forbidden", "high", "write"),
    "production.transmit": ("forbidden", "high", "write"),
    "mpi.merge": ("forbidden", "high", "write"),
}


def test_default_registry_catalog() -> None:
    reg = build_default_registry()
    assert reg.names() == sorted(EXPECTED)
    for name, (action_class, risk, kind) in EXPECTED.items():
        spec = reg.get(name)
        assert (spec.action_class, spec.risk, spec.kind) == (action_class, risk, kind), name
        assert spec.scope and spec.description
        described = spec.describe()
        assert "input_schema" in described and "output_schema" in described
    # Fase 2: regulação e exames são reais; só comunicação continua stub.
    stubs = {spec.name for spec in reg.list() if spec.stub}
    assert stubs == {"communication.request_message"}
    with pytest.raises(ToolNotRegistered):
        reg.get("nope")


def test_opa_catalog_lists_every_registered_tool() -> None:
    """``policies/data/agent_tools.json`` (OPA) deve conhecer todas as ferramentas do registro."""
    import json
    from pathlib import Path

    catalog_path = Path(__file__).resolve().parents[2] / "policies" / "data" / "agent_tools.json"
    if not catalog_path.exists():  # pragma: no cover - fora do monorepo
        pytest.skip("policies/ não disponível")
    catalog = json.loads(catalog_path.read_text("utf-8"))["agent_tools"]["tools"]
    reg = build_default_registry()
    for spec in reg.list():
        assert spec.name in catalog, f"{spec.name} ausente em policies/data/agent_tools.json"
        assert catalog[spec.name]["action_class"] == spec.action_class, spec.name


def test_duplicate_registration_is_rejected() -> None:
    reg = build_default_registry()

    class In(BaseModel):
        x: int

    async def handler(ctx: object, args: In) -> In:
        return args

    with pytest.raises(ValueError, match="já registrada"):
        reg.register(
            ToolSpec(
                name="core.create_task",
                description="dup",
                input_model=In,
                output_model=In,
                risk="low",
                action_class="auto",
                scope="x",
                handler=handler,
            )
        )


def test_agents_only_reference_registered_tools_and_never_forbidden_ones() -> None:
    reg = build_default_registry()
    for definition in build_catalog().values():
        for tool in definition.tools:
            assert tool in reg, f"{definition.id} usa ferramenta não registrada: {tool}"
            assert reg.get(tool).action_class != "forbidden", (
                f"{definition.id} concede ferramenta proibida"
            )
        assert definition.prompt_path().exists(), definition.prompt_path()
        assert definition.system_prompt()
