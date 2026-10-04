"""Observabilidade de LLM (Langfuse via callbacks do LiteLLM) — AIA-002/010.

Ligado só com ``AI_LANGFUSE_ENABLED=true``, ``AI_LLM_PROVIDER=litellm`` e chaves configuradas.
O LiteLLM envia a cada geração o ``metadata`` do ``LiteLLMClient`` (``agent_id``,
``trace_name``, ``tags``). Os prompts já são o contexto **minimizado** (sem PII).
"""

from __future__ import annotations

import os
from typing import Any

from sus_nexus_ai.common import get_logger
from sus_nexus_ai.config import Settings

log = get_logger(__name__)

CALLBACK = "langfuse"


def configure_langfuse(settings: Settings, litellm_module: Any | None = None) -> bool:
    """Registra o callback Langfuse no LiteLLM. Devolve ``True`` se ficou ligado."""
    if not settings.langfuse_enabled or settings.llm_provider != "litellm":
        return False
    if not settings.langfuse_public_key or not settings.langfuse_secret_key:
        log.warning("langfuse.missing_keys")
        return False
    if litellm_module is None:
        import litellm as litellm_module  # import tardio: biblioteca pesada

    os.environ.setdefault("LANGFUSE_PUBLIC_KEY", settings.langfuse_public_key)
    os.environ.setdefault("LANGFUSE_SECRET_KEY", settings.langfuse_secret_key)
    os.environ.setdefault("LANGFUSE_HOST", settings.langfuse_host)
    for attr in ("success_callback", "failure_callback"):
        callbacks = list(getattr(litellm_module, attr, None) or [])
        if CALLBACK not in callbacks:
            callbacks.append(CALLBACK)
        setattr(litellm_module, attr, callbacks)
    log.info("langfuse.enabled", host=settings.langfuse_host)
    return True
