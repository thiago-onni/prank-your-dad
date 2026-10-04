"""Rotas HTTP do ai-service."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from pydantic import BaseModel, Field

from sus_nexus_ai import metrics
from sus_nexus_ai.api.auth import Principal, get_principal, require_settings_roles
from sus_nexus_ai.persistence.schemas import AgentApproval, AgentRunRecord, Trigger
from sus_nexus_ai.security.kill_switch import KillSwitchState
from sus_nexus_ai.service import AIService, ApprovalError, UnknownAgent

router = APIRouter()


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


def _check_tenant(principal: Principal, tenant: str, service: AIService) -> None:
    if principal.tenant and principal.tenant != tenant:
        if principal.has_any_role(service.settings.admin_roles):
            return
        raise HTTPException(status.HTTP_403_FORBIDDEN, "tenant do token difere do solicitado")


# ---- agentes e ferramentas ----


@router.get("/agents", tags=["agents"])
def list_agents(
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
) -> list[dict[str, Any]]:
    service = _service(request)
    return [
        {
            "id": d.id,
            "version": d.version,
            "prompt_version": d.prompt_version,
            "description": d.description,
            "tools": d.tools,
            "rule_versions": d.rule_versions,
            "output_schema": d.output_model.model_json_schema(),
        }
        for d in service.catalog.values()
    ]


@router.get("/tools", tags=["agents"])
def list_tools(
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
) -> list[dict[str, Any]]:
    return [spec.describe() for spec in _service(request).registry.list()]


@router.post("/agents/{agent_id}/run", tags=["agents"], response_model=AgentRunRecord)
async def run_agent(
    agent_id: str,
    body: RunRequest,
    request: Request,
    principal: Principal = Depends(get_principal),  # noqa: B008
) -> AgentRunRecord:
    service = _service(request)
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


@router.get("/health", tags=["ops"])
def health(request: Request) -> dict[str, Any]:
    service = _service(request)
    return {
        "status": "ok",
        "service": service.settings.service_name,
        "agents": sorted(service.catalog),
        "llm_model": service.llm.model,
        "kill_switch_global": service.kill_switch.state().global_,
    }


@router.get("/metrics", tags=["ops"])
def prometheus_metrics() -> Response:
    return Response(content=metrics.render(), media_type="text/plain; version=0.0.4")
