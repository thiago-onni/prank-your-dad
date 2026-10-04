"""Registro formal de ferramentas (CONVENTIONS › Python; AIA-004/006).

Cada ferramenta tem schema de entrada/saída (Pydantic), classificação de risco, classe de ação
(``auto`` | ``requires_approval`` | ``forbidden``), escopo OAuth e dono. Agentes só chamam
ferramentas registradas aqui, e sempre através de ``ToolExecutor`` (OPA + kill switch + registro).
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Literal

from pydantic import BaseModel

from sus_nexus_ai.security.policy import ActionClass

if TYPE_CHECKING:
    from sus_nexus_ai.security.identity import AgentToken
    from sus_nexus_ai.tools.analytics import AggregatedAnalytics
    from sus_nexus_ai.tools.core_client import CoreClient

Risk = Literal["low", "medium", "high"]
ToolKind = Literal["read", "write"]
DataLayer = Literal["operational", "aggregated"]
"""``operational``: API do core (dado por entidade/cidadão); ``aggregated``: lakehouse agregado."""


@dataclass
class ToolContext:
    tenant: str
    agent_id: str
    agent_version: str
    run_id: str
    token: AgentToken
    core: CoreClient
    correlation_id: str
    approved_by: str | None = None
    analytics: AggregatedAnalytics | None = None


ToolHandler = Callable[[ToolContext, Any], Awaitable[BaseModel]]


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    input_model: type[BaseModel]
    output_model: type[BaseModel]
    risk: Risk
    action_class: ActionClass
    scope: str
    handler: ToolHandler
    kind: ToolKind = "read"
    owner: str = "core-municipal"
    stub: bool = False
    tags: tuple[str, ...] = field(default_factory=tuple)
    data_layer: DataLayer = "operational"

    def describe(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "risk": self.risk,
            "action_class": self.action_class,
            "scope": self.scope,
            "kind": self.kind,
            "owner": self.owner,
            "stub": self.stub,
            "data_layer": self.data_layer,
            "input_schema": self.input_model.model_json_schema(),
            "output_schema": self.output_model.model_json_schema(),
        }


class ToolNotRegistered(KeyError):
    pass


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, ToolSpec] = {}

    def register(self, spec: ToolSpec) -> ToolSpec:
        if spec.name in self._tools:
            raise ValueError(f"ferramenta já registrada: {spec.name}")
        self._tools[spec.name] = spec
        return spec

    def get(self, name: str) -> ToolSpec:
        try:
            return self._tools[name]
        except KeyError as exc:
            raise ToolNotRegistered(name) from exc

    def __contains__(self, name: str) -> bool:
        return name in self._tools

    def names(self) -> list[str]:
        return sorted(self._tools)

    def list(self) -> list[ToolSpec]:
        return [self._tools[n] for n in self.names()]
