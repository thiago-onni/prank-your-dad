from __future__ import annotations

from typing import Any

import pytest
from pydantic import BaseModel

from sus_nexus_ai.agents.catalog import build_catalog
from sus_nexus_ai.security.policy import AGENT_PROFILES
from sus_nexus_ai.tools.core_tools import build_default_registry
from sus_nexus_ai.tools.registry import ToolNotRegistered, ToolSpec

EXPECTED = {
    "bi.get_indicator_series": ("auto", "low", "read"),
    "bi.get_unit_indicators": ("auto", "low", "read"),
    "bi.get_territory_care_gaps": ("auto", "low", "read"),
    "core.get_citizen_summary": ("auto", "low", "read"),
    "core.get_regulation_request": ("auto", "low", "read"),
    "core.get_exam_order": ("auto", "low", "read"),
    "core.get_hospital_episode": ("auto", "low", "read"),
    "core.list_care_gaps": ("auto", "low", "read"),
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
        # Camada de dado (padrão "operational") e kind: o Rego usa ambos para perfis de agente.
        assert catalog[spec.name].get("data_layer", "operational") == spec.data_layer, spec.name
        if spec.data_layer == "aggregated":
            assert catalog[spec.name].get("kind") == spec.kind == "read", spec.name


def _catalog() -> dict[str, Any]:
    import json
    from pathlib import Path

    path = Path(__file__).resolve().parents[2] / "policies" / "data" / "agent_tools.json"
    if not path.exists():  # pragma: no cover - fora do monorepo
        pytest.skip("policies/ não disponível")
    data: dict[str, Any] = json.loads(path.read_text("utf-8"))["agent_tools"]
    return data


def test_opa_agent_profiles_match_python_profiles() -> None:
    """``agent_profiles`` (Rego) == ``AGENT_PROFILES`` (executor + rota dedicada)."""
    profiles = _catalog()["agent_profiles"]
    assert set(profiles) == set(AGENT_PROFILES)
    for agent_id, profile in AGENT_PROFILES.items():
        opa = profiles[agent_id]
        assert opa["allowed_data_layers"] == profile.allowed_data_layers, agent_id
        assert opa["read_only"] == profile.read_only, agent_id
        assert sorted(opa["invoker_roles"]) == sorted(profile.invoker_roles), agent_id
    assert AGENT_PROFILES["bi_situation_analyst"].invoker_roles == [
        "admin_municipal",
        "auditor",
        "gestor",
    ]


def test_profiled_agents_only_use_allowed_layers_and_reads() -> None:
    reg = build_default_registry()
    catalog = build_catalog()
    for agent_id, profile in AGENT_PROFILES.items():
        definition = catalog[agent_id]
        for tool in definition.tools:
            spec = reg.get(tool)
            assert spec.data_layer in profile.allowed_data_layers, (agent_id, tool)
            if profile.read_only:
                assert spec.kind == "read" and spec.action_class == "auto", (agent_id, tool)


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
