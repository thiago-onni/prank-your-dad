"""Consumidor de eventos: envelopes válidos contra ``contracts/events`` → agentes certos."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator, FormatChecker

from sus_nexus_ai.consumers.kafka import DEFAULT_TOPICS, AgentEventHandler, EventEnvelope
from sus_nexus_ai.service import AIService
from sus_nexus_ai.tools.core_client import InMemoryCoreClient
from tests.conftest import (
    CASE_ID,
    CITIZEN_ID,
    EPISODE_ID,
    EPISODE_ID_TRACKED,
    EXAM_ORDER_ID,
    EXAM_RESULT_ID,
    REQUEST_ID,
    TENANT,
)

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts" / "events"


def _envelope(event_type: str, data: dict[str, Any], event_id: str, purpose: str) -> dict[str, Any]:
    return {
        "event_id": event_id,
        "event_type": event_type,
        "event_version": "1.0",
        "occurred_at": "2026-10-01T15:30:00-03:00",
        "published_at": "2026-10-01T15:31:00-03:00",
        "tenant": {"municipality_id": TENANT},
        "subject": {
            "municipal_citizen_id": CITIZEN_ID,
            "identifiers": [{"system": "CNS", "value_masked": "***********1234"}],
        },
        "source": {"system": "SISREG", "connector": "sisreg-connector", "source_record_id": "r-1"},
        "data": data,
        "privacy": {"classification": "restricted", "purpose": [purpose]},
        "trace": {"correlation_id": "corr-1", "schema_version": "1.0"},
    }


DISCHARGE_EVENT = _envelope(
    "sus.hospital.discharge.completed",
    {
        "action": "completed",
        "hospital_episode_id": EPISODE_ID,
        "hospital_cnes": "7654321",
        "discharged_at": "2026-10-01T15:30:00-03:00",
        "admitted_at": "2026-09-22T10:00:00-03:00",
        "length_of_stay_days": 9,
        "disposition": "home",
        "principal_diagnosis": {"system": "CID10", "code": "I50.0"},
        "readmission_within_30d": True,
        "followup_plan_present": False,
        "reference_health_unit_cnes": "2143456",
        "reference_team_ine": "0001234567",
        "risk_level": "high",
        "risk_rule_version": "hospital_risk_v1",
        "care_lines": ["insuficiencia_cardiaca"],
    },
    "evt_01J8XE01ABCDEFGHJKMNPQRSTV",
    "care_coordination",
)
REGULATION_EVENT = _envelope(
    "sus.regulation.request.created",
    {
        "action": "created",
        "regulation_request_id": REQUEST_ID,
        "kind": "consultation",
        "status": "requested",
        "priority": "priority",
        "requested_service_code": "0301010072",
        "code_system": "SIGTAP",
        "specialty": "cardiologia",
        "requested_at": "2026-09-30T10:00:00-03:00",
        "requesting_cnes": "2143456",
        "justification_present": False,
        "attached_documents_count": 1,
    },
    "evt_01J8XE04ABCDEFGHJKMNPQRSTV",
    "regulation",
)
EXAM_EVENT = _envelope(
    "sus.exam.result.critical_flagged",
    {
        "action": "critical_flagged",
        "exam_order_id": EXAM_ORDER_ID,
        "exam_result_id": EXAM_RESULT_ID,
        "result_status": "final",
        "critical": True,
        "reported_at": "2026-10-01T13:00:00Z",
        "performer_cnes": "7654321",
        "has_document": True,
        "observations_count": 1,
        "requesting_cnes": "2143456",
    },
    "evt_01J8XE06ABCDEFGHJKMNPQRSTV",
    "care_coordination",
)
MERGE_EVENT = _envelope(
    "sus.identity.merge.case_opened",
    {
        "action": "case_opened",
        "case_id": CASE_ID,
        "surviving_citizen_id": CITIZEN_ID,
        "merged_citizen_ids": ["cit_01J8X02ABCDEFGHJKMNPQRSTVW"],
        "evidence_count": 3,
    },
    "evt_01J8XE07ABCDEFGHJKMNPQRSTV",
    "identity_management",
)


def _schema(path: str) -> Draft202012Validator:
    return Draft202012Validator(
        json.loads((CONTRACTS / path).read_text("utf-8")), format_checker=FormatChecker()
    )


@pytest.mark.parametrize(
    ("event", "data_schema"),
    [
        (DISCHARGE_EVENT, "hospital/discharge.v1.schema.json"),
        (REGULATION_EVENT, "regulation/request.v1.schema.json"),
        (EXAM_EVENT, "exam/result.v1.schema.json"),
        (MERGE_EVENT, "identity/merge.v1.schema.json"),
    ],
    ids=["discharge", "regulation", "exam", "merge"],
)
def test_example_envelopes_are_valid_against_contracts(
    event: dict[str, Any], data_schema: str
) -> None:
    if not CONTRACTS.exists():  # pragma: no cover - fora do monorepo
        pytest.skip("contracts/ não disponível")
    _schema("envelope.schema.json").validate(event)
    _schema(data_schema).validate(event["data"])


def test_default_topics_cover_every_mapped_family() -> None:
    assert set(DEFAULT_TOPICS) == {
        "sus.hospital.discharge.v1",
        "sus.regulation.request.v1",
        "sus.exam.result.v1",
        "sus.identity.merge.v1",
    }


async def test_discharge_event_triggers_post_discharge_agent(
    service: AIService, core: InMemoryCoreClient
) -> None:
    handler = AgentEventHandler(service, consumer_group="ai-service")
    envelope = EventEnvelope.model_validate(DISCHARGE_EVENT)
    assert AgentEventHandler.agent_for(envelope) == "post_discharge_followup"
    assert AgentEventHandler.build_input("post_discharge_followup", envelope) == {
        "hospital_episode_id": EPISODE_ID,
        "event_id": DISCHARGE_EVENT["event_id"],
        "citizen_id": CITIZEN_ID,
        "care_lines": ["insuficiencia_cardiaca"],
    }
    run = await handler.handle(json.dumps(DISCHARGE_EVENT).encode())
    assert run is not None and run.status == "completed"
    assert run.agent_id == "post_discharge_followup" and run.tenant == TENANT
    assert run.trigger.kind == "event" and run.trigger.ref == DISCHARGE_EVENT["event_id"]
    assert core.calls[0][0] == "get_hospital_episode" and core.calls[0][2] == TENANT
    # episódio sem tarefa do core → fallback cria UMA tarefa
    assert len(core.tasks) == 1 and core.tasks[0].citizen_id == CITIZEN_ID


async def test_discharge_event_with_core_task_only_summarizes(
    service: AIService, core: InMemoryCoreClient
) -> None:
    event = {
        **DISCHARGE_EVENT,
        "event_id": "evt_01J8XE0CABCDEFGHJKMNPQRSTV",
        "data": {**DISCHARGE_EVENT["data"], "hospital_episode_id": EPISODE_ID_TRACKED},
    }
    run = await AgentEventHandler(service).handle(event)
    assert run is not None and run.status == "completed"
    assert run.output is not None and run.output["mode"] == "summary_only"
    assert run.actions == [] and core.tasks == []


async def test_duplicate_event_id_is_idempotent(
    service: AIService, core: InMemoryCoreClient
) -> None:
    handler = AgentEventHandler(service)
    first = await handler.handle(DISCHARGE_EVENT)
    second = await handler.handle(DISCHARGE_EVENT)
    assert first is not None and second is None
    assert len(core.tasks) == 1
    assert len(service.repository.list_runs(agent_id="post_discharge_followup")) == 1


async def test_unmapped_or_invalid_events_are_ignored(service: AIService) -> None:
    handler = AgentEventHandler(service)
    other = {
        **DISCHARGE_EVENT,
        "event_id": "evt_01J8XE02ABCDEFGHJKMNPQRSTV",
        "event_type": "sus.hospital.adt.admitted",
    }
    assert await handler.handle(other) is None
    counter = {
        **DISCHARGE_EVENT,
        "event_id": "evt_01J8XE03ABCDEFGHJKMNPQRSTV",
        "event_type": "sus.hospital.discharge.counter_referral_received",
    }
    assert await handler.handle(counter) is None
    returned = {
        **REGULATION_EVENT,
        "event_id": "evt_01J8XE08ABCDEFGHJKMNPQRSTV",
        "event_type": "sus.regulation.request.returned",
    }
    assert await handler.handle(returned) is None
    available = {
        **EXAM_EVENT,
        "event_id": "evt_01J8XE0AABCDEFGHJKMNPQRSTV",
        "event_type": "sus.exam.result.available",
    }
    assert await handler.handle(available) is None
    assert await handler.handle({"event_type": "sus.x.y"}) is None  # envelope inválido
    assert service.repository.list_runs() == []


async def test_regulation_request_event_triggers_completeness_agent(service: AIService) -> None:
    handler = AgentEventHandler(service)
    envelope = EventEnvelope.model_validate(REGULATION_EVENT)
    assert AgentEventHandler.agent_for(envelope) == "regulation_completeness"
    assert AgentEventHandler.build_input("regulation_completeness", envelope) == {
        "request_id": REQUEST_ID,
        "citizen_id": CITIZEN_ID,
    }
    run = await handler.handle(REGULATION_EVENT)
    assert run is not None and run.agent_id == "regulation_completeness"
    assert run.tenant == TENANT
    assert run.status == "completed" and run.actions[0].status == "pending_approval"
    updated = {
        **REGULATION_EVENT,
        "event_id": "evt_01J8XE0BABCDEFGHJKMNPQRSTV",
        "event_type": "sus.regulation.request.updated",
    }
    again = await handler.handle(updated)
    assert again is not None and again.agent_id == "regulation_completeness"


async def test_exam_critical_event_triggers_exam_agent(
    service: AIService, core: InMemoryCoreClient
) -> None:
    handler = AgentEventHandler(service)
    envelope = EventEnvelope.model_validate(EXAM_EVENT)
    assert AgentEventHandler.agent_for(envelope) == "exam_critical_result"
    assert AgentEventHandler.build_input("exam_critical_result", envelope) == {
        "order_id": EXAM_ORDER_ID,
        "result_id": EXAM_RESULT_ID,
        "requesting_cnes": "2143456",
    }
    run = await handler.handle(EXAM_EVENT)
    assert run is not None and run.agent_id == "exam_critical_result"
    assert run.status == "completed" and run.tenant == TENANT
    assert len(core.tasks) == 1 and core.tasks[0].task_type == "exam_result_followup"


async def test_merge_case_opened_triggers_mpi_agent(
    service: AIService, core: InMemoryCoreClient
) -> None:
    handler = AgentEventHandler(service)
    envelope = EventEnvelope.model_validate(MERGE_EVENT)
    assert AgentEventHandler.agent_for(envelope) == "mpi_duplicate_suggestion"
    assert AgentEventHandler.build_input("mpi_duplicate_suggestion", envelope) == {
        "case_id": CASE_ID
    }
    run = await handler.handle(MERGE_EVENT)
    assert run is not None and run.agent_id == "mpi_duplicate_suggestion"
    assert run.status == "completed" and run.actions == []
    assert run.output is not None and run.output["verdict"] == "probable_same_person"
    assert core.tasks == [] and core.issues == []
