"""Kill switch (AIA-009): bloqueio por agente, ferramenta e tenant, ou global.

Fontes combinadas (união): arquivo JSON (relido quando muda), variável de ambiente
``AI_KILL_SWITCH`` e estado administrativo definido via ``POST /admin/kill-switch``.
É verificado antes de cada execução de agente e antes de cada chamada de ferramenta.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from pydantic import BaseModel, Field

from sus_nexus_ai.common import get_logger

log = get_logger(__name__)


class KillSwitchState(BaseModel):
    """Estado serializável (também é o contrato enviado ao OPA)."""

    global_: bool = Field(default=False, alias="global")
    agents: list[str] = Field(default_factory=list)
    tools: list[str] = Field(default_factory=list)
    tenants: list[str] = Field(default_factory=list)

    model_config = {"populate_by_name": True}

    def merge(self, other: KillSwitchState) -> KillSwitchState:
        return KillSwitchState(
            global_=self.global_ or other.global_,
            agents=sorted(set(self.agents) | set(other.agents)),
            tools=sorted(set(self.tools) | set(other.tools)),
            tenants=sorted(set(self.tenants) | set(other.tenants)),
        )

    def to_opa(self) -> dict[str, object]:
        return self.model_dump(by_alias=True)


class KillSwitchEngaged(Exception):
    def __init__(self, scope: str, value: str | None = None) -> None:
        self.scope = scope
        self.value = value
        detail = f"kill switch ativo ({scope}{': ' + value if value else ''})"
        super().__init__(detail)


class KillSwitch:
    def __init__(self, file_path: Path | None = None, env_var: str = "AI_KILL_SWITCH") -> None:
        self._file_path = file_path
        self._env_var = env_var
        self._admin_state = KillSwitchState()
        self._file_state = KillSwitchState()
        self._file_mtime: float | None = None

    # ---- fontes ----
    def _load_file(self) -> None:
        if self._file_path is None or not self._file_path.exists():
            self._file_state = KillSwitchState()
            self._file_mtime = None
            return
        mtime = self._file_path.stat().st_mtime
        if mtime == self._file_mtime:
            return
        try:
            self._file_state = KillSwitchState.model_validate(
                json.loads(self._file_path.read_text("utf-8"))
            )
            self._file_mtime = mtime
            log.info("kill_switch.file_loaded", path=str(self._file_path))
        except (ValueError, OSError) as exc:  # arquivo inválido → fail-closed global
            log.error("kill_switch.file_invalid", path=str(self._file_path), error=str(exc))
            self._file_state = KillSwitchState(global_=True)

    def _load_env(self) -> KillSwitchState:
        raw = os.environ.get(self._env_var)
        if not raw:
            return KillSwitchState()
        try:
            return KillSwitchState.model_validate(json.loads(raw))
        except ValueError as exc:
            log.error("kill_switch.env_invalid", error=str(exc))
            return KillSwitchState(global_=True)

    # ---- API ----
    def state(self) -> KillSwitchState:
        self._load_file()
        return self._file_state.merge(self._load_env()).merge(self._admin_state)

    def set_admin_state(self, state: KillSwitchState) -> KillSwitchState:
        self._admin_state = state
        log.warning("kill_switch.admin_updated", state=state.to_opa())
        return self.state()

    def admin_state(self) -> KillSwitchState:
        return self._admin_state

    def check(self, agent_id: str, tenant: str, tool: str | None = None) -> None:
        """Levanta ``KillSwitchEngaged`` se qualquer dimensão estiver bloqueada."""
        st = self.state()
        if st.global_:
            raise KillSwitchEngaged("global")
        if agent_id in st.agents:
            raise KillSwitchEngaged("agent", agent_id)
        if tenant in st.tenants:
            raise KillSwitchEngaged("tenant", tenant)
        if tool is not None and tool in st.tools:
            raise KillSwitchEngaged("tool", tool)
