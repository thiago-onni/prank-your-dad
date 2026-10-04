"""O OpenAPI exportado em ``contracts/openapi/ai-service.yaml`` deve estar sincronizado."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from sus_nexus_ai.export_openapi import DEFAULT_OUTPUT, build_spec, main, render


def test_exported_spec_is_up_to_date() -> None:
    if not DEFAULT_OUTPUT.parent.exists():  # pragma: no cover - fora do monorepo
        pytest.skip("contracts/openapi não disponível")
    assert DEFAULT_OUTPUT.exists(), "rode: python -m sus_nexus_ai.export_openapi"
    assert DEFAULT_OUTPUT.read_text("utf-8") == render(build_spec()), (
        "contracts/openapi/ai-service.yaml desatualizado — "
        "rode python -m sus_nexus_ai.export_openapi"
    )


def test_spec_shape_for_type_generation() -> None:
    spec = build_spec()
    assert spec["openapi"].startswith("3.1")
    operation_ids = {
        op["operationId"] for methods in spec["paths"].values() for op in methods.values()
    }
    assert {
        "run_agent",
        "run_bi_situation_analyst",
        "get_run",
        "list_runs",
        "approve_action",
        "reject_action",
        "list_approvals",
        "list_agents",
        "list_tools",
        "get_kill_switch",
        "set_kill_switch",
        "health",
    } <= operation_ids
    schemas = spec["components"]["schemas"]
    for name in (
        "AgentRunRecord",
        "AgentAction",
        "AgentApproval",
        "RunRequest",
        "DecisionRequest",
        "BiSituationInput",
    ):
        assert name in schemas, name
    # o agente de BI não aceita município na requisição (vem do token)
    assert "tenant" not in schemas["BiSituationInput"]["properties"]
    assert schemas["BiSituationInput"]["additionalProperties"] is False
    assert set(schemas["AgentAction"]["properties"]["action_class"]["enum"]) == {
        "auto",
        "requires_approval",
        "forbidden",
    }


def test_cli_check_and_write(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    out = tmp_path / "ai-service.yaml"
    assert main(["--check", "--out", str(out)]) == 1
    assert main(["--out", str(out)]) == 0
    assert main(["--check", "--out", str(out)]) == 0
    loaded = yaml.safe_load(out.read_text("utf-8"))
    assert loaded["info"]["title"].startswith("SUS Nexus")
    assert "desatualizado" in capsys.readouterr().out
