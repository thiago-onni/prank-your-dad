"""Executor de ferramentas: único caminho de um agente até o mundo (AIA-001/006/009/012).

Antes de executar qualquer ferramenta:
1. ``KillSwitch.check`` (global/agente/tenant/ferramenta);
2. ``PolicyClient.decide`` (OPA) com identidade do agente e ferramentas concedidas;
3. classe ``requires_approval`` só executa com ``approved_by`` (identidade do aprovador);
4. validação de entrada/saída por Pydantic; identidade do agente via ``IdentityProvider``.
Toda chamada (executada, negada ou pendente) gera um ``ToolCallRecord`` sem PII.
"""

from __future__ import annotations

import time
from dataclasses import asdict, dataclass, field
from typing import Any

from pydantic import BaseModel, ValidationError

from sus_nexus_ai import metrics
from sus_nexus_ai.common import get_logger, new_id, sha256_hex, utcnow
from sus_nexus_ai.privacy.minimizer import mask_for_record
from sus_nexus_ai.security.identity import IdentityProvider
from sus_nexus_ai.security.kill_switch import KillSwitch, KillSwitchEngaged
from sus_nexus_ai.security.policy import (
    AgentIdentity,
    PolicyClient,
    PolicyDecision,
    PolicyInput,
    PolicyUnavailable,
)
from sus_nexus_ai.tools.core_client import CoreClient
from sus_nexus_ai.tools.registry import ToolContext, ToolNotRegistered, ToolRegistry, ToolSpec

log = get_logger(__name__)


@dataclass
class ToolCallRecord:
    tool: str
    status: str  # executed | denied | requires_approval | error
    args_masked: dict[str, Any]
    decision: dict[str, Any] = field(default_factory=dict)
    result_hash: str | None = None
    error: str | None = None
    approved_by: str | None = None
    called_at: str = field(default_factory=lambda: utcnow().isoformat())
    duration_ms: int = 0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class ToolDenied(Exception):
    def __init__(self, tool: str, reasons: list[str], record: ToolCallRecord) -> None:
        self.tool = tool
        self.reasons = reasons
        self.record = record
        super().__init__(f"ferramenta negada: {tool} ({', '.join(reasons)})")


class ApprovalRequired(Exception):
    def __init__(self, tool: str, decision: PolicyDecision, record: ToolCallRecord) -> None:
        self.tool = tool
        self.decision = decision
        self.record = record
        super().__init__(f"ferramenta exige aprovação humana: {tool}")


class ToolExecutionError(Exception):
    def __init__(self, tool: str, error: str, record: ToolCallRecord) -> None:
        self.tool = tool
        self.record = record
        super().__init__(f"erro ao executar {tool}: {error}")


@dataclass
class ToolCallResult:
    output: BaseModel
    record: ToolCallRecord


class ToolExecutor:
    def __init__(
        self,
        registry: ToolRegistry,
        policy: PolicyClient,
        kill_switch: KillSwitch,
        identity: IdentityProvider,
        core: CoreClient,
    ) -> None:
        self.registry = registry
        self.policy = policy
        self.kill_switch = kill_switch
        self.identity = identity
        self.core = core

    @staticmethod
    def _mask(args: dict[str, Any]) -> dict[str, Any]:
        masked = mask_for_record(args)
        return masked if isinstance(masked, dict) else {"_": masked}

    def _deny(
        self, agent: AgentIdentity, tool: str, reasons: list[str], record: ToolCallRecord
    ) -> ToolDenied:
        record.status = "denied"
        metrics.agent_tool_call_total.labels(agent_id=agent.id, tool=tool, status="denied").inc()
        metrics.agent_tool_call_denied_total.labels(
            agent_id=agent.id, tool=tool, reason=reasons[0] if reasons else "unknown"
        ).inc()
        log.warning("tool.denied", agent_id=agent.id, tool=tool, reasons=reasons)
        return ToolDenied(tool, reasons, record)

    async def authorize(
        self, agent: AgentIdentity, tenant: str, spec: ToolSpec, record: ToolCallRecord
    ) -> PolicyDecision:
        try:
            self.kill_switch.check(agent.id, tenant, spec.name)
        except KillSwitchEngaged as exc:
            reason = f"kill_switch:{exc.scope}"
            record.decision = {"allow": False, "reasons": [reason]}
            raise self._deny(agent, spec.name, [reason], record) from exc

        policy_input = PolicyInput(
            agent=agent,
            tool=spec.name,
            action_class_requested=spec.action_class,
            tenant=tenant,
            kill_switch=self.kill_switch.state(),
        )
        try:
            decision = await self.policy.decide(policy_input)
        except PolicyUnavailable as exc:
            record.decision = {"allow": False, "reasons": ["policy_unavailable"]}
            raise self._deny(agent, spec.name, ["policy_unavailable"], record) from exc
        record.decision = decision.model_dump()
        # Defesa em profundidade: mesmo que o OPA permita, forbidden do catálogo nunca executa.
        if spec.action_class == "forbidden" or decision.action_class == "forbidden":
            raise self._deny(agent, spec.name, ["action_class:forbidden"], record)
        if not decision.allow:
            raise self._deny(agent, spec.name, decision.reasons or ["denied"], record)
        return decision

    async def call(
        self,
        *,
        agent: AgentIdentity,
        tenant: str,
        tool_name: str,
        args: dict[str, Any],
        run_id: str,
        approved_by: str | None = None,
        on_behalf_of_token: str | None = None,
        correlation_id: str | None = None,
    ) -> ToolCallResult:
        record = ToolCallRecord(tool=tool_name, status="pending", args_masked=self._mask(args))
        started = time.perf_counter()
        try:
            spec = self.registry.get(tool_name)
        except ToolNotRegistered as exc:
            record.decision = {"allow": False, "reasons": ["tool_not_registered"]}
            raise self._deny(agent, tool_name, ["tool_not_registered"], record) from exc

        decision = await self.authorize(agent, tenant, spec, record)

        if decision.requires_approval and approved_by is None:
            record.status = "requires_approval"
            metrics.agent_tool_call_total.labels(
                agent_id=agent.id, tool=tool_name, status="requires_approval"
            ).inc()
            raise ApprovalRequired(tool_name, decision, record)

        try:
            parsed = spec.input_model.model_validate(args)
        except ValidationError as exc:
            record.status = "error"
            record.error = f"entrada inválida: {exc.error_count()} erro(s)"
            metrics.agent_tool_call_total.labels(
                agent_id=agent.id, tool=tool_name, status="error"
            ).inc()
            raise ToolExecutionError(tool_name, record.error, record) from exc

        token = await self.identity.token_for(agent.id, tenant, on_behalf_of_token)
        ctx = ToolContext(
            tenant=tenant,
            agent_id=agent.id,
            agent_version=agent.version,
            run_id=run_id,
            token=token,
            core=self.core,
            correlation_id=correlation_id or new_id("corr_"),
            approved_by=approved_by,
        )
        try:
            output = await spec.handler(ctx, parsed)
            validated = spec.output_model.model_validate(output.model_dump())
        except Exception as exc:
            record.status = "error"
            record.error = type(exc).__name__
            metrics.agent_tool_call_total.labels(
                agent_id=agent.id, tool=tool_name, status="error"
            ).inc()
            log.error("tool.error", agent_id=agent.id, tool=tool_name, error=type(exc).__name__)
            raise ToolExecutionError(tool_name, type(exc).__name__, record) from exc

        record.status = "executed"
        record.approved_by = approved_by
        record.result_hash = sha256_hex(validated.model_dump(mode="json"))
        record.duration_ms = int((time.perf_counter() - started) * 1000)
        metrics.agent_tool_call_total.labels(
            agent_id=agent.id, tool=tool_name, status="executed"
        ).inc()
        log.info(
            "tool.executed",
            agent_id=agent.id,
            tool=tool_name,
            run_id=run_id,
            approved=approved_by is not None,
        )
        return ToolCallResult(output=validated, record=record)
