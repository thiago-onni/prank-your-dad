"""Ferramentas iniciais registradas no ``ToolRegistry``.

| ferramenta                      | classe             | risco  |
|---------------------------------|--------------------|--------|
| core.get_citizen_summary        | auto (leitura)     | low    |
| core.get_regulation_request     | auto (leitura, stub)| low   |
| core.list_merge_case            | auto (leitura)     | low    |
| core.create_task                | auto               | low    |
| core.create_pending_issue       | requires_approval  | medium |
| communication.request_message   | requires_approval  | medium |
| regulation.change_priority      | forbidden          | high   |
| regulation.decide               | forbidden          | high   |
| production.transmit             | forbidden          | high   |
| mpi.merge                       | forbidden          | high   |

As ferramentas ``forbidden`` existem no catálogo para que a negação seja explícita, auditável e
testável (bloqueadas por OPA e pelo executor; nunca executam).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from sus_nexus_ai.tools.core_client import (
    CitizenOperationalSummary,
    MergeCase,
    MessageRequest,
    MessageRequestResult,
    PendingIssue,
    PendingIssueCreate,
    Purpose,
    RegulationRequest,
    Task,
    TaskCreate,
    TaskOrigin,
)
from sus_nexus_ai.tools.registry import ToolContext, ToolRegistry, ToolSpec


class ForbiddenToolInvoked(RuntimeError):
    """Nunca deve acontecer: a ferramenta é proibida por política e pelo executor."""


# ---- modelos de entrada ----


class GetCitizenSummaryInput(BaseModel):
    citizen_id: str = Field(pattern=r"^cit_[0-9A-Za-z]{26}$|^cit_[0-9A-Za-z_-]+$")
    purpose: Purpose = "care_coordination"


class GetRegulationRequestInput(BaseModel):
    request_id: str


class ListMergeCaseInput(BaseModel):
    case_id: str | None = None
    status: str | None = None
    limit: int = Field(default=20, ge=1, le=100)


class ListMergeCaseOutput(BaseModel):
    items: list[MergeCase]


class EmptyOutput(BaseModel):
    ok: bool = True


class ChangePriorityInput(BaseModel):
    request_id: str
    priority: str
    reason: str


class DecideInput(BaseModel):
    request_id: str
    decision: str
    reason: str


class TransmitInput(BaseModel):
    batch_id: str


class MergeInput(BaseModel):
    case_id: str
    surviving_citizen_id: str
    reason: str


# ---- handlers ----


async def _get_citizen_summary(
    ctx: ToolContext, args: GetCitizenSummaryInput
) -> CitizenOperationalSummary:
    return await ctx.core.get_citizen_summary(
        args.citizen_id, args.purpose, token=ctx.token.access_token, tenant=ctx.tenant
    )


async def _get_regulation_request(
    ctx: ToolContext, args: GetRegulationRequestInput
) -> RegulationRequest:
    return await ctx.core.get_regulation_request(
        args.request_id, token=ctx.token.access_token, tenant=ctx.tenant
    )


async def _list_merge_case(ctx: ToolContext, args: ListMergeCaseInput) -> ListMergeCaseOutput:
    if args.case_id:
        case = await ctx.core.get_merge_case(
            args.case_id, token=ctx.token.access_token, tenant=ctx.tenant
        )
        return ListMergeCaseOutput(items=[case])
    items = await ctx.core.list_merge_cases(
        args.status, token=ctx.token.access_token, tenant=ctx.tenant, limit=args.limit
    )
    return ListMergeCaseOutput(items=items)


async def _create_task(ctx: ToolContext, args: TaskCreate) -> Task:
    if args.origin is None:
        args = args.model_copy(
            update={"origin": TaskOrigin(kind="agent", id=ctx.agent_id, version=ctx.agent_version)}
        )
    return await ctx.core.create_task(args, token=ctx.token.access_token, tenant=ctx.tenant)


async def _create_pending_issue(ctx: ToolContext, args: PendingIssueCreate) -> PendingIssue:
    return await ctx.core.create_pending_issue(
        args, token=ctx.token.access_token, tenant=ctx.tenant
    )


async def _request_message(ctx: ToolContext, args: MessageRequest) -> MessageRequestResult:
    return await ctx.core.request_message(args, token=ctx.token.access_token, tenant=ctx.tenant)


async def _forbidden(ctx: ToolContext, args: Any) -> EmptyOutput:
    raise ForbiddenToolInvoked("ferramenta proibida para agentes")


def build_default_registry() -> ToolRegistry:
    reg = ToolRegistry()
    reg.register(
        ToolSpec(
            name="core.get_citizen_summary",
            description="Resumo operacional do cidadão (JOR-008), sem dado clínico além do mínimo.",
            input_model=GetCitizenSummaryInput,
            output_model=CitizenOperationalSummary,
            risk="low",
            action_class="auto",
            scope="citizen:summary:read",
            handler=_get_citizen_summary,
            kind="read",
        )
    )
    reg.register(
        ToolSpec(
            name="core.get_regulation_request",
            description="Pedido de regulação (campos administrativos e documentos anexos).",
            input_model=GetRegulationRequestInput,
            output_model=RegulationRequest,
            risk="low",
            action_class="auto",
            scope="regulation:request:read",
            handler=_get_regulation_request,
            kind="read",
            stub=True,
        )
    )
    reg.register(
        ToolSpec(
            name="core.list_merge_case",
            description="Caso(s) de possível duplicidade do MPI com evidências por atributo.",
            input_model=ListMergeCaseInput,
            output_model=ListMergeCaseOutput,
            risk="low",
            action_class="auto",
            scope="mpi:case:read",
            handler=_list_merge_case,
            kind="read",
        )
    )
    reg.register(
        ToolSpec(
            name="core.create_task",
            description="Cria tarefa operacional no core (origem = agente).",
            input_model=TaskCreate,
            output_model=Task,
            risk="low",
            action_class="auto",
            scope="task:write",
            handler=_create_task,
            kind="write",
        )
    )
    reg.register(
        ToolSpec(
            name="core.create_pending_issue",
            description="Abre pendência administrativa num pedido de regulação (devolve à origem).",
            input_model=PendingIssueCreate,
            output_model=PendingIssue,
            risk="medium",
            action_class="requires_approval",
            scope="regulation:pending-issue:write",
            handler=_create_pending_issue,
            kind="write",
            stub=True,
        )
    )
    reg.register(
        ToolSpec(
            name="communication.request_message",
            description="Solicita envio de mensagem ao cidadão por template (sem dado clínico).",
            input_model=MessageRequest,
            output_model=MessageRequestResult,
            risk="medium",
            action_class="requires_approval",
            scope="communication:message:write",
            handler=_request_message,
            kind="write",
            owner="communication",
            stub=True,
        )
    )
    for name, model, desc, scope in (
        (
            "regulation.change_priority",
            ChangePriorityInput,
            "Alterar prioridade clínica (REG-009) — PROIBIDO para agentes.",
            "regulation:priority:write",
        ),
        (
            "regulation.decide",
            DecideInput,
            "Decisão regulatória (autorizar/negar) — PROIBIDO para agentes.",
            "regulation:decision:write",
        ),
        (
            "production.transmit",
            TransmitInput,
            "Transmitir produção (PRO-010) — PROIBIDO para agentes.",
            "production:transmit",
        ),
        (
            "mpi.merge",
            MergeInput,
            "Fundir cadastros (MPI-007) — PROIBIDO para agentes.",
            "mpi:merge",
        ),
    ):
        reg.register(
            ToolSpec(
                name=name,
                description=desc,
                input_model=model,
                output_model=EmptyOutput,
                risk="high",
                action_class="forbidden",
                scope=scope,
                handler=_forbidden,
                kind="write",
            )
        )
    return reg
