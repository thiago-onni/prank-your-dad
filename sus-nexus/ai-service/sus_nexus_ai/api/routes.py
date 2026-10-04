"""Rotas HTTP do ai-service."""

from __future__ import annotations

import re
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from pydantic import BaseModel, Field

from sus_nexus_ai import metrics
from sus_nexus_ai.agents.bi_situation_analyst import AGENT_ID as BI_AGENT_ID
from sus_nexus_ai.agents.bi_situation_analyst import BiSituationInput
from sus_nexus_ai.api.auth import Principal, get_principal, require_settings_roles
from sus_nexus_ai.persistence.schemas import AgentApproval, AgentRunRecord, Trigger
from sus_nexus_ai.security.kill_switch import KillSwitchState
from sus_nexus_ai.security.policy import AGENT_PROFILES, PolicyUnavailable
from sus_nexus_ai.service import AIService, ApprovalError, UnknownAgent

router = APIRouter()

_TENANT_RE = re.compile(r"^ibge_\d{7}$")


def _service(request: Request) -> AIService:
    service: AIService = request.app.state.service
    return service


class RunRequest(BaseModel):
    tenant: str = Field(pattern=r"^ibge_\d{7}$")
    trigger: Trigger = Field(default_factory=Trigger)
    input: dict[str, Any]


class DecisionRequest(BaseModel):
    justification: str = Field(min_length=10, max_length=1000)


class KillSwitchResponse(BaseModel):
    effective: KillSwitchState
    admin: KillSwitchState


class AgentDescriptor(BaseModel):
    id: str
    version: str
    prompt_version: str
    description: str
    tools: list[str]
    rule_versions: dict[str, str]
    input_schema: dict[str, Any]
    output_schema: dict[str, Any]


class ToolDescriptor(BaseModel):
    name: str
    description: str
    risk: str
    action_class: str
    scope: str
    kind: str
    owner: str
    stub: bool
    data_layer: str = "operational"
    input_schema: dict[str, Any]
    output_schema: dict[str, Any]


class HealthResponse(BaseModel):
    status: str
    service: str
    agents: list[str]
    llm_model: str
    kill_switch_global: bool


def _check_tenant(principal: Principal, tenant: str, service: AIService) -> None:
    if principal.tenant and principal.tenant != tenant:
        if principal.has_any_role(service.settings.admin_roles):
            return
        raise HTTPException(status.HTTP_403_FORBIDDEN, "tenant do token difere do solicitado")


# ---- agentes e ferramentas ----


@router.get("/agents", tags=["agents"], response_model=list[AgentDescriptor])
def list_agents(
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
) -> list[AgentDescriptor]:
    service = _service(request)
    return [
        AgentDescriptor(
            id=d.id,
            version=d.version,
            prompt_version=d.prompt_version,
            description=d.description,
            tools=d.tools,
            rule_versions=d.rule_versions,
            input_schema=d.input_model.model_json_schema(),
            output_schema=d.output_model.model_json_schema(),
        )
        for d in service.catalog.values()
    ]


@router.get("/tools", tags=["agents"], response_model=list[ToolDescriptor])
def list_tools(
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
) -> list[ToolDescriptor]:
    return [ToolDescriptor(**spec.describe()) for spec in _service(request).registry.list()]


@router.post(
    f"/agents/{BI_AGENT_ID}/run",
    tags=["agents"],
    response_model=AgentRunRecord,
    summary="Run BI situation analyst",
    responses={
        403: {"description": "papel sem permissão, token sem município ou negado pelo OPA"},
        503: {"description": "OPA indisponível (fail-closed: o agente não é executado)"},
    },
)
async def run_bi_situation_analyst(
    body: BiSituationInput,
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
) -> AgentRunRecord:
    """Análise de situação da competência (somente dados agregados; sem ações).

    O município é sempre o do token (`municipality_id`); o corpo não aceita tenant nem campos
    extras. Papéis: `gestor`, `auditor`, `admin_municipal`, checados localmente e pela decisão OPA
    `data.sus.agents.invoke` (papéis, tenant e kill switch); OPA indisponível → 503.
    A saída (`output`) segue o `output_schema` do agente em `GET /agents`.
    """
    service = _service(request)
    profile = AGENT_PROFILES[BI_AGENT_ID]
    if not principal.has_any_role(profile.invoker_roles):
        raise HTTPException(
            status.HTTP_403_FORBIDDEN, f"papel necessário: {' ou '.join(profile.invoker_roles)}"
        )
    tenant = principal.tenant
    if not tenant or not _TENANT_RE.fullmatch(tenant):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "token sem município (municipality_id)")
    # decisão autoritativa em tempo de execução (OPA); a checagem acima é defesa em profundidade
    try:
        decision = await service.authorize_invocation(
            BI_AGENT_ID, roles=principal.roles, subject_tenant=principal.tenant, tenant=tenant
        )
    except PolicyUnavailable as exc:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "política indisponível (OPA); invocação negada"
        ) from exc
    if not decision.allow:
        raise HTTPException(
            status.HTTP_403_FORBIDDEN,
            f"invocação negada pela política: {', '.join(decision.reasons) or 'sem motivo'}",
        )
    return await service.run_agent(
        BI_AGENT_ID,
        tenant=tenant,
        trigger=Trigger(kind="user", ref=principal.subject),
        input_data=body.model_dump(mode="json"),
        on_behalf_of_token=principal.raw_token,
    )


@router.post("/agents/{agent_id}/run", tags=["agents"], response_model=AgentRunRecord)
async def run_agent(
    agent_id: str,
    body: RunRequest,
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
) -> AgentRunRecord:
    service = _service(request)
    if agent_id in AGENT_PROFILES:  # agentes com perfil só pela rota dedicada (tenant do token)
        raise HTTPException(status.HTTP_403_FORBIDDEN, f"use POST /agents/{agent_id}/run dedicada")
    _check_tenant(principal, body.tenant, service)
    trigger = body.trigger
    if trigger.kind == "user" and trigger.ref is None:
        trigger = trigger.model_copy(update={"ref": principal.subject})
    try:
        return await service.run_agent(
            agent_id,
            tenant=body.tenant,
            trigger=trigger,
            input_data=body.input,
            on_behalf_of_token=principal.raw_token if trigger.kind == "user" else None,
        )
    except UnknownAgent as exc:
        raise HTTPException(status.HTTP_404_NOT_FOUND, f"agente desconhecido: {agent_id}") from exc
    except ValueError as exc:
        raise HTTPException(422, str(exc)[:500]) from exc


# ---- runs ----


@router.get("/runs", tags=["runs"], response_model=list[AgentRunRecord])
def list_runs(
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
    agent_id: str | None = None,
    status_: str | None = Query(default=None, alias="status"),
    limit: int = Query(default=50, ge=1, le=200),
) -> list[AgentRunRecord]:
    service = _service(request)
    tenant = None if principal.has_any_role(service.settings.admin_roles) else principal.tenant
    return service.repository.list_runs(
        agent_id=agent_id, status=status_, tenant=tenant, limit=limit
    )


@router.get("/runs/{run_id}", tags=["runs"], response_model=AgentRunRecord)
def get_run(
    run_id: str,
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
) -> AgentRunRecord:
    service = _service(request)
    run = service.repository.get_run(run_id)
    if run is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "run não encontrado")
    _check_tenant(principal, run.tenant, service)
    return run


@router.post(
    "/runs/{run_id}/actions/{action_id}/approve", tags=["approvals"], response_model=AgentRunRecord
)
async def approve_action(
    run_id: str,
    action_id: str,
    body: DecisionRequest,
    request: Request,
    principal: Principal = Depends(require_settings_roles("approver_roles")),  # noqa: B008
) -> AgentRunRecord:
    service = _service(request)
    try:
        run = service.repository.get_run(run_id)
        if run is not None:
            _check_tenant(principal, run.tenant, service)
        return await service.approve_action(
            run_id, action_id, approver=principal.subject, justification=body.justification
        )
    except ApprovalError as exc:
        raise HTTPException(exc.status, exc.detail) from exc


@router.post(
    "/runs/{run_id}/actions/{action_id}/reject", tags=["approvals"], response_model=AgentRunRecord
)
async def reject_action(
    run_id: str,
    action_id: str,
    body: DecisionRequest,
    request: Request,
    principal: Principal = Depends(require_settings_roles("approver_roles")),  # noqa: B008
) -> AgentRunRecord:
    service = _service(request)
    try:
        run = service.repository.get_run(run_id)
        if run is not None:
            _check_tenant(principal, run.tenant, service)
        return await service.reject_action(
            run_id, action_id, approver=principal.subject, justification=body.justification
        )
    except ApprovalError as exc:
        raise HTTPException(exc.status, exc.detail) from exc


@router.get("/approvals", tags=["approvals"], response_model=list[AgentApproval])
def list_approvals(
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
    status_: str | None = Query(default="pending", alias="status"),
    agent_id: str | None = None,
) -> list[AgentApproval]:
    return _service(request).repository.list_approvals(status=status_, agent_id=agent_id)


# ---- admin ----


@router.get("/admin/kill-switch", tags=["admin"], response_model=KillSwitchResponse)
def get_kill_switch(
    request: Request,
    principal: Principal = Depends(require_settings_roles("admin_roles")),  # noqa: B008
) -> KillSwitchResponse:
    ks = _service(request).kill_switch
    return KillSwitchResponse(effective=ks.state(), admin=ks.admin_state())


@router.post("/admin/kill-switch", tags=["admin"], response_model=KillSwitchResponse)
def set_kill_switch(
    body: KillSwitchState,
    request: Request,
    principal: Principal = Depends(require_settings_roles("admin_roles")),  # noqa: B008
) -> KillSwitchResponse:
    ks = _service(request).kill_switch
    effective = ks.set_admin_state(body)
    return KillSwitchResponse(effective=effective, admin=ks.admin_state())


# ---- operacional ----


@router.get("/health", tags=["ops"], response_model=HealthResponse)
def health(request: Request) -> HealthResponse:
    service = _service(request)
    return HealthResponse(
        status="ok",
        service=service.settings.service_name,
        agents=sorted(service.catalog),
        llm_model=service.llm.model,
        kill_switch_global=service.kill_switch.state().global_,
    )


@router.get("/metrics", tags=["ops"])
def prometheus_metrics() -> Response:
    return Response(content=metrics.render(), media_type="text/plain; version=0.0.4")
