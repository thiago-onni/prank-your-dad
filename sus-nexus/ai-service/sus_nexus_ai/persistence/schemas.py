"""Registros de execução (AIA-002/010) e aprovação (AIA-005) — modelos Pydantic.

``AgentRunRecord`` NUNCA contém o input bruto: guarda ``input_ref`` (hash + referência),
o contexto minimizado e argumentos de ferramentas mascarados.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

from sus_nexus_ai.security.policy import ActionClass

RunStatus = Literal["running", "completed", "invalid_output", "failed", "denied"]
ValidationStatus = Literal["not_run", "valid", "invalid_output"]
ActionStatus = Literal[
    "executed", "pending_approval", "approved", "rejected", "blocked", "denied", "failed"
]
ApprovalStatus = Literal["pending", "approved", "rejected"]


class Trigger(BaseModel):
    kind: Literal["event", "user", "workflow", "eval", "manual"] = "manual"
    ref: str | None = None
    """event_id, workflow_id ou identificador do usuário solicitante (nunca PII)."""
    on_behalf_of: str | None = None


class InputRef(BaseModel):
    hash: str
    ref: str | None = None
    kind: str | None = None


class AgentAction(BaseModel):
    id: str
    tool: str
    action_class: ActionClass
    status: ActionStatus
    args: dict[str, Any] = Field(default_factory=dict)
    """Argumentos mascarados (sem PII) — são os que serão executados após aprovação."""
    result_hash: str | None = None
    approver: str | None = None
    justification: str | None = None
    decided_at: datetime | None = None
    error: str | None = None
    reasons: list[str] = Field(default_factory=list)


class AgentRunRecord(BaseModel):
    id: str
    agent_id: str
    agent_version: str
    prompt_version: str
    model: str
    model_params: dict[str, Any] = Field(default_factory=dict)
    rule_versions: dict[str, str] = Field(default_factory=dict)
    tenant: str
    trigger: Trigger
    input_ref: InputRef
    minimized_context: dict[str, Any] = Field(default_factory=dict)
    tools_called: list[dict[str, Any]] = Field(default_factory=list)
    output: dict[str, Any] | None = None
    validation_status: ValidationStatus = "not_run"
    validation_attempts: int = 0
    status: RunStatus = "running"
    actions: list[AgentAction] = Field(default_factory=list)
    started_at: datetime
    finished_at: datetime | None = None
    cost_estimate: float = 0.0
    tokens_in: int = 0
    tokens_out: int = 0
    error: str | None = None

    def action(self, action_id: str) -> AgentAction | None:
        return next((a for a in self.actions if a.id == action_id), None)


class AgentApproval(BaseModel):
    id: str
    run_id: str
    action_id: str
    agent_id: str
    tenant: str
    tool: str
    status: ApprovalStatus = "pending"
    requested_at: datetime
    decided_at: datetime | None = None
    approver: str | None = None
    justification: str | None = None
