"""Tabelas SQLAlchemy 2 (schema ``agents``): agent_run, agent_approval, agent_event_inbox.

SQLite em testes, PostgreSQL em produção (``AI_DATABASE_URL=postgresql+psycopg://...``).
O JSON completo do registro vai em ``payload``; colunas indexadas replicam os filtros usados.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import JSON, DateTime, Index, String
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

JsonType = JSON().with_variant(JSONB(), "postgresql")


class Base(DeclarativeBase):
    pass


class AgentRunRow(Base):
    __tablename__ = "agent_run"

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    tenant_id: Mapped[str] = mapped_column(String(32), nullable=False)
    agent_id: Mapped[str] = mapped_column(String(80), nullable=False)
    agent_version: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    validation_status: Mapped[str] = mapped_column(String(20), nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    payload: Mapped[dict[str, Any]] = mapped_column(JsonType, nullable=False)

    __table_args__ = (
        Index("ix_agent_run_tenant_agent", "tenant_id", "agent_id"),
        Index("ix_agent_run_status", "status"),
        Index("ix_agent_run_started", "started_at"),
    )


class AgentApprovalRow(Base):
    __tablename__ = "agent_approval"

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    tenant_id: Mapped[str] = mapped_column(String(32), nullable=False)
    run_id: Mapped[str] = mapped_column(String(40), nullable=False)
    action_id: Mapped[str] = mapped_column(String(40), nullable=False)
    agent_id: Mapped[str] = mapped_column(String(80), nullable=False)
    tool: Mapped[str] = mapped_column(String(80), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    decided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    approver: Mapped[str | None] = mapped_column(String(120))
    payload: Mapped[dict[str, Any]] = mapped_column(JsonType, nullable=False)

    __table_args__ = (
        Index("ix_agent_approval_run_action", "run_id", "action_id", unique=True),
        Index("ix_agent_approval_status", "status"),
    )


class AgentEventInboxRow(Base):
    """Idempotência de consumo (CONVENTIONS › Eventos: event_inbox(event_id, consumer_group))."""

    __tablename__ = "agent_event_inbox"

    event_id: Mapped[str] = mapped_column(String(40), primary_key=True)
    consumer_group: Mapped[str] = mapped_column(String(80), primary_key=True)
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    run_id: Mapped[str | None] = mapped_column(String(40))
