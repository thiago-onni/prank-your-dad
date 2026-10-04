"""Utilitários compartilhados: ULIDs prefixados, hashing, relógio e logging estruturado."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
from datetime import UTC, datetime
from typing import Any

import structlog
from ulid import ULID


def new_id(prefix: str) -> str:
    """ULID prefixado por tipo (``run_``, ``act_``, ``apr_``...), conforme CONVENTIONS."""
    return f"{prefix}{ULID()}"


def utcnow() -> datetime:
    return datetime.now(tz=UTC)


def canonical_json(data: Any) -> str:
    return json.dumps(data, sort_keys=True, ensure_ascii=False, separators=(",", ":"), default=str)


def sha256_hex(data: Any) -> str:
    """Hash estável (SHA-256) de qualquer estrutura JSON-serializável."""
    payload = data if isinstance(data, str) else canonical_json(data)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def hmac_identifier(tenant: str, value: str, secret: str) -> str:
    """HMAC-SHA256 com chave derivada por tenant (CONVENTIONS › Segurança)."""
    key = hashlib.sha256(f"{secret}:{tenant}".encode()).digest()
    return hmac.new(key, value.encode("utf-8"), hashlib.sha256).hexdigest()


def configure_logging(level: str = "INFO") -> None:
    """Logs JSON sem PII (os campos de contexto nunca recebem dados brutos de cidadão)."""
    logging.basicConfig(level=level.upper(), format="%(message)s")
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.JSONRenderer(ensure_ascii=False),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            logging.getLevelName(level.upper())
            if isinstance(logging.getLevelName(level.upper()), int)
            else logging.INFO
        ),
        cache_logger_on_first_use=False,
    )


def get_logger(name: str) -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)  # type: ignore[no-any-return]
