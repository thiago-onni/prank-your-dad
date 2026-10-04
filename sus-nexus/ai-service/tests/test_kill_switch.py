from __future__ import annotations

import json
from pathlib import Path

import pytest

from sus_nexus_ai.security.kill_switch import KillSwitch, KillSwitchEngaged, KillSwitchState


def test_file_env_and_admin_sources_are_merged(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    path = tmp_path / "kill.json"
    path.write_text(json.dumps({"global": False, "agents": ["a1"]}), "utf-8")
    monkeypatch.setenv("AI_KS_TEST", json.dumps({"tools": ["core.create_task"]}))
    ks = KillSwitch(file_path=path, env_var="AI_KS_TEST")
    ks.set_admin_state(KillSwitchState(tenants=["ibge_0000000"]))
    state = ks.state()
    assert state.agents == ["a1"]
    assert state.tools == ["core.create_task"]
    assert state.tenants == ["ibge_0000000"]
    assert state.global_ is False

    ks.check("a2", "ibge_3143302", "core.get_citizen_summary")  # permitido
    with pytest.raises(KillSwitchEngaged, match="agent"):
        ks.check("a1", "ibge_3143302")
    with pytest.raises(KillSwitchEngaged, match="tool"):
        ks.check("a2", "ibge_3143302", "core.create_task")
    with pytest.raises(KillSwitchEngaged, match="tenant"):
        ks.check("a2", "ibge_0000000")


def test_file_change_is_picked_up_and_invalid_file_fails_closed(tmp_path: Path) -> None:
    path = tmp_path / "kill.json"
    path.write_text("{}", "utf-8")
    ks = KillSwitch(file_path=path, env_var="AI_KS_UNSET")
    assert ks.state().global_ is False
    path.write_text("{not json", "utf-8")
    import os

    os.utime(path, (path.stat().st_atime, path.stat().st_mtime + 10))
    assert ks.state().global_ is True
    with pytest.raises(KillSwitchEngaged, match="global"):
        ks.check("any", "ibge_3143302")


def test_opa_contract_shape() -> None:
    assert KillSwitchState(global_=True).to_opa() == {
        "global": True,
        "agents": [],
        "tools": [],
        "tenants": [],
    }
    assert KillSwitchState.model_validate({"global": True}).global_ is True
