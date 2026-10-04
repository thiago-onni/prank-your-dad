"""Cliente da API pública do core-municipal (``contracts/openapi/core-municipal.yaml``).

O agente **nunca** acessa banco: todo efeito passa pela API (AIA-006). ``HttpCoreClient``
usa httpx; ``InMemoryCoreClient`` serve dev/testes/evals com fixtures determinísticas e
reproduz os mesmos *shapes* de resposta do core.

Endpoints usados (todos com ``Authorization: Bearer`` da identidade do agente,
``X-Tenant-Id``, ``X-Purpose-Of-Use`` e ``X-Correlation-Id``):

| método                     | endpoint (prefixo ``/api/v1``)        | purpose             |
|----------------------------|---------------------------------------|---------------------|
| ``get_citizen_summary``    | ``GET  /citizens/{id}/summary``       | parâmetro           |
| ``get_regulation_request`` | ``GET  /regulation/requests/{id}``    | regulation          |
| ``add_regulation_issue``   | ``POST /regulation/requests/{id}/issues`` | regulation      |
| ``get_exam_order``         | ``GET  /exams/orders/{id}``           | care_coordination   |
| ``get_merge_case``         | ``GET  /mpi/cases/{id}``              | identity_management |
| ``list_merge_cases``       | ``GET  /mpi/cases``                   | identity_management |
| ``create_task``            | ``POST /tasks``                       | care_coordination   |
| ``request_message``        | *stub* — sem módulo de comunicação    | —                   |

``POST …/issues`` exige o papel ``agente_ia`` e o core cria a pendência **já aberta**; por isso o
ai-service só o chama depois de aprovação humana (ferramenta ``requires_approval``).
"""

from __future__ import annotations

import contextlib
from datetime import datetime
from typing import Any, Literal, Protocol

import httpx
from pydantic import BaseModel, ConfigDict, Field

from sus_nexus_ai.common import new_id, utcnow

# ---------------------------------------------------------------------------
# Modelos (espelho do OpenAPI do core)
# ---------------------------------------------------------------------------

Purpose = Literal[
    "care_coordination",
    "regulation",
    "scheduling",
    "identity_management",
    "production_audit",
    "public_health_surveillance",
    "management_analytics",
    "integration_operations",
    "security_audit",
]

HEADER_TENANT = "X-Tenant-Id"
HEADER_PURPOSE = "X-Purpose-Of-Use"
HEADER_CORRELATION = "X-Correlation-Id"
HEADER_IDEMPOTENCY = "Idempotency-Key"


class CitizenOperationalSummary(BaseModel):
    """``CitizenOperationalSummary`` (JOR-008)."""

    model_config = ConfigDict(extra="allow")

    citizen_id: str
    last_aps_encounter_at: datetime | None = None
    next_appointment_at: datetime | None = None
    open_tasks: int = 0
    open_regulation_requests: int = 0
    pending_exams: int = 0
    last_hospital_discharge_at: datetime | None = None
    care_lines: list[str] = Field(default_factory=list)
    care_gaps: int = 0
    contact_valid: bool | None = None


class MaskedIdentifier(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str | None = None
    system: str
    value_masked: str
    status: Literal["active", "deprecated", "invalid"] = "active"
    source_system: str | None = None


class CitizenSummary(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    display_name: str
    birthdate: str | None = None
    sex: str | None = None
    mother_name_masked: str | None = None
    registration_state: str = "validated"
    identifiers: list[MaskedIdentifier] = Field(default_factory=list)
    health_unit_cnes: str | None = None
    team_ine: str | None = None
    microarea: str | None = None
    identity_confidence: str | None = None


class MergeEvidence(BaseModel):
    attribute: str
    comparison: str | None = None
    agreement: Literal["agree", "disagree", "missing"]
    weight: float = 0.0


MergeCaseStatus = Literal["open", "in_review", "merged", "rejected", "unmerged"]


class MergeCase(BaseModel):
    """``MergeCase`` — ``GET /api/v1/mpi/cases/{caseId}``."""

    model_config = ConfigDict(extra="allow")

    id: str
    status: MergeCaseStatus = "open"
    reason: str | None = None
    score: float | None = None
    rule_version: str | None = None
    candidates: list[CitizenSummary]
    evidence: list[MergeEvidence] = Field(default_factory=list)
    conflicts: list[str] = Field(default_factory=list)
    opened_at: datetime
    decided_at: datetime | None = None
    decided_by: str | None = None
    decision_reason: str | None = None
    merge_id: str | None = None


class Assignee(BaseModel):
    kind: Literal["user", "team", "health_unit", "queue"]
    id: str


class AgentOrigin(BaseModel):
    """``origin`` de tarefas e pendências criadas por agente (``kind="agent"``)."""

    kind: Literal["workflow", "agent", "user", "rule", "connector"] = "agent"
    id: str
    version: str | None = None


TaskOrigin = AgentOrigin


class TaskCreate(BaseModel):
    """``TaskCreate`` — ``POST /api/v1/tasks``."""

    task_type: str
    priority: Literal["low", "medium", "high", "urgent"]
    title: str = Field(max_length=200)
    description: str | None = Field(default=None, max_length=2000)
    citizen_id: str | None = None
    assignee: Assignee | None = None
    due_at: datetime | None = None
    sla_policy_id: str | None = None
    origin: AgentOrigin | None = None


class Task(TaskCreate):
    model_config = ConfigDict(extra="allow")

    id: str
    status: str = "open"
    created_at: datetime


RegulationKind = Literal["consultation", "exam", "procedure", "surgery", "admission"]
RegulationStatus = Literal[
    "requested",
    "pending_documents",
    "returned",
    "under_review",
    "authorized",
    "denied",
    "scheduled",
    "cancelled",
    "no_show",
    "performed",
    "expired",
]
RegulationPriority = Literal["elective", "priority", "urgent", "emergency"]
IssueKind = Literal[
    "missing_document", "missing_field", "clinical_justification", "duplicate", "other"
]
RegulationIssueKind = Literal[
    "missing_document",
    "missing_field",
    "clinical_justification",
    "duplicate",
    "other",
    "sla_breached",
    "no_capacity",
    "expired",
]


class IssueOrigin(BaseModel):
    kind: Literal["user", "agent", "rule", "workflow", "connector"] = "agent"
    id: str
    version: str | None = None


class RegulationIssue(BaseModel):
    """``RegulationIssue`` (pendência já registrada no pedido)."""

    model_config = ConfigDict(extra="allow")

    id: str
    kind: RegulationIssueKind
    status: Literal["open", "resolved"] = "open"
    description: str | None = None
    origin: IssueOrigin | None = None
    created_at: datetime
    resolved_at: datetime | None = None


class RegulationIssueCreate(BaseModel):
    """Corpo de ``POST /api/v1/regulation/requests/{requestId}/issues``."""

    kind: IssueKind
    description: str = Field(min_length=1, max_length=1000)
    origin: IssueOrigin | None = None


class RegulationRequest(BaseModel):
    """``RegulationRequest`` — ``GET /api/v1/regulation/requests/{requestId}``.

    Nunca traz a justificativa clínica em texto: só ``justification_present`` (REG-001/002).
    """

    model_config = ConfigDict(extra="allow")

    id: str
    citizen_id: str
    kind: RegulationKind
    status: RegulationStatus
    priority: RegulationPriority | None = None
    requested_at: datetime
    requested_service_code: str
    code_system: str | None = None
    service_description: str | None = None
    specialty: str | None = None
    requesting_cnes: str | None = None
    requesting_unit_name: str | None = None
    requesting_professional_id: str | None = None
    provider_cnes: str | None = None
    provider_name: str | None = None
    scheduled_at: datetime | None = None
    appointment_id: str | None = None
    regulator_id: str | None = None
    decision_reason: str | None = None
    justification_present: bool | None = None
    attached_documents_count: int | None = None
    waiting_days: int = 0
    sla_due_at: datetime | None = None
    sla_breached: bool | None = None
    issues: list[RegulationIssue] = Field(default_factory=list)
    status_history: list[dict[str, Any]] = Field(default_factory=list)
    source_system: str
    source_record_id: str | None = None
    version: int | None = None

    def open_issue_kinds(self) -> set[str]:
        return {i.kind for i in self.issues if i.status == "open"}


ExamOrderStatus = Literal[
    "requested",
    "authorized",
    "scheduled",
    "collected",
    "performed",
    "reported",
    "cancelled",
    "not_performed",
]
ExamResultStatus = Literal["final", "preliminary", "amended", "inconclusive", "cancelled"]
ExamIssue = Literal[
    "not_scheduled",
    "no_result_followup",
    "result_pending",
    "integration_failure",
    "inconclusive",
    "critical",
]


class ExamResult(BaseModel):
    """``ExamResult`` — só metadados; nunca valores nem laudo (EXA-006/007)."""

    model_config = ConfigDict(extra="allow")

    id: str
    reported_at: datetime
    status: ExamResultStatus
    critical: bool | None = None
    performer_cnes: str | None = None
    has_document: bool | None = None
    observations_count: int | None = None
    followup_task_id: str | None = None
    source_system: str


class ExamOrder(BaseModel):
    """``ExamOrder`` — ``GET /api/v1/exams/orders/{orderId}``."""

    model_config = ConfigDict(extra="allow")

    id: str
    citizen_id: str
    status: ExamOrderStatus
    requested_at: datetime
    exam_code: str
    code_system: str | None = None
    exam_description: str | None = None
    category: str | None = None
    priority: str | None = None
    requesting_cnes: str | None = None
    requesting_unit_name: str | None = None
    requesting_professional_id: str | None = None
    performer_cnes: str | None = None
    regulation_request_id: str | None = None
    appointment_id: str | None = None
    scheduled_at: datetime | None = None
    performed_at: datetime | None = None
    reported_at: datetime | None = None
    care_line: str | None = None
    issues: list[ExamIssue] = Field(default_factory=list)
    results: list[ExamResult] = Field(default_factory=list)
    status_history: list[dict[str, Any]] = Field(default_factory=list)
    cycle_times: dict[str, float] | None = None
    source_system: str
    source_record_id: str | None = None
    version: int | None = None


class MessageRequest(BaseModel):
    """STUB: o core ainda não expõe módulo de comunicação (``Domain=communication``)."""

    citizen_id: str
    channel: Literal["sms", "whatsapp", "phone_call", "letter"]
    template_id: str
    purpose: Purpose = "care_coordination"
    variables: dict[str, str] = Field(default_factory=dict)


class MessageRequestResult(BaseModel):
    id: str
    status: str = "queued"


# ---------------------------------------------------------------------------
# Cliente
# ---------------------------------------------------------------------------


class CoreError(Exception):
    def __init__(self, status: int, detail: str) -> None:
        self.status = status
        super().__init__(f"core {status}: {detail}")


class CoreClient(Protocol):
    async def get_citizen_summary(
        self,
        citizen_id: str,
        purpose: Purpose,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> CitizenOperationalSummary: ...

    async def get_merge_case(
        self, case_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> MergeCase: ...

    async def list_merge_cases(
        self,
        status: str | None,
        *,
        token: str,
        tenant: str,
        limit: int = 20,
        correlation_id: str | None = None,
    ) -> list[MergeCase]: ...

    async def create_task(
        self, task: TaskCreate, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> Task: ...

    async def get_regulation_request(
        self, request_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> RegulationRequest: ...

    async def add_regulation_issue(
        self,
        request_id: str,
        issue: RegulationIssueCreate,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> RegulationRequest: ...

    async def get_exam_order(
        self, order_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> ExamOrder: ...

    async def request_message(
        self,
        message: MessageRequest,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> MessageRequestResult: ...


class HttpCoreClient:
    def __init__(
        self, base_url: str, timeout_seconds: float = 10.0, client: httpx.AsyncClient | None = None
    ) -> None:
        self._client = client or httpx.AsyncClient(base_url=base_url, timeout=timeout_seconds)

    @staticmethod
    def _headers(
        token: str,
        tenant: str,
        purpose: Purpose,
        correlation_id: str | None,
        *,
        idempotent: bool = False,
    ) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {token}",
            HEADER_TENANT: tenant,
            HEADER_PURPOSE: purpose,
            HEADER_CORRELATION: correlation_id or new_id("corr_"),
        }
        if idempotent:
            headers[HEADER_IDEMPOTENCY] = new_id("idem_")
        return headers

    async def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        resp = await self._client.request(method, path, **kwargs)
        if resp.status_code >= 400:
            detail = resp.text[:200]
            with contextlib.suppress(ValueError):
                detail = str(resp.json().get("title", detail))
            raise CoreError(resp.status_code, detail)
        return resp.json()

    async def get_citizen_summary(
        self,
        citizen_id: str,
        purpose: Purpose,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> CitizenOperationalSummary:
        data = await self._request(
            "GET",
            f"/api/v1/citizens/{citizen_id}/summary",
            headers=self._headers(token, tenant, purpose, correlation_id),
        )
        return CitizenOperationalSummary.model_validate(data)

    async def get_merge_case(
        self, case_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> MergeCase:
        data = await self._request(
            "GET",
            f"/api/v1/mpi/cases/{case_id}",
            headers=self._headers(token, tenant, "identity_management", correlation_id),
        )
        return MergeCase.model_validate(data)

    async def list_merge_cases(
        self,
        status: str | None,
        *,
        token: str,
        tenant: str,
        limit: int = 20,
        correlation_id: str | None = None,
    ) -> list[MergeCase]:
        params: dict[str, Any] = {"limit": limit}
        if status:
            params["status"] = status
        data = await self._request(
            "GET",
            "/api/v1/mpi/cases",
            params=params,
            headers=self._headers(token, tenant, "identity_management", correlation_id),
        )
        return [MergeCase.model_validate(item) for item in data.get("items", [])]

    async def create_task(
        self, task: TaskCreate, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> Task:
        data = await self._request(
            "POST",
            "/api/v1/tasks",
            json=task.model_dump(mode="json", exclude_none=True),
            headers=self._headers(
                token, tenant, "care_coordination", correlation_id, idempotent=True
            ),
        )
        return Task.model_validate(data)

    async def get_regulation_request(
        self, request_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> RegulationRequest:
        data = await self._request(
            "GET",
            f"/api/v1/regulation/requests/{request_id}",
            headers=self._headers(token, tenant, "regulation", correlation_id),
        )
        return RegulationRequest.model_validate(data)

    async def add_regulation_issue(
        self,
        request_id: str,
        issue: RegulationIssueCreate,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> RegulationRequest:
        data = await self._request(
            "POST",
            f"/api/v1/regulation/requests/{request_id}/issues",
            json=issue.model_dump(mode="json", exclude_none=True),
            headers=self._headers(token, tenant, "regulation", correlation_id, idempotent=True),
        )
        return RegulationRequest.model_validate(data)

    async def get_exam_order(
        self, order_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> ExamOrder:
        data = await self._request(
            "GET",
            f"/api/v1/exams/orders/{order_id}",
            headers=self._headers(token, tenant, "care_coordination", correlation_id),
        )
        return ExamOrder.model_validate(data)

    async def request_message(
        self,
        message: MessageRequest,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> MessageRequestResult:
        # STUB: não existe endpoint de comunicação no contrato do core. Falha explicitamente
        # (501) em vez de inventar um caminho — a ferramenta está marcada como ``stub``.
        raise CoreError(501, "módulo de comunicação ainda não existe no core-municipal")

    async def aclose(self) -> None:
        await self._client.aclose()


class CreatedIssue(BaseModel):
    """Registro de ``add_regulation_issue`` no cliente em memória (para testes)."""

    request_id: str
    kind: IssueKind
    description: str
    origin: IssueOrigin | None = None
    issue_id: str


class InMemoryCoreClient:
    """Fixtures em memória para dev, testes e evals. Guarda tudo que foi criado."""

    def __init__(self) -> None:
        self.summaries: dict[str, CitizenOperationalSummary] = {}
        self.merge_cases: dict[str, MergeCase] = {}
        self.regulation_requests: dict[str, RegulationRequest] = {}
        self.exam_orders: dict[str, ExamOrder] = {}
        self.tasks: list[Task] = []
        self.issues: list[CreatedIssue] = []
        self.messages: list[MessageRequest] = []
        self.calls: list[tuple[str, str, str]] = []  # (método, token, tenant)
        self.correlation_ids: list[str | None] = []

    def _record(self, method: str, token: str, tenant: str, correlation_id: str | None) -> None:
        self.calls.append((method, token, tenant))
        self.correlation_ids.append(correlation_id)

    async def get_citizen_summary(
        self,
        citizen_id: str,
        purpose: Purpose,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> CitizenOperationalSummary:
        self._record("get_citizen_summary", token, tenant, correlation_id)
        return self.summaries.get(citizen_id) or CitizenOperationalSummary(citizen_id=citizen_id)

    async def get_merge_case(
        self, case_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> MergeCase:
        self._record("get_merge_case", token, tenant, correlation_id)
        try:
            return self.merge_cases[case_id]
        except KeyError as exc:
            raise CoreError(404, f"merge case {case_id} não encontrado") from exc

    async def list_merge_cases(
        self,
        status: str | None,
        *,
        token: str,
        tenant: str,
        limit: int = 20,
        correlation_id: str | None = None,
    ) -> list[MergeCase]:
        self._record("list_merge_cases", token, tenant, correlation_id)
        items = [c for c in self.merge_cases.values() if status is None or c.status == status]
        return items[:limit]

    async def create_task(
        self, task: TaskCreate, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> Task:
        self._record("create_task", token, tenant, correlation_id)
        created = Task(id=new_id("task_"), created_at=utcnow(), **task.model_dump())
        self.tasks.append(created)
        return created

    async def get_regulation_request(
        self, request_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> RegulationRequest:
        self._record("get_regulation_request", token, tenant, correlation_id)
        try:
            return self.regulation_requests[request_id]
        except KeyError as exc:
            raise CoreError(404, f"pedido {request_id} não encontrado") from exc

    async def add_regulation_issue(
        self,
        request_id: str,
        issue: RegulationIssueCreate,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> RegulationRequest:
        self._record("add_regulation_issue", token, tenant, correlation_id)
        request = self.regulation_requests.get(request_id)
        if request is None:
            raise CoreError(404, f"pedido {request_id} não encontrado")
        created = RegulationIssue(
            id=new_id("iss_"),
            kind=issue.kind,
            status="open",  # o core cria a pendência já aberta
            description=issue.description,
            origin=issue.origin,
            created_at=utcnow(),
        )
        request.issues.append(created)
        if request.status == "requested":
            request.status = "pending_documents"
        self.issues.append(
            CreatedIssue(
                request_id=request_id,
                kind=issue.kind,
                description=issue.description,
                origin=issue.origin,
                issue_id=created.id,
            )
        )
        return request

    async def get_exam_order(
        self, order_id: str, *, token: str, tenant: str, correlation_id: str | None = None
    ) -> ExamOrder:
        self._record("get_exam_order", token, tenant, correlation_id)
        try:
            return self.exam_orders[order_id]
        except KeyError as exc:
            raise CoreError(404, f"pedido de exame {order_id} não encontrado") from exc

    async def request_message(
        self,
        message: MessageRequest,
        *,
        token: str,
        tenant: str,
        correlation_id: str | None = None,
    ) -> MessageRequestResult:
        self._record("request_message", token, tenant, correlation_id)
        self.messages.append(message)
        return MessageRequestResult(id=new_id("msg_"))
