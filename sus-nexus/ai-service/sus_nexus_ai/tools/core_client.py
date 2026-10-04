"""Cliente da API pública do core-municipal (contracts/openapi/core-municipal.yaml).

O agente **nunca** acessa banco: todo efeito passa pela API (AIA-006). ``HttpCoreClient``
usa httpx; ``InMemoryCoreClient`` serve dev/testes/evals com fixtures determinísticas.
"""

from __future__ import annotations

import contextlib
from datetime import datetime
from typing import Any, Literal, Protocol

import httpx
from pydantic import BaseModel, ConfigDict, Field

from sus_nexus_ai.common import new_id, utcnow

# ---------------------------------------------------------------------------
# Modelos (espelho do OpenAPI do core; campos mínimos)
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


class CitizenOperationalSummary(BaseModel):
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
    system: str
    value_masked: str


class CitizenSummary(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    display_name: str
    birthdate: str | None = None
    sex: str | None = None
    mother_name_masked: str | None = None
    registration_state: str = "active"
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


class MergeCase(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    status: Literal["open", "in_review", "merged", "rejected", "unmerged"] = "open"
    reason: str | None = None
    score: float | None = None
    rule_version: str | None = None
    candidates: list[CitizenSummary]
    evidence: list[MergeEvidence] = Field(default_factory=list)
    conflicts: list[str] = Field(default_factory=list)
    opened_at: datetime


class Assignee(BaseModel):
    kind: Literal["user", "team", "health_unit", "queue"]
    id: str


class TaskOrigin(BaseModel):
    kind: Literal["workflow", "agent", "user", "rule", "connector"] = "agent"
    id: str
    version: str | None = None


class TaskCreate(BaseModel):
    task_type: str
    priority: Literal["low", "medium", "high", "urgent"]
    title: str = Field(max_length=200)
    description: str | None = Field(default=None, max_length=2000)
    citizen_id: str | None = None
    assignee: Assignee | None = None
    due_at: datetime | None = None
    sla_policy_id: str | None = None
    origin: TaskOrigin | None = None


class Task(TaskCreate):
    model_config = ConfigDict(extra="allow")

    id: str
    status: str = "open"
    created_at: datetime


class RegulationRequest(BaseModel):
    """Modelo LOCAL (stub): o core ainda não expõe regulação na OpenAPI.

    Quando ``/api/v1/regulation/requests/{id}`` existir, este modelo deve ser alinhado ao contrato.
    """

    model_config = ConfigDict(extra="allow")

    id: str
    citizen_id: str
    status: str = "open"
    specialty: str | None = None
    procedure_code: str | None = None
    procedure_description: str | None = None
    priority_requested: str | None = None
    clinical_justification: str | None = None
    cid10: str | None = None
    requesting_unit_cnes: str | None = None
    requesting_professional_name: str | None = None
    requesting_professional_cbo: str | None = None
    attachments: list[str] = Field(default_factory=list)
    required_documents: list[str] = Field(default_factory=list)
    created_at: datetime | None = None


class PendingIssueCreate(BaseModel):
    request_id: str
    reason: str = Field(min_length=10, max_length=1000)
    missing_fields: list[str] = Field(default_factory=list)
    return_to: Literal["requesting_unit", "citizen"] = "requesting_unit"


class PendingIssue(PendingIssueCreate):
    id: str
    status: str = "open"
    created_at: datetime


class MessageRequest(BaseModel):
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
        self, citizen_id: str, purpose: Purpose, *, token: str, tenant: str
    ) -> CitizenOperationalSummary: ...

    async def get_merge_case(self, case_id: str, *, token: str, tenant: str) -> MergeCase: ...

    async def list_merge_cases(
        self, status: str | None, *, token: str, tenant: str, limit: int = 20
    ) -> list[MergeCase]: ...

    async def create_task(self, task: TaskCreate, *, token: str, tenant: str) -> Task: ...

    async def get_regulation_request(
        self, request_id: str, *, token: str, tenant: str
    ) -> RegulationRequest: ...

    async def create_pending_issue(
        self, issue: PendingIssueCreate, *, token: str, tenant: str
    ) -> PendingIssue: ...

    async def request_message(
        self, message: MessageRequest, *, token: str, tenant: str
    ) -> MessageRequestResult: ...


class HttpCoreClient:
    def __init__(
        self, base_url: str, timeout_seconds: float = 10.0, client: httpx.AsyncClient | None = None
    ) -> None:
        self._client = client or httpx.AsyncClient(base_url=base_url, timeout=timeout_seconds)

    @staticmethod
    def _headers(token: str, tenant: str, idempotency_key: str | None = None) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {token}", "X-Tenant-Id": tenant}
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
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
        self, citizen_id: str, purpose: Purpose, *, token: str, tenant: str
    ) -> CitizenOperationalSummary:
        data = await self._request(
            "GET",
            f"/api/v1/citizens/{citizen_id}/summary",
            params={"purpose": purpose},
            headers=self._headers(token, tenant),
        )
        return CitizenOperationalSummary.model_validate(data)

    async def get_merge_case(self, case_id: str, *, token: str, tenant: str) -> MergeCase:
        data = await self._request(
            "GET", f"/api/v1/mpi/cases/{case_id}", headers=self._headers(token, tenant)
        )
        return MergeCase.model_validate(data)

    async def list_merge_cases(
        self, status: str | None, *, token: str, tenant: str, limit: int = 20
    ) -> list[MergeCase]:
        params: dict[str, Any] = {"limit": limit}
        if status:
            params["status"] = status
        data = await self._request(
            "GET", "/api/v1/mpi/cases", params=params, headers=self._headers(token, tenant)
        )
        return [MergeCase.model_validate(item) for item in data.get("items", [])]

    async def create_task(self, task: TaskCreate, *, token: str, tenant: str) -> Task:
        data = await self._request(
            "POST",
            "/api/v1/tasks",
            json=task.model_dump(mode="json", exclude_none=True),
            headers=self._headers(token, tenant, idempotency_key=new_id("idem_")),
        )
        return Task.model_validate(data)

    async def get_regulation_request(
        self, request_id: str, *, token: str, tenant: str
    ) -> RegulationRequest:
        # STUB: endpoint ainda não existe no contrato do core.
        data = await self._request(
            "GET",
            f"/api/v1/regulation/requests/{request_id}",
            headers=self._headers(token, tenant),
        )
        return RegulationRequest.model_validate(data)

    async def create_pending_issue(
        self, issue: PendingIssueCreate, *, token: str, tenant: str
    ) -> PendingIssue:
        # STUB: endpoint ainda não existe no contrato do core.
        data = await self._request(
            "POST",
            f"/api/v1/regulation/requests/{issue.request_id}/pending-issues",
            json=issue.model_dump(mode="json"),
            headers=self._headers(token, tenant, idempotency_key=new_id("idem_")),
        )
        return PendingIssue.model_validate(data)

    async def request_message(
        self, message: MessageRequest, *, token: str, tenant: str
    ) -> MessageRequestResult:
        # STUB: endpoint ainda não existe no contrato do core.
        data = await self._request(
            "POST",
            "/api/v1/communication/messages",
            json=message.model_dump(mode="json"),
            headers=self._headers(token, tenant, idempotency_key=new_id("idem_")),
        )
        return MessageRequestResult.model_validate(data)

    async def aclose(self) -> None:
        await self._client.aclose()


class InMemoryCoreClient:
    """Fixtures em memória para dev, testes e evals. Guarda tudo que foi criado."""

    def __init__(self) -> None:
        self.summaries: dict[str, CitizenOperationalSummary] = {}
        self.merge_cases: dict[str, MergeCase] = {}
        self.regulation_requests: dict[str, RegulationRequest] = {}
        self.tasks: list[Task] = []
        self.pending_issues: list[PendingIssue] = []
        self.messages: list[MessageRequest] = []
        self.calls: list[tuple[str, str, str]] = []  # (método, token, tenant)

    async def get_citizen_summary(
        self, citizen_id: str, purpose: Purpose, *, token: str, tenant: str
    ) -> CitizenOperationalSummary:
        self.calls.append(("get_citizen_summary", token, tenant))
        return self.summaries.get(citizen_id) or CitizenOperationalSummary(citizen_id=citizen_id)

    async def get_merge_case(self, case_id: str, *, token: str, tenant: str) -> MergeCase:
        self.calls.append(("get_merge_case", token, tenant))
        try:
            return self.merge_cases[case_id]
        except KeyError as exc:
            raise CoreError(404, f"merge case {case_id} não encontrado") from exc

    async def list_merge_cases(
        self, status: str | None, *, token: str, tenant: str, limit: int = 20
    ) -> list[MergeCase]:
        self.calls.append(("list_merge_cases", token, tenant))
        items = [c for c in self.merge_cases.values() if status is None or c.status == status]
        return items[:limit]

    async def create_task(self, task: TaskCreate, *, token: str, tenant: str) -> Task:
        self.calls.append(("create_task", token, tenant))
        created = Task(id=new_id("task_"), created_at=utcnow(), **task.model_dump())
        self.tasks.append(created)
        return created

    async def get_regulation_request(
        self, request_id: str, *, token: str, tenant: str
    ) -> RegulationRequest:
        self.calls.append(("get_regulation_request", token, tenant))
        try:
            return self.regulation_requests[request_id]
        except KeyError as exc:
            raise CoreError(404, f"pedido {request_id} não encontrado") from exc

    async def create_pending_issue(
        self, issue: PendingIssueCreate, *, token: str, tenant: str
    ) -> PendingIssue:
        self.calls.append(("create_pending_issue", token, tenant))
        created = PendingIssue(id=new_id("pend_"), created_at=utcnow(), **issue.model_dump())
        self.pending_issues.append(created)
        return created

    async def request_message(
        self, message: MessageRequest, *, token: str, tenant: str
    ) -> MessageRequestResult:
        self.calls.append(("request_message", token, tenant))
        self.messages.append(message)
        return MessageRequestResult(id=new_id("msg_"))
