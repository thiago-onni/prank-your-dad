"""``HttpCoreClient`` contra o contrato real: paths, headers e corpos validados pelo OpenAPI."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest
import respx
import yaml
from jsonschema import Draft202012Validator, FormatChecker

from sus_nexus_ai.tools.core_client import (
    CoreError,
    HttpCoreClient,
    IssueOrigin,
    RegulationIssueCreate,
    TaskCreate,
)
from tests.conftest import (
    CASE_ID,
    CITIZEN_ID,
    EXAM_ORDER_ID,
    REQUEST_ID,
    TENANT,
    exam_order_fixture,
    regulation_request_fixture,
)

BASE = "http://core.test"
OPENAPI = Path(__file__).resolve().parents[2] / "contracts" / "openapi" / "core-municipal.yaml"

pytestmark = pytest.mark.skipif(not OPENAPI.exists(), reason="contracts/ não disponível")


# ---------------------------------------------------------------------------
# OpenAPI 3.1 → JSON Schema (trivial: só resolve $ref locais)
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def openapi() -> dict[str, Any]:
    spec: dict[str, Any] = yaml.safe_load(OPENAPI.read_text("utf-8"))
    return spec


def _resolve(node: Any, spec: dict[str, Any]) -> Any:
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/"):
            target: Any = spec
            for part in ref[2:].split("/"):
                target = target[part]
            return _resolve(target, spec)
        return {k: _resolve(v, spec) for k, v in node.items()}
    if isinstance(node, list):
        return [_resolve(v, spec) for v in node]
    return node


def request_body_validator(
    spec: dict[str, Any], path: str, method: str = "post"
) -> Draft202012Validator:
    schema = spec["paths"][path][method]["requestBody"]["content"]["application/json"]["schema"]
    return Draft202012Validator(_resolve(schema, spec), format_checker=FormatChecker())


def response_validator(
    spec: dict[str, Any], path: str, method: str, status: str
) -> Draft202012Validator:
    schema = spec["paths"][path][method]["responses"][status]["content"]["application/json"][
        "schema"
    ]
    return Draft202012Validator(_resolve(schema, spec), format_checker=FormatChecker())


def _assert_headers(request: httpx.Request, purpose: str, *, idempotent: bool = False) -> None:
    assert request.headers["Authorization"] == "Bearer tok-agent"
    assert request.headers["X-Tenant-Id"] == TENANT
    assert request.headers["X-Purpose-Of-Use"] == purpose
    assert request.headers["X-Correlation-Id"] == "corr_abc"
    if idempotent:
        assert request.headers["Idempotency-Key"].startswith("idem_")
    else:
        assert "Idempotency-Key" not in request.headers


@pytest.fixture
def core_client() -> HttpCoreClient:
    return HttpCoreClient(BASE)


# ---------------------------------------------------------------------------
# Leituras
# ---------------------------------------------------------------------------


@respx.mock
async def test_get_regulation_request(core_client: HttpCoreClient, openapi: dict[str, Any]) -> None:
    payload = regulation_request_fixture().model_dump(mode="json", exclude_none=True)
    response_validator(openapi, "/api/v1/regulation/requests/{requestId}", "get", "200").validate(
        payload
    )
    route = respx.get(f"{BASE}/api/v1/regulation/requests/{REQUEST_ID}").respond(200, json=payload)
    req = await core_client.get_regulation_request(
        REQUEST_ID, token="tok-agent", tenant=TENANT, correlation_id="corr_abc"
    )
    assert route.called
    _assert_headers(route.calls.last.request, "regulation")
    assert req.id == REQUEST_ID and req.justification_present is False
    assert req.attached_documents_count == 1 and req.open_issue_kinds() == set()


@respx.mock
async def test_get_exam_order(core_client: HttpCoreClient, openapi: dict[str, Any]) -> None:
    payload = exam_order_fixture().model_dump(mode="json", exclude_none=True)
    response_validator(openapi, "/api/v1/exams/orders/{orderId}", "get", "200").validate(payload)
    route = respx.get(f"{BASE}/api/v1/exams/orders/{EXAM_ORDER_ID}").respond(200, json=payload)
    order = await core_client.get_exam_order(
        EXAM_ORDER_ID, token="tok-agent", tenant=TENANT, correlation_id="corr_abc"
    )
    assert route.called
    _assert_headers(route.calls.last.request, "care_coordination")
    assert order.results[0].critical is True and order.results[0].followup_task_id is None
    assert "critical" in order.issues


@respx.mock
async def test_get_merge_case_and_citizen_summary(core_client: HttpCoreClient) -> None:
    case_payload = {
        "id": CASE_ID,
        "status": "open",
        "score": 0.9,
        "candidates": [
            {
                "id": CITIZEN_ID,
                "display_name": "A",
                "registration_state": "validated",
                "identifiers": [],
            },
            {
                "id": "cit_01J8X02ABCDEFGHJKMNPQRSTVW",
                "display_name": "B",
                "registration_state": "validated",
                "identifiers": [],
            },
        ],
        "evidence": [{"attribute": "cpf", "agreement": "disagree", "weight": 0.5}],
        "conflicts": ["CPF divergente"],
        "opened_at": "2026-09-29T12:00:00Z",
    }
    case_route = respx.get(f"{BASE}/api/v1/mpi/cases/{CASE_ID}").respond(200, json=case_payload)
    summary_route = respx.get(f"{BASE}/api/v1/citizens/{CITIZEN_ID}/summary").respond(
        200, json={"citizen_id": CITIZEN_ID, "open_tasks": 2}
    )
    case = await core_client.get_merge_case(
        CASE_ID, token="tok-agent", tenant=TENANT, correlation_id="corr_abc"
    )
    summary = await core_client.get_citizen_summary(
        CITIZEN_ID, "regulation", token="tok-agent", tenant=TENANT, correlation_id="corr_abc"
    )
    _assert_headers(case_route.calls.last.request, "identity_management")
    _assert_headers(summary_route.calls.last.request, "regulation")
    # purpose vai no header, não na query string
    assert summary_route.calls.last.request.url.query == b""
    assert case.conflicts == ["CPF divergente"] and case.evidence[0].agreement == "disagree"
    assert summary.open_tasks == 2


# ---------------------------------------------------------------------------
# Escritas (corpo validado contra o schema do contrato)
# ---------------------------------------------------------------------------


@respx.mock
async def test_add_regulation_issue_body_matches_contract(
    core_client: HttpCoreClient, openapi: dict[str, Any]
) -> None:
    validator = request_body_validator(openapi, "/api/v1/regulation/requests/{requestId}/issues")
    returned = regulation_request_fixture(
        status="pending_documents",
        issues=[
            {
                "id": "iss_01J8XI01ABCDEFGHJKMNPQRSTV",
                "kind": "clinical_justification",
                "status": "open",
                "origin": {"kind": "agent", "id": "regulation_completeness", "version": "2.0.0"},
                "created_at": "2026-10-01T09:00:00Z",
            }
        ],
    ).model_dump(mode="json", exclude_none=True)
    route = respx.post(f"{BASE}/api/v1/regulation/requests/{REQUEST_ID}/issues").respond(
        201, json=returned
    )
    issue = RegulationIssueCreate(
        kind="clinical_justification",
        description="Pedido sem justificativa clínica registrada.",
        origin=IssueOrigin(kind="agent", id="regulation_completeness", version="2.0.0"),
    )
    result = await core_client.add_regulation_issue(
        REQUEST_ID, issue, token="tok-agent", tenant=TENANT, correlation_id="corr_abc"
    )
    body = json.loads(route.calls.last.request.content)
    validator.validate(body)
    assert body == {
        "kind": "clinical_justification",
        "description": "Pedido sem justificativa clínica registrada.",
        "origin": {"kind": "agent", "id": "regulation_completeness", "version": "2.0.0"},
    }
    _assert_headers(route.calls.last.request, "regulation", idempotent=True)
    assert result.open_issue_kinds() == {"clinical_justification"}


@respx.mock
async def test_create_task_body_matches_contract(
    core_client: HttpCoreClient, openapi: dict[str, Any]
) -> None:
    validator = request_body_validator(openapi, "/api/v1/tasks")
    route = respx.post(f"{BASE}/api/v1/tasks").mock(
        side_effect=lambda request: httpx.Response(
            201,
            json={
                **json.loads(request.content),
                "id": "task_01J8XT01ABCDEFGHJKMNPQRSTV",
                "status": "open",
                "created_at": "2026-10-01T13:00:00Z",
            },
        )
    )
    task = TaskCreate(
        task_type="exam_result_followup",
        priority="urgent",
        title="Resultado de exame crítico aguardando conduta",
        citizen_id=CITIZEN_ID,
        assignee={"kind": "health_unit", "id": "2143456"},  # type: ignore[arg-type]
        due_at="2026-10-02T13:00:00Z",  # type: ignore[arg-type]
        origin={"kind": "agent", "id": "exam_critical_result", "version": "1.0.0"},  # type: ignore[arg-type]
    )
    created = await core_client.create_task(
        task, token="tok-agent", tenant=TENANT, correlation_id="corr_abc"
    )
    body = json.loads(route.calls.last.request.content)
    validator.validate(body)
    assert body["origin"] == {"kind": "agent", "id": "exam_critical_result", "version": "1.0.0"}
    assert "description" not in body  # exclude_none
    _assert_headers(route.calls.last.request, "care_coordination", idempotent=True)
    assert created.id.startswith("task_") and created.status == "open"


@respx.mock
async def test_problem_json_becomes_core_error(core_client: HttpCoreClient) -> None:
    respx.get(f"{BASE}/api/v1/regulation/requests/{REQUEST_ID}").respond(
        403,
        json={"type": "about:blank", "title": "papel agente_ia obrigatório", "status": 403},
        headers={"content-type": "application/problem+json"},
    )
    with pytest.raises(CoreError) as exc:
        await core_client.get_regulation_request(REQUEST_ID, token="tok-agent", tenant=TENANT)
    assert exc.value.status == 403 and "agente_ia" in str(exc.value)


async def test_request_message_is_an_explicit_stub(core_client: HttpCoreClient) -> None:
    from sus_nexus_ai.tools.core_client import MessageRequest

    with pytest.raises(CoreError) as exc:
        await core_client.request_message(
            MessageRequest(citizen_id=CITIZEN_ID, channel="sms", template_id="t1"),
            token="tok-agent",
            tenant=TENANT,
        )
    assert exc.value.status == 501


def test_contract_paths_used_by_client_exist(openapi: dict[str, Any]) -> None:
    paths = openapi["paths"]
    for path, method in (
        ("/api/v1/citizens/{citizenId}/summary", "get"),
        ("/api/v1/regulation/requests/{requestId}", "get"),
        ("/api/v1/regulation/requests/{requestId}/issues", "post"),
        ("/api/v1/exams/orders/{orderId}", "get"),
        ("/api/v1/mpi/cases/{caseId}", "get"),
        ("/api/v1/mpi/cases", "get"),
        ("/api/v1/tasks", "post"),
    ):
        assert method in paths[path], f"{method.upper()} {path}"
    # header de finalidade é obrigatório nas leituras usadas pelos agentes
    purpose = openapi["components"]["parameters"]["purpose"]
    assert purpose["name"] == "X-Purpose-Of-Use" and purpose["in"] == "header"
