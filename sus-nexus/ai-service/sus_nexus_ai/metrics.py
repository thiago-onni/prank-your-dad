"""Métricas Prometheus do ai-service."""

from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram, generate_latest

REGISTRY = CollectorRegistry(auto_describe=True)

agent_tool_call_total = Counter(
    "agent_tool_call_total",
    "Chamadas de ferramenta por agente/ferramenta/resultado",
    ["agent_id", "tool", "status"],
    registry=REGISTRY,
)
agent_tool_call_denied_total = Counter(
    "agent_tool_call_denied_total",
    "Chamadas de ferramenta negadas (OPA, kill switch, não concedida)",
    ["agent_id", "tool", "reason"],
    registry=REGISTRY,
)
agent_run_total = Counter(
    "agent_run_total", "Execuções de agente por status", ["agent_id", "status"], registry=REGISTRY
)
agent_human_decision_total = Counter(
    "agent_human_decision_total",
    "Decisões humanas sobre ações propostas",
    ["agent_id", "decision"],
    registry=REGISTRY,
)
agent_human_approval_rate = Gauge(
    "agent_human_approval_rate",
    "Taxa de aprovação humana (aprovadas / decididas) por agente",
    ["agent_id"],
    registry=REGISTRY,
)
agent_run_duration_seconds = Histogram(
    "agent_run_duration_seconds", "Duração das execuções", ["agent_id"], registry=REGISTRY
)
agent_llm_cost_usd_total = Counter(
    "agent_llm_cost_usd_total", "Custo estimado de LLM (USD)", ["agent_id"], registry=REGISTRY
)

_decisions: dict[str, dict[str, int]] = {}


def record_human_decision(agent_id: str, decision: str) -> None:
    agent_human_decision_total.labels(agent_id=agent_id, decision=decision).inc()
    counts = _decisions.setdefault(agent_id, {"approved": 0, "rejected": 0})
    if decision in counts:
        counts[decision] += 1
    total = counts["approved"] + counts["rejected"]
    if total:
        agent_human_approval_rate.labels(agent_id=agent_id).set(counts["approved"] / total)


def render() -> bytes:
    return generate_latest(REGISTRY)
