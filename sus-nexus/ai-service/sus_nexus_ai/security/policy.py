"""Cliente de política de agentes (OPA) — AIA-001/004/012.

Contrato (``POST {opa_url}/v1/data/sus/agents/decision``)::

    input: {"agent": {"id", "version", "tools_granted": [...]},
            "tool": "...", "action_class_requested": "auto|requires_approval|forbidden",
            "tenant": "...",
            "kill_switch": {"global": bool, "agents": [], "tools": [], "tenants": []}}
    result: {"allow": bool, "action_class": "...", "requires_approval": bool, "reasons": [...]}

Invocação humana de agentes com perfil (``POST {opa_url}/v1/data/sus/agents/invoke``)::

    input: {"agent": {"id"}, "subject": {"roles": [...], "tenant": "..."}, "tenant": "...",
            "kill_switch": {"global": bool, "agents": [], "tools": [], "tenants": []}}
    result: {"allow": bool, "reasons": [...], "policy_version": "..."}

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


class InvokeAgent(BaseModel):
    id: str


class InvokeSubject(BaseModel):
    roles: list[str] = Field(default_factory=list)
    tenant: str | None = None

    def to_opa(self) -> dict[str, object]:
        # tenant ausente não é enviado (a política nega com ``tenant_mismatch``)
        return self.model_dump(exclude_none=True)


class InvokeInput(BaseModel):
    """Entrada de ``data.sus.agents.invoke`` (quem invoca um agente com perfil)."""

    agent: InvokeAgent
    subject: InvokeSubject
    tenant: str
    kill_switch: KillSwitchState

    def to_opa(self) -> dict[str, object]:
        return {
            "input": {
                "agent": self.agent.model_dump(),
                "subject": self.subject.to_opa(),
                "tenant": self.tenant,
                "kill_switch": self.kill_switch.to_opa(),
            }
        }

    def cache_key(self) -> str:
        return sha256_hex(self.to_opa())


class InvokeDecision(BaseModel):
    allow: bool
    reasons: list[str] = Field(default_factory=list)
    policy_version: str | None = None


class AgentProfile(BaseModel):
    """Restrições adicionais por agente (espelho de ``agent_profiles`` em
    ``policies/data/agent_tools.json``; paridade em ``tests/test_registry.py``).

    * ``allowed_data_layers``: camadas de dado que as ferramentas do agente podem tocar;
    * ``read_only``: só ferramentas ``kind=read``;
    * ``invoker_roles``: papéis humanos que podem disparar o agente (``data.sus.agents.invoke``).
    """

    allowed_data_layers: list[str]
    read_only: bool = False
    invoker_roles: list[str] = Field(default_factory=list)


AGENT_PROFILES: dict[str, AgentProfile] = {
    "bi_situation_analyst": AgentProfile(
        allowed_data_layers=["aggregated"],
        read_only=True,
        invoker_roles=["admin_municipal", "auditor", "gestor"],
    ),
}


class PolicyClient(Protocol):
    async def decide(self, policy_input: PolicyInput) -> PolicyDecision: ...

    async def decide_invoke(self, invoke_input: InvokeInput) -> InvokeDecision: ...


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


def evaluate_invoke_locally(invoke_input: InvokeInput) -> InvokeDecision:
    """Espelho de ``data.sus.agents.invoke`` (mesmos motivos do Rego)."""
    profile = AGENT_PROFILES.get(invoke_input.agent.id)
    ks = invoke_input.kill_switch
    reasons: set[str] = set()
    if profile is None:
        reasons.add("agent_without_invocation_profile")
    elif not any(r in profile.invoker_roles for r in invoke_input.subject.roles):
        reasons.add("role_not_allowed_to_invoke")
    if not invoke_input.tenant or invoke_input.subject.tenant != invoke_input.tenant:
        reasons.add("tenant_mismatch")
    if ks.global_ or invoke_input.agent.id in ks.agents or invoke_input.tenant in ks.tenants:
        reasons.add("kill_switch")
    if reasons:
        return InvokeDecision(allow=False, reasons=sorted(reasons), policy_version="local")
    return InvokeDecision(allow=True, reasons=["granted"], policy_version="local")


class LocalPolicyEvaluator:
    async def decide(self, policy_input: PolicyInput) -> PolicyDecision:
        return evaluate_locally(policy_input)

    async def decide_invoke(self, invoke_input: InvokeInput) -> InvokeDecision:
        return evaluate_invoke_locally(invoke_input)


class AgentPolicyClient:
    """Cliente HTTP do OPA com cache curto (TTL) por entrada canônica."""

    def __init__(
        self,
        opa_url: str,
        decision_path: str = "/v1/data/sus/agents/decision",
        cache_ttl_seconds: float = 5.0,
        timeout_seconds: float = 2.0,
        client: httpx.AsyncClient | None = None,
        invoke_path: str = "/v1/data/sus/agents/invoke",
    ) -> None:
        self._url = opa_url.rstrip("/") + decision_path
        self._invoke_url = opa_url.rstrip("/") + invoke_path
        self._ttl = cache_ttl_seconds
        self._client = client or httpx.AsyncClient(timeout=timeout_seconds)
        self._cache: dict[str, tuple[float, PolicyDecision]] = {}
        self._invoke_cache: dict[str, tuple[float, InvokeDecision]] = {}

    def clear_cache(self) -> None:
        self._cache.clear()
        self._invoke_cache.clear()

    async def _query(self, url: str, payload: dict[str, object]) -> dict[str, object]:
        """POST ao OPA; falha de rede/HTTP ou corpo sem ``result`` → ``PolicyUnavailable``."""
        try:
            resp = await self._client.post(url, json=payload)
            resp.raise_for_status()
            body = resp.json()
            result = body.get("result") if isinstance(body, dict) else None
            if not isinstance(result, dict):
                raise PolicyUnavailable("OPA sem 'result' (política não carregada?)")
            return result
        except (httpx.HTTPError, ValueError) as exc:
            log.error("policy.opa_error", url=url, error=str(exc))
            raise PolicyUnavailable(str(exc)) from exc

    async def decide_invoke(self, invoke_input: InvokeInput) -> InvokeDecision:
        key = invoke_input.cache_key()
        now = time.monotonic()
        cached = self._invoke_cache.get(key)
        if cached and cached[0] > now:
            return cached[1]
        result = await self._query(self._invoke_url, invoke_input.to_opa())
        try:
            decision = InvokeDecision.model_validate(result)
        except ValueError as exc:
            log.error("policy.opa_invalid_invoke", error=str(exc))
            raise PolicyUnavailable(str(exc)) from exc
        if self._ttl > 0:
            self._invoke_cache[key] = (now + self._ttl, decision)
        return decision

    async def decide(self, policy_input: PolicyInput) -> PolicyDecision:
        key = policy_input.cache_key()
        now = time.monotonic()
        cached = self._cache.get(key)
        if cached and cached[0] > now:
            return cached[1]
        result = await self._query(self._url, policy_input.to_opa())
        try:
            decision = PolicyDecision.model_validate(result)
        except ValueError as exc:
            log.error("policy.opa_error", error=str(exc))
            raise PolicyUnavailable(str(exc)) from exc
        if self._ttl > 0:
            self._cache[key] = (now + self._ttl, decision)
        return decision

    async def aclose(self) -> None:
        await self._client.aclose()
