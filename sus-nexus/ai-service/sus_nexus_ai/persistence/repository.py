"""Repositório de execuções e aprovações (SQLAlchemy 2, síncrono)."""

from __future__ import annotations

import json
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from sqlalchemy import Engine, create_engine, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from sus_nexus_ai.common import utcnow
from sus_nexus_ai.persistence.models import AgentApprovalRow, AgentEventInboxRow, AgentRunRow, Base
from sus_nexus_ai.persistence.schemas import AgentApproval, AgentRunRecord


def create_db_engine(database_url: str) -> Engine:
    if database_url.startswith("sqlite"):
        return create_engine(
            database_url,
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
            json_serializer=lambda obj: json.dumps(obj, ensure_ascii=False, default=str),
        )
    return create_engine(database_url, pool_pre_ping=True)


def _dump(model: AgentRunRecord | AgentApproval) -> dict[str, Any]:
    return model.model_dump(mode="json")


class AgentRunRepository:
    def __init__(self, engine: Engine) -> None:
        self._engine = engine
        self._sessions = sessionmaker(bind=engine, expire_on_commit=False)
        Base.metadata.create_all(engine)

    @contextmanager
    def session(self) -> Iterator[Session]:
        with self._sessions() as session:
            yield session
            session.commit()

    # ---- runs ----
    def save_run(self, record: AgentRunRecord) -> AgentRunRecord:
        with self.session() as s:
            row = s.get(AgentRunRow, record.id)
            if row is None:
                row = AgentRunRow(id=record.id)
                s.add(row)
            row.tenant_id = record.tenant
            row.agent_id = record.agent_id
            row.agent_version = record.agent_version
            row.status = record.status
            row.validation_status = record.validation_status
            row.started_at = record.started_at
            row.finished_at = record.finished_at
            row.payload = _dump(record)
        return record

    def get_run(self, run_id: str) -> AgentRunRecord | None:
        with self.session() as s:
            row = s.get(AgentRunRow, run_id)
            return AgentRunRecord.model_validate(row.payload) if row else None

    def get_run_raw(self, run_id: str) -> dict[str, Any] | None:
        """Payload persistido tal como gravado (usado em testes de PII)."""
        with self.session() as s:
            row = s.get(AgentRunRow, run_id)
            return dict(row.payload) if row else None

    def list_runs(
        self,
        agent_id: str | None = None,
        status: str | None = None,
        tenant: str | None = None,
        limit: int = 50,
    ) -> list[AgentRunRecord]:
        stmt = select(AgentRunRow).order_by(AgentRunRow.started_at.desc()).limit(limit)
        if agent_id:
            stmt = stmt.where(AgentRunRow.agent_id == agent_id)
        if status:
            stmt = stmt.where(AgentRunRow.status == status)
        if tenant:
            stmt = stmt.where(AgentRunRow.tenant_id == tenant)
        with self.session() as s:
            return [AgentRunRecord.model_validate(r.payload) for r in s.scalars(stmt)]

    # ---- approvals ----
    def save_approval(self, approval: AgentApproval) -> AgentApproval:
        with self.session() as s:
            row = s.get(AgentApprovalRow, approval.id)
            if row is None:
                row = AgentApprovalRow(id=approval.id)
                s.add(row)
            row.tenant_id = approval.tenant
            row.run_id = approval.run_id
            row.action_id = approval.action_id
            row.agent_id = approval.agent_id
            row.tool = approval.tool
            row.status = approval.status
            row.requested_at = approval.requested_at
            row.decided_at = approval.decided_at
            row.approver = approval.approver
            row.payload = _dump(approval)
        return approval

    def get_approval(self, run_id: str, action_id: str) -> AgentApproval | None:
        stmt = select(AgentApprovalRow).where(
            AgentApprovalRow.run_id == run_id, AgentApprovalRow.action_id == action_id
        )
        with self.session() as s:
            row = s.scalars(stmt).first()
            return AgentApproval.model_validate(row.payload) if row else None

    def list_approvals(
        self, status: str | None = "pending", agent_id: str | None = None, limit: int = 100
    ) -> list[AgentApproval]:
        stmt = select(AgentApprovalRow).order_by(AgentApprovalRow.requested_at.desc()).limit(limit)
        if status:
            stmt = stmt.where(AgentApprovalRow.status == status)
        if agent_id:
            stmt = stmt.where(AgentApprovalRow.agent_id == agent_id)
        with self.session() as s:
            return [AgentApproval.model_validate(r.payload) for r in s.scalars(stmt)]

    # ---- inbox (idempotência de eventos) ----
    def claim_event(self, event_id: str, consumer_group: str) -> bool:
        """Retorna True na primeira vez; False se o evento já foi processado."""
        try:
            with self.session() as s:
                s.add(
                    AgentEventInboxRow(
                        event_id=event_id, consumer_group=consumer_group, processed_at=utcnow()
                    )
                )
        except IntegrityError:
            return False
        return True

    def bind_event_run(self, event_id: str, consumer_group: str, run_id: str) -> None:
        with self.session() as s:
            row = s.get(AgentEventInboxRow, (event_id, consumer_group))
            if row is not None:
                row.run_id = run_id
