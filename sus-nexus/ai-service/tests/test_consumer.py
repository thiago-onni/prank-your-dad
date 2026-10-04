from __future__ import annotations

import json

from sus_nexus_ai.consumers.kafka import AgentEventHandler, EventEnvelope
from sus_nexus_ai.service import AIService
from sus_nexus_ai.tools.core_client import InMemoryCoreClient
from tests.conftest import CITIZEN_ID, REQUEST_ID, TENANT

DISCHARGE_EVENT = {
    "event_id": "evt_01J8XE01ABCDEFGHJKMNPQRSTV",
    "event_type": "sus.hospital.discharge.completed",
    "event_version": "1.0",
    "occurred_at": "2026-10-01T15:30:00-03:00",
    "published_at": "2026-10-01T15:31:00-03:00",
    "tenant": {"municipality_id": TENANT},
    "subject": {
        "municipal_citizen_id": CITIZEN_ID,
        "identifiers": [{"system": "CNS", "value_masked": "***********1234"}],
    },
    "source": {"system": "HIS", "connector": "his-connector", "source_record_id": "ep-77"},
    "data": {
        "episode_id": "hep_01J8XH01ABCDEFGHJKMNPQRSTV",
        "discharged_at": "2026-10-01T15:30:00-03:00",
        "discharge_type": "home",
        "length_of_stay_days": 9,
        "primary_diagnosis_cid10": "I50.0",
        "readmissions_30d": 1,
        "age_years": 72,
        "comorbidities_count": 3,
        "has_care_plan": False,
        "reference_team": {"team_ine": "0001234567", "health_unit_cnes": "2143456"},
    },
    "privacy": {"classification": "restricted", "purpose": ["care_coordination"]},
    "trace": {"correlation_id": "corr-1", "schema_version": "1.0"},
}


async def test_discharge_event_triggers_post_discharge_agent(
    service: AIService, core: InMemoryCoreClient
) -> None:
    handler = AgentEventHandler(service, consumer_group="ai-service")
    run = await handler.handle(json.dumps(DISCHARGE_EVENT).encode())
    assert run is not None and run.status == "completed"
    assert run.agent_id == "post_discharge_followup"
    assert run.trigger.kind == "event" and run.trigger.ref == DISCHARGE_EVENT["event_id"]
    assert len(core.tasks) == 1 and core.tasks[0].citizen_id == CITIZEN_ID


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
    assert await handler.handle({"event_type": "sus.x.y"}) is None  # envelope inválido
    assert service.repository.list_runs() == []


async def test_regulation_request_event_triggers_completeness_agent(service: AIService) -> None:
    handler = AgentEventHandler(service)
    event = {
        **DISCHARGE_EVENT,
        "event_id": "evt_01J8XE04ABCDEFGHJKMNPQRSTV",
        "event_type": "sus.regulation.request.created",
        "data": {"request_id": REQUEST_ID},
    }
    envelope = EventEnvelope.model_validate(event)
    assert AgentEventHandler.agent_for(envelope) == "regulation_completeness"
    run = await handler.handle(event)
    assert run is not None and run.agent_id == "regulation_completeness"
    assert run.status == "completed" and run.actions[0].status == "pending_approval"
