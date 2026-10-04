"""Configuração do ai-service (pydantic-settings).

Todas as variáveis têm prefixo ``AI_`` (ex.: ``AI_CORE_BASE_URL``).
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

PACKAGE_DIR = Path(__file__).resolve().parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", env_file=".env", extra="ignore")

    # --- serviço ---
    service_name: str = "ai-service"
    environment: Literal["dev", "test", "staging", "prod"] = "dev"
    log_level: str = "INFO"
    http_port: int = 8000

    # --- integrações ---
    core_base_url: str = "http://core-municipal:8080"
    core_timeout_seconds: float = 10.0
    opa_url: str = "http://opa:8181"
    opa_decision_path: str = "/v1/data/sus/agents/decision"
    opa_cache_ttl_seconds: float = 5.0
    policy_mode: Literal["opa", "local"] = "opa"
    """``opa``: consulta HTTP ao OPA; ``local``: avaliador em memória equivalente (dev/test)."""

    # --- LLM ---
    llm_provider: Literal["litellm", "fake"] = "fake"
    llm_model: str = "openai/gpt-4o-mini"
    llm_temperature: float = 0.0
    llm_max_tokens: int = 1500
    llm_max_output_retries: int = 2
    llm_api_base: str | None = None
    llm_api_key: str | None = None
    llm_mock_response: str | None = None
    """Quando definido, LiteLLM responde com este texto (``mock_response``) — útil em homologação."""
    llm_cost_per_1k_input_usd: float = 0.00015
    llm_cost_per_1k_output_usd: float = 0.0006

    # --- observabilidade ---
    langfuse_enabled: bool = False
    langfuse_public_key: str | None = None
    langfuse_secret_key: str | None = None
    langfuse_host: str = "https://cloud.langfuse.com"
    otel_enabled: bool = False

    # --- kill switch (AIA-009) ---
    kill_switch_file: Path | None = None
    kill_switch_env: str | None = Field(default=None, alias="AI_KILL_SWITCH")
    """JSON com ``{"global":false,"agents":[],"tools":[],"tenants":[]}`` via variável de ambiente."""

    # --- segurança / identidade ---
    auth_mode: Literal["jwt", "mock"] = "jwt"
    keycloak_base_url: str = "http://keycloak:8180"
    keycloak_realm: str = "sus-nexus"
    keycloak_jwks_url: str | None = None
    keycloak_audience: str = "ai-service"
    keycloak_agent_client_id: str = "ai-service-agent"
    keycloak_agent_client_secret: str | None = None
    identity_mode: Literal["keycloak", "fake"] = "fake"
    tenant_hmac_secret: str = "dev-only-change-me"
    admin_roles: list[str] = Field(default_factory=lambda: ["admin", "dpo"])
    approver_roles: list[str] = Field(default_factory=lambda: ["admin", "agent_approver"])

    # --- persistência ---
    database_url: str = "sqlite+pysqlite:///:memory:"
    checkpointer: Literal["memory", "postgres"] = "memory"

    # --- consumidores ---
    kafka_enabled: bool = False
    kafka_bootstrap_servers: str = "kafka:9092"
    kafka_consumer_group: str = "ai-service"
    kafka_topics: list[str] = Field(
        default_factory=lambda: ["sus.hospital.discharge.v1", "sus.regulation.request.v1"]
    )

    # --- paths ---
    prompts_dir: Path = PACKAGE_DIR / "prompts"
    evals_dir: Path = PACKAGE_DIR / "evals"

    @property
    def keycloak_token_url(self) -> str:
        return f"{self.keycloak_base_url}/realms/{self.keycloak_realm}/protocol/openid-connect/token"

    @property
    def resolved_jwks_url(self) -> str:
        return (
            self.keycloak_jwks_url
            or f"{self.keycloak_base_url}/realms/{self.keycloak_realm}/protocol/openid-connect/certs"
        )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
