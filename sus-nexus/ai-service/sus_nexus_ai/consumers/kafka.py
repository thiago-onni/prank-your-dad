"""Consumidor Kafka (desligado por padrão: ``AI_KAFKA_ENABLED=false``).

Eventos (``contracts/events/*.schema.json``) → agentes:

| tópico                        | ``event_type`` (sufixo)        | agente                       |
|-------------------------------|--------------------------------|------------------------------|
| ``sus.regulation.request.v1`` | ``.created`` / ``.updated``    | ``regulation_completeness``  |
| ``sus.exam.result.v1``        | ``.critical_flagged``          | ``exam_critical_result``     |
| ``sus.identity.merge.v1``     | ``.case_opened``               | ``mpi_duplicate_suggestion`` |
| ``sus.hospital.discharge.v1`` | ``.completed``                 | ``post_discharge_followup``  |

``sus.hospital.discharge.completed`` → ``post_discharge_followup`` v2 com
``data.hospital_episode_id`` (o agente relê o episódio no core; ``counter_referral_received`` é
ignorado).

Tenant vem **sempre** do envelope (``tenant.municipality_id``). Idempotência por ``event_id`` via
``agent_event_inbox(event_id, consumer_group)``. O handler (``AgentEventHandler.handle``) é
testável sem rede; ``KafkaAgentConsumer`` usa ``aiokafka`` (dependência opcional ``.[kafka]``).
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

from pydantic import BaseModel, Field, ValidationError

from sus_nexus_ai.common import get_logger
from sus_nexus_ai.config import Settings
from sus_nexus_ai.persistence.schemas import AgentRunRecord, Trigger
from sus_nexus_ai.service import AIService

log = get_logger(__name__)

TOPIC_AGENTS: dict[str, str] = {
    "sus.hospital.discharge": "post_discharge_followup",
    "sus.regulation.request": "regulation_completeness",
    "sus.exam.result": "exam_critical_result",
    "sus.identity.merge": "mpi_duplicate_suggestion",
}
ACCEPTED_ACTIONS: dict[str, set[str]] = {
    "sus.hospital.discharge": {"completed"},
    "sus.regulation.request": {"created", "updated"},
    "sus.exam.result": {"critical_flagged"},
    "sus.identity.merge": {"case_opened"},
}
DEFAULT_TOPICS: tuple[str, ...] = (
    "sus.hospital.discharge.v1",
    "sus.regulation.request.v1",
    "sus.exam.result.v1",
    "sus.identity.merge.v1",
)


class EventEnvelope(BaseModel):
    """Subconjunto do ``contracts/events/envelope.schema.json`` usado pelo consumidor."""

    event_id: str = Field(pattern=r"^evt_[0-9A-Za-z]{26}$")
    event_type: str = Field(pattern=r"^sus\.[a-z][a-z-]*(\.[a-z][a-z-]*)?\.[a-z][a-z_]*$")
    event_version: str = "1.0"
    tenant: dict[str, Any]
    subject: dict[str, Any] | None = None
    data: dict[str, Any] = Field(default_factory=dict)
    data_ref: str | None = None
    trace: dict[str, Any] = Field(default_factory=dict)

    @property
    def municipality_id(self) -> str:
        return str(self.tenant["municipality_id"])

    @property
    def family(self) -> str:
        return self.event_type.rsplit(".", 1)[0]

    @property
    def action(self) -> str:
        return self.event_type.rsplit(".", 1)[1]


class AgentEventHandler:
    def __init__(self, service: AIService, consumer_group: str = "ai-service") -> None:
        self.service = service
        self.consumer_group = consumer_group

    @staticmethod
    def agent_for(envelope: EventEnvelope) -> str | None:
        agent_id = TOPIC_AGENTS.get(envelope.family)
        if agent_id is None or envelope.action not in ACCEPTED_ACTIONS[envelope.family]:
            return None
        return agent_id

    @staticmethod
    def build_input(agent_id: str, envelope: EventEnvelope) -> dict[str, Any]:
        data = envelope.data
        citizen_id = (envelope.subject or {}).get("municipal_citizen_id") or data.get("citizen_id")
        if agent_id == "post_discharge_followup":
            # v2: o agente lê o episódio real no core (risco e tarefa já definidos na alta);
            # do evento só usa a referência do episódio e as linhas de cuidado (contagem).
            care_lines = data.get("care_lines")
            return {
                "hospital_episode_id": data.get("hospital_episode_id"),
                "event_id": envelope.event_id,
                "citizen_id": citizen_id,
                "care_lines": care_lines if isinstance(care_lines, list) else [],
            }
        if agent_id == "regulation_completeness":
            return {
                "request_id": data.get("regulation_request_id")
                or data.get("request_id")
                or data.get("id"),
                "citizen_id": citizen_id,
            }
        if agent_id == "exam_critical_result":
            return {
                "order_id": data.get("exam_order_id"),
                "result_id": data.get("exam_result_id"),
                "requesting_cnes": data.get("requesting_cnes"),
            }
        if agent_id == "mpi_duplicate_suggestion":
            return {"case_id": data.get("case_id")}
        raise KeyError(agent_id)

    async def handle(self, raw: dict[str, Any] | bytes | str) -> AgentRunRecord | None:
        """Processa um evento; devolve o run criado ou ``None`` (ignorado/duplicado)."""
        payload = json.loads(raw) if isinstance(raw, bytes | str) else raw
        try:
            envelope = EventEnvelope.model_validate(payload)
        except ValidationError as exc:
            log.warning("consumer.invalid_envelope", errors=exc.error_count())
            return None
        agent_id = self.agent_for(envelope)
        if agent_id is None:
            log.debug("consumer.ignored", event_type=envelope.event_type)
            return None
        if not self.service.repository.claim_event(envelope.event_id, self.consumer_group):
            log.info("consumer.duplicate", event_id=envelope.event_id)
            return None
        run = await self.service.run_agent(
            agent_id,
            tenant=envelope.municipality_id,
            trigger=Trigger(kind="event", ref=envelope.event_id),
            input_data=self.build_input(agent_id, envelope),
        )
        self.service.repository.bind_event_run(envelope.event_id, self.consumer_group, run.id)
        log.info("consumer.handled", event_id=envelope.event_id, run_id=run.id, status=run.status)
        return run


class KafkaAgentConsumer:
    def __init__(self, service: AIService, settings: Settings) -> None:
        self.handler = AgentEventHandler(service, settings.kafka_consumer_group)
        self.settings = settings
        self._task: asyncio.Task[None] | None = None
        self._consumer: Any = None

    async def start(self) -> None:
        try:
            from aiokafka import AIOKafkaConsumer
        except ImportError as exc:  # pragma: no cover - dependência opcional
            raise RuntimeError("instale 'sus-nexus-ai-service[kafka]' para usar Kafka") from exc
        self._consumer = AIOKafkaConsumer(
            *self.settings.kafka_topics,
            bootstrap_servers=self.settings.kafka_bootstrap_servers,
            group_id=self.settings.kafka_consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        await self._consumer.start()
        self._task = asyncio.create_task(self._loop())
        log.info("consumer.started", topics=self.settings.kafka_topics)

    async def _loop(self) -> None:
        assert self._consumer is not None
        async for msg in self._consumer:
            try:
                await self.handler.handle(msg.value)
                await self._consumer.commit()
            except Exception as exc:
                log.error("consumer.error", topic=msg.topic, error=type(exc).__name__)

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
        if self._consumer is not None:
            await self._consumer.stop()
