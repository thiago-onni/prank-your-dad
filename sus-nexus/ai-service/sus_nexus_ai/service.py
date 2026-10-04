"""Composição do serviço (wiring) e fluxo de aprovação humana (AIA-005)."""

from __future__ import annotations

from typing import Any

from sus_nexus_ai import metrics
from sus_nexus_ai.agents.base import AgentDefinition, AgentRunner
from sus_nexus_ai.agents.catalog import build_catalog, build_fake_llm
from sus_nexus_ai.common import get_logger, utcnow
from sus_nexus_ai.config import Settings, get_settings
from sus_nexus_ai.llm.client import LiteLLMClient, LLMClient
from sus_nexus_ai.observability import configure_langfuse
from sus_nexus_ai.persistence.repository import AgentRunRepository, create_db_engine
from sus_nexus_ai.persistence.schemas import AgentApproval, AgentRunRecord, Trigger
from sus_nexus_ai.security.identity import (
    FakeIdentityProvider,
    IdentityProvider,
    KeycloakIdentityProvider,
)
from sus_nexus_ai.security.kill_switch import KillSwitch
from sus_nexus_ai.security.policy import (
    AgentPolicyClient,
    InvokeAgent,
    InvokeDecision,
    InvokeInput,
    InvokeSubject,
    LocalPolicyEvaluator,
    PolicyClient,
)
from sus_nexus_ai.tools.analytics import (
    AggregatedAnalytics,
    InMemoryAggregatedAnalytics,
    TrinoAggregatedAnalytics,
)
from sus_nexus_ai.tools.core_client import CoreClient, HttpCoreClient, InMemoryCoreClient
from sus_nexus_ai.tools.core_tools import build_default_registry
from sus_nexus_ai.tools.executor import (
    ApprovalRequired,
    ToolDenied,
    ToolExecutionError,
    ToolExecutor,
)
from sus_nexus_ai.tools.registry import ToolRegistry
from sus_nexus_ai.tools.trino_client import TrinoHttpClient

log = get_logger(__name__)


class UnknownAgent(KeyError):
    pass


class ApprovalError(Exception):
    def __init__(self, status: int, detail: str) -> None:
        self.status = status
        self.detail = detail
        super().__init__(detail)


class AIService:
    def __init__(
        self,
        settings: Settings,
        registry: ToolRegistry,
        kill_switch: KillSwitch,
        policy: PolicyClient,
        identity: IdentityProvider,
        core: CoreClient,
        llm: LLMClient,
        repository: AgentRunRepository,
        catalog: dict[str, AgentDefinition[Any, Any]],
        analytics: AggregatedAnalytics | None = None,
    ) -> None:
        self.settings = settings
        self.registry = registry
        self.kill_switch = kill_switch
        self.policy = policy
        self.identity = identity
        self.core = core
        self.llm = llm
        self.repository = repository
        self.catalog = catalog
        self.analytics = analytics
        self.executor = ToolExecutor(registry, policy, kill_switch, identity, core, analytics)
        self.runner = AgentRunner(
            self.executor,
            llm,
            repository,
            kill_switch,
            max_output_retries=settings.llm_max_output_retries,
            cost_per_1k_input=settings.llm_cost_per_1k_input_usd,
            cost_per_1k_output=settings.llm_cost_per_1k_output_usd,
            checkpointer=_build_checkpointer(settings),
        )

    def get_agent(self, agent_id: str) -> AgentDefinition[Any, Any]:
        try:
            return self.catalog[agent_id]
        except KeyError as exc:
            raise UnknownAgent(agent_id) from exc

    async def authorize_invocation(
        self, agent_id: str, *, roles: list[str], subject_tenant: str | None, tenant: str
    ) -> InvokeDecision:
        """Consulta ``data.sus.agents.invoke`` (OPA) com papéis, tenant e kill switch efetivo.

        Levanta ``PolicyUnavailable`` se o OPA não responder (o chamador nega: fail-closed).
        """
        decision = await self.policy.decide_invoke(
            InvokeInput(
                agent=InvokeAgent(id=agent_id),
                subject=InvokeSubject(roles=sorted(set(roles)), tenant=subject_tenant),
                tenant=tenant,
                kill_switch=self.kill_switch.state(),
            )
        )
        log.info(
            "agent.invoke_decision",
            agent_id=agent_id,
            tenant=tenant,
            allow=decision.allow,
            reasons=decision.reasons,
            policy_version=decision.policy_version,
        )
        return decision

    async def run_agent(
        self,
        agent_id: str,
        *,
        tenant: str,
        trigger: Trigger,
        input_data: dict[str, Any],
        on_behalf_of_token: str | None = None,
    ) -> AgentRunRecord:
        definition = self.get_agent(agent_id)
        return await self.runner.run(
            definition,
            tenant=tenant,
            trigger=trigger,
            input_data=input_data,
            on_behalf_of_token=on_behalf_of_token,
        )

    # ---- aprovação humana (AIA-005) ----
    def _load_pending(self, run_id: str, action_id: str) -> tuple[AgentRunRecord, AgentApproval]:
        run = self.repository.get_run(run_id)
        if run is None:
            raise ApprovalError(404, f"run {run_id} não encontrado")
        action = run.action(action_id)
        if action is None:
            raise ApprovalError(404, f"ação {action_id} não encontrada")
        approval = self.repository.get_approval(run_id, action_id)
        if approval is None or action.status != "pending_approval":
            raise ApprovalError(409, "ação não está pendente de aprovação")
        if approval.status != "pending":
            raise ApprovalError(409, f"aprovação já decidida: {approval.status}")
        return run, approval

    async def approve_action(
        self, run_id: str, action_id: str, *, approver: str, justification: str
    ) -> AgentRunRecord:
        if not justification.strip():
            raise ApprovalError(422, "justificativa obrigatória")
        run, approval = self._load_pending(run_id, action_id)
        action = run.action(action_id)
        assert action is not None
        definition = self.get_agent(run.agent_id)
        now = utcnow()
        action.approver = approver
        action.justification = justification
        action.decided_at = now
        approval.approver = approver
        approval.justification = justification
        approval.decided_at = now
        try:
            result = await self.executor.call(
                agent=definition.identity(),
                tenant=run.tenant,
                tool_name=action.tool,
                args=action.args,
                run_id=run.id,
                approved_by=approver,
            )
            action.status = "approved"
            action.result_hash = result.record.result_hash
            run.tools_called.append(result.record.to_dict())
            approval.status = "approved"
        except ToolDenied as exc:
            action.status = "denied"
            action.reasons = exc.reasons
            run.tools_called.append(exc.record.to_dict())
            approval.status = "rejected"
        except (ApprovalRequired, ToolExecutionError) as exc:
            action.status = "failed"
            action.error = str(exc)
            run.tools_called.append(exc.record.to_dict())
            approval.status = "approved"
        self.repository.save_approval(approval)
        self.repository.save_run(run)
        metrics.record_human_decision(run.agent_id, "approved")
        metrics.record_action(run.agent_id, action.action_class, action.status)
        log.info("approval.decided", run_id=run.id, action_id=action_id, decision="approved")
        return run

    async def reject_action(
        self, run_id: str, action_id: str, *, approver: str, justification: str
    ) -> AgentRunRecord:
        if not justification.strip():
            raise ApprovalError(422, "justificativa obrigatória")
        run, approval = self._load_pending(run_id, action_id)
        action = run.action(action_id)
        assert action is not None
        now = utcnow()
        action.status = "rejected"
        action.approver = approver
        action.justification = justification
        action.decided_at = now
        approval.status = "rejected"
        approval.approver = approver
        approval.justification = justification
        approval.decided_at = now
        self.repository.save_approval(approval)
        self.repository.save_run(run)
        metrics.record_human_decision(run.agent_id, "rejected")
        metrics.record_action(run.agent_id, action.action_class, action.status)
        log.info("approval.decided", run_id=run.id, action_id=action_id, decision="rejected")
        return run

    async def aclose(self) -> None:
        for component in (self.policy, self.identity, self.core, self.analytics):
            closer = getattr(component, "aclose", None)
            if closer is not None:
                await closer()


def _build_checkpointer(settings: Settings) -> Any:
    if settings.checkpointer == "postgres":
        try:
            from langgraph.checkpoint.postgres import PostgresSaver

            saver = PostgresSaver.from_conn_string(
                settings.database_url.replace("postgresql+psycopg://", "postgresql://")
            )
            saver.setup()
            return saver
        except ImportError:  # pragma: no cover - dependência opcional
            log.warning("checkpointer.postgres_unavailable", fallback="memory")
    return None


def build_service(
    settings: Settings | None = None,
    *,
    core: CoreClient | None = None,
    llm: LLMClient | None = None,
    policy: PolicyClient | None = None,
    identity: IdentityProvider | None = None,
    repository: AgentRunRepository | None = None,
    kill_switch: KillSwitch | None = None,
    registry: ToolRegistry | None = None,
    analytics: AggregatedAnalytics | None = None,
) -> AIService:
    settings = settings or get_settings()
    kill_switch = kill_switch or KillSwitch(file_path=settings.kill_switch_file)
    if policy is None:
        policy = (
            LocalPolicyEvaluator()
            if settings.policy_mode == "local"
            else AgentPolicyClient(
                settings.opa_url,
                settings.opa_decision_path,
                cache_ttl_seconds=settings.opa_cache_ttl_seconds,
                invoke_path=settings.opa_invoke_path,
            )
        )
    if identity is None:
        identity = (
            KeycloakIdentityProvider(
                settings.keycloak_token_url,
                settings.keycloak_agent_client_id,
                settings.keycloak_agent_client_secret or "",
            )
            if settings.identity_mode == "keycloak"
            else FakeIdentityProvider()
        )
    if core is None:
        core = (
            InMemoryCoreClient()
            if settings.environment == "test"
            else HttpCoreClient(settings.core_base_url, settings.core_timeout_seconds)
        )
    if llm is None:
        llm = (
            build_fake_llm()
            if settings.llm_provider == "fake"
            else LiteLLMClient(
                settings.llm_model,
                temperature=settings.llm_temperature,
                max_tokens=settings.llm_max_tokens,
                api_base=settings.llm_api_base,
                api_key=settings.llm_api_key,
                mock_response=settings.llm_mock_response,
            )
        )
    if analytics is None:
        analytics = (
            InMemoryAggregatedAnalytics()
            if settings.environment == "test"
            else TrinoAggregatedAnalytics(
                TrinoHttpClient(
                    settings.trino_url,
                    settings.trino_user,
                    settings.trino_catalog,
                    password=settings.trino_password,
                    timeout_seconds=settings.trino_timeout_seconds,
                ),
                settings.trino_catalog,
            )
        )
    configure_langfuse(settings)
    repository = repository or AgentRunRepository(create_db_engine(settings.database_url))
    return AIService(
        settings=settings,
        registry=registry or build_default_registry(),
        kill_switch=kill_switch,
        policy=policy,
        identity=identity,
        core=core,
        llm=llm,
        repository=repository,
        catalog=build_catalog(),
        analytics=analytics,
    )
