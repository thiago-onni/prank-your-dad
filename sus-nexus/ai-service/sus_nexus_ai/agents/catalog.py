"""Catálogo de agentes e LLM fake com os respondedores de cada agente."""

from __future__ import annotations

from typing import Any

from sus_nexus_ai.agents import mpi_duplicate_suggestion, post_discharge_followup
from sus_nexus_ai.agents import regulation_completeness as regulation
from sus_nexus_ai.agents.base import AgentDefinition
from sus_nexus_ai.llm.client import FakeLLMClient, FakeResponder

_MODULES = (regulation, mpi_duplicate_suggestion, post_discharge_followup)


def build_catalog() -> dict[str, AgentDefinition[Any, Any]]:
    catalog: dict[str, AgentDefinition[Any, Any]] = {}
    for module in _MODULES:
        definition = module.definition()
        catalog[definition.id] = definition
    return catalog


def fake_responders() -> dict[str, FakeResponder]:
    return {module.AGENT_ID: module.fake_responder for module in _MODULES}


def build_fake_llm(scripted: list[str] | None = None) -> FakeLLMClient:
    return FakeLLMClient(responders=fake_responders(), scripted=scripted)
