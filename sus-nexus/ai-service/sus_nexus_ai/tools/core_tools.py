"""Ferramentas registradas no ``ToolRegistry`` (alinhadas ao contrato real do core — Fase 2).

| ferramenta                    | classe            | endpoint do core                       |
|-------------------------------|-------------------|----------------------------------------|
| core.get_citizen_summary      | auto (leitura)    | GET  /citizens/{id}/summary            |
| core.get_regulation_request   | auto (leitura)    | GET  /regulation/requests/{id}         |
| core.get_exam_order           | auto (leitura)    | GET  /exams/orders/{id}                |
| core.get_hospital_episode     | auto (leitura)    | GET  /hospital/episodes/{id}           |
| core.list_care_gaps           | auto (leitura)    | GET  /caregaps?…                       |
| core.get_merge_case           | auto (leitura)    | GET  /mpi/cases/{id}                   |
| core.list_merge_case          | auto (leitura)    | GET  /mpi/cases[/{id}]                 |
| core.create_task              | auto              | POST /tasks (origin.kind=agent)        |
| core.create_pending_issue     | requires_approval | POST /regulation/requests/{id}/issues  |
| communication.request_message | requires_approval | **stub** (sem módulo de comunicação)   |
| regulation.change_priority    | forbidden         | —                                      |
| regulation.decide             | forbidden         | —                                      |
| production.transmit           | forbidden         | —                                      |
| mpi.merge                     | forbidden         | —                                      |
| bi.* (ver ``bi_tools.py``)    | auto (leitura)    | Trino — só ``marts_aggregated``        |

``core.create_pending_issue`` chama ``POST …/issues`` com ``origin={kind:"agent", id, version}``.
O core exige o papel ``agente_ia`` e cria a pendência já **aberta**: por isso a classe é
``requires_approval`` e a chamada só acontece em ``POST /runs/{run}/actions/{action}/approve``.

``communication.request_message`` permanece *stub*: o ``Domain`` ``communication`` existe no
contrato, mas nenhum endpoint foi publicado. ``HttpCoreClient`` responde 501 e a ferramenta
continua ``requires_approval`` para que o fluxo de aprovação já esteja coberto quando o módulo
existir.

As ferramentas ``forbidden`` existem no catálogo para que a negação seja explícita, auditável e
testável (bloqueadas por OPA e pelo executor; nunca executam).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from sus_nexus_ai.tools.bi_tools import register_bi_tools
from sus_nexus_ai.tools.core_client import (
    AgentOrigin,
    CareGapPage,
    CareGapQuery,
    CitizenOperationalSummary,
    ExamOrder,
    HospitalEpisode,
    IssueKind,
    IssueOrigin,
    MergeCase,
    MessageRequest,
    MessageRequestResult,
    Purpose,
    RegulationIssueCreate,
    RegulationRequest,
    Task,
    TaskCreate,
)
from sus_nexus_ai.tools.registry import ToolContext, ToolRegistry, ToolSpec


class ForbiddenToolInvoked(RuntimeError):
    """Nunca deve acontecer: a ferramenta é proibida por política e pelo executor."""


# ---- modelos de entrada ----


class GetCitizenSummaryInput(BaseModel):
    citizen_id: str = Field(pattern=r"^cit_[0-9A-Za-z_-]+$")
    purpose: Purpose = "care_coordination"


class GetRegulationRequestInput(BaseModel):
    request_id: str = Field(min_length=1)


class GetExamOrderInput(BaseModel):
    order_id: str = Field(min_length=1)


class GetHospitalEpisodeInput(BaseModel):
    episode_id: str = Field(pattern=r"^hep_[0-9A-Za-z]+$")


class GetMergeCaseInput(BaseModel):
    case_id: str = Field(min_length=1)


class ListMergeCaseInput(BaseModel):
    case_id: str | None = None
    status: str | None = None
    limit: int = Field(default=20, ge=1, le=100)


class ListMergeCaseOutput(BaseModel):
    items: list[MergeCase]


class CreatePendingIssueInput(BaseModel):
    """Entrada de ``core.create_pending_issue`` (a origem é preenchida pelo handler)."""

    request_id: str = Field(min_length=1)
    kind: IssueKind
    description: str = Field(min_length=10, max_length=1000)


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
        args.citizen_id,
        args.purpose,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


async def _get_regulation_request(
    ctx: ToolContext, args: GetRegulationRequestInput
) -> RegulationRequest:
    return await ctx.core.get_regulation_request(
        args.request_id,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


async def _get_exam_order(ctx: ToolContext, args: GetExamOrderInput) -> ExamOrder:
    return await ctx.core.get_exam_order(
        args.order_id,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


async def _get_hospital_episode(ctx: ToolContext, args: GetHospitalEpisodeInput) -> HospitalEpisode:
    return await ctx.core.get_hospital_episode(
        args.episode_id,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


async def _list_care_gaps(ctx: ToolContext, args: CareGapQuery) -> CareGapPage:
    return await ctx.core.list_care_gaps(
        args,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


async def _get_merge_case(ctx: ToolContext, args: GetMergeCaseInput) -> MergeCase:
    return await ctx.core.get_merge_case(
        args.case_id,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


async def _list_merge_case(ctx: ToolContext, args: ListMergeCaseInput) -> ListMergeCaseOutput:
    if args.case_id:
        case = await ctx.core.get_merge_case(
            args.case_id,
            token=ctx.token.access_token,
            tenant=ctx.tenant,
            correlation_id=ctx.correlation_id,
        )
        return ListMergeCaseOutput(items=[case])
    items = await ctx.core.list_merge_cases(
        args.status,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        limit=args.limit,
        correlation_id=ctx.correlation_id,
    )
    return ListMergeCaseOutput(items=items)


async def _create_task(ctx: ToolContext, args: TaskCreate) -> Task:
    # A origem é sempre o agente em execução — nunca o que o LLM/planejador informou.
    args = args.model_copy(
        update={"origin": AgentOrigin(kind="agent", id=ctx.agent_id, version=ctx.agent_version)}
    )
    return await ctx.core.create_task(
        args,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


async def _create_pending_issue(
    ctx: ToolContext, args: CreatePendingIssueInput
) -> RegulationRequest:
    issue = RegulationIssueCreate(
        kind=args.kind,
        description=args.description,
        origin=IssueOrigin(kind="agent", id=ctx.agent_id, version=ctx.agent_version),
    )
    return await ctx.core.add_regulation_issue(
        args.request_id,
        issue,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


async def _request_message(ctx: ToolContext, args: MessageRequest) -> MessageRequestResult:
    return await ctx.core.request_message(
        args,
        token=ctx.token.access_token,
        tenant=ctx.tenant,
        correlation_id=ctx.correlation_id,
    )


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
            description=(
                "Pedido de regulação (status, prioridade, justification_present, "
                "attached_documents_count, pendências abertas) — sem texto clínico."
            ),
            input_model=GetRegulationRequestInput,
            output_model=RegulationRequest,
            risk="low",
            action_class="auto",
            scope="regulation:request:read",
            handler=_get_regulation_request,
            kind="read",
        )
    )
    reg.register(
        ToolSpec(
            name="core.get_exam_order",
            description="Pedido de exame com resultados (só metadados: crítico, laudo, tarefa).",
            input_model=GetExamOrderInput,
            output_model=ExamOrder,
            risk="low",
            action_class="auto",
            scope="exam:order:read",
            handler=_get_exam_order,
            kind="read",
        )
    )
    reg.register(
        ToolSpec(
            name="core.get_hospital_episode",
            description=(
                "Episódio hospitalar (alta, disposition, LOS, reinternação, risco e versão da "
                "regra do core, seguimento/tarefa já criada) — o CID nunca vai ao LLM."
            ),
            input_model=GetHospitalEpisodeInput,
            output_model=HospitalEpisode,
            risk="low",
            action_class="auto",
            scope="hospital:episode:read",
            handler=_get_hospital_episode,
            kind="read",
        )
    )
    reg.register(
        ToolSpec(
            name="core.list_care_gaps",
            description=(
                "Lacunas de cuidado / busca ativa (GET /caregaps) por linha, tipo, unidade, "
                "equipe ou microárea; citizen_id opcional filtra no cliente."
            ),
            input_model=CareGapQuery,
            output_model=CareGapPage,
            risk="low",
            action_class="auto",
            scope="careplan:gap:read",
            handler=_list_care_gaps,
            kind="read",
        )
    )
    reg.register(
        ToolSpec(
            name="core.get_merge_case",
            description="Caso de possível duplicidade do MPI com evidências e conflitos.",
            input_model=GetMergeCaseInput,
            output_model=MergeCase,
            risk="low",
            action_class="auto",
            scope="mpi:case:read",
            handler=_get_merge_case,
            kind="read",
        )
    )
    reg.register(
        ToolSpec(
            name="core.list_merge_case",
            description="Fila de casos de duplicidade do MPI (ou um caso, por id).",
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
            description="Cria tarefa operacional no core (origin.kind=agent).",
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
            description=(
                "Registra pendência documental/administrativa num pedido de regulação "
                "(POST /regulation/requests/{id}/issues, origin.kind=agent) — só após aprovação."
            ),
            input_model=CreatePendingIssueInput,
            output_model=RegulationRequest,
            risk="medium",
            action_class="requires_approval",
            scope="regulation:issue:write",
            handler=_create_pending_issue,
            kind="write",
        )
    )
    reg.register(
        ToolSpec(
            name="communication.request_message",
            description=(
                "Solicita envio de mensagem ao cidadão por template (sem dado clínico). "
                "STUB: o core ainda não expõe módulo de comunicação."
            ),
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
    register_bi_tools(reg)
    return reg
