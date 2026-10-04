"""Cliente de política de agentes (OPA) — AIA-001/004/012.

Contrato (``POST {opa_url}/v1/data/sus/agents/decision``)::

    input: {"agent": {"id", "version", "tools_granted": [...]},
            "tool": "...", "action_class_requested": "auto|requires_approval|forbidden",
            "tenant": "...",
            "kill_switch": {"global": bool, "agents": [], "tools": [], "tenants": []}}
    result: {"allow": bool, "action_class": "...", "requires_approval": bool, "reasons": [...]}

``AgentPolicyClient`` fala com o OPA via HTTP com cache curto; ``LocalPolicyEvaluator``
implementa a mesma lógica em memória (dev/testes) e serve de referência para o Rego.
"""

from __future__ import annotations

import time
from typing import Literal, Protocol

import httpx
from pydantic import BaseModel, Field

from sus_nexus_ai.common import get_logger, sha256_hex
from sus_nexus_ai.security.kill_switch import KillSwitchState

log = get_logger(__name__)

ActionClass = Literal["auto", "requires_approval", "forbidden"]


class AgentIdentity(BaseModel):
    id: str
    version: str
    tools_granted: list[str] = Field(default_factory=list)


class PolicyInput(BaseModel):
    agent: AgentIdentity
    tool: str
    action_class_requested: ActionClass
    tenant: str
    kill_switch: KillSwitchState

    def to_opa(self) -> dict[str, object]:
        data = self.model_dump()
        data["kill_switch"] = self.kill_switch.to_opa()
        return {"input": data}

    def cache_key(self) -> str:
        return sha256_hex(self.to_opa())


class PolicyDecision(BaseModel):
    allow: bool
    action_class: ActionClass
    requires_approval: bool = False
    reasons: list[str] = Field(default_factory=list)


class PolicyClient(Protocol):
    async def decide(self, policy_input: PolicyInput) -> PolicyDecision: ...


class PolicyUnavailable(Exception):
    """OPA inacessível ou resposta inválida → fail-closed (nega)."""


def evaluate_locally(policy_input: PolicyInput) -> PolicyDecision:
    """Lógica de referência (espelho de ``policies/agents.rego``)."""
    reasons: list[str] = []
    ks = policy_input.kill_switch
    agent = policy_input.agent
    requested = policy_input.action_class_requested

    if ks.global_:
        reasons.append("kill_switch:global")
    if agent.id in ks.agents:
        reasons.append(f"kill_switch:agent:{agent.id}")
    if policy_input.tool in ks.tools:
        reasons.append(f"kill_switch:tool:{policy_input.tool}")
    if policy_input.tenant in ks.tenants:
        reasons.append(f"kill_switch:tenant:{policy_input.tenant}")
    if requested == "forbidden":
        reasons.append("action_class:forbidden")
    if policy_input.tool not in agent.tools_granted:
        reasons.append("tool_not_granted")

    if reasons:
        return PolicyDecision(
            allow=False,
            action_class="forbidden" if requested == "forbidden" else requested,
            requires_approval=False,
            reasons=reasons,
        )
    if requested == "requires_approval":
        return PolicyDecision(
            allow=True, action_class="requires_approval", requires_approval=True, reasons=["ok"]
        )
    return PolicyDecision(allow=True, action_class="auto", requires_approval=False, reasons=["ok"])


class LocalPolicyEvaluator:
    async def decide(self, policy_input: PolicyInput) -> PolicyDecision:
        return evaluate_locally(policy_input)


class AgentPolicyClient:
    """Cliente HTTP do OPA com cache curto (TTL) por entrada canônica."""

    def __init__(
        self,
        opa_url: str,
        decision_path: str = "/v1/data/sus/agents/decision",
        cache_ttl_seconds: float = 5.0,
        timeout_seconds: float = 2.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._url = opa_url.rstrip("/") + decision_path
        self._ttl = cache_ttl_seconds
        self._client = client or httpx.AsyncClient(timeout=timeout_seconds)
        self._cache: dict[str, tuple[float, PolicyDecision]] = {}

    def clear_cache(self) -> None:
        self._cache.clear()

    async def decide(self, policy_input: PolicyInput) -> PolicyDecision:
        key = policy_input.cache_key()
        now = time.monotonic()
        cached = self._cache.get(key)
        if cached and cached[0] > now:
            return cached[1]
        try:
            resp = await self._client.post(self._url, json=policy_input.to_opa())
            resp.raise_for_status()
            body = resp.json()
            result = body.get("result")
            if not isinstance(result, dict):
                raise PolicyUnavailable("OPA sem 'result' (política não carregada?)")
            decision = PolicyDecision.model_validate(result)
        except (httpx.HTTPError, ValueError) as exc:
            log.error("policy.opa_error", error=str(exc))
            raise PolicyUnavailable(str(exc)) from exc
        if self._ttl > 0:
            self._cache[key] = (now + self._ttl, decision)
        return decision

    async def aclose(self) -> None:
        await self._client.aclose()
