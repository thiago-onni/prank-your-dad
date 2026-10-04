"""Fluxo de aprovação ponta a ponta com o core HTTP real (mockado por respx).

run → ação ``requires_approval`` → ``POST /runs/{id}/actions/{aid}/approve`` → chamada real a
``POST /api/v1/regulation/requests/{id}/issues`` com ``origin.id`` = id do agente.
Rejeição não chama o core; kill switch na aprovação nega.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from typing import Any

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from sus_nexus_ai.api.app import create_app
from sus_nexus_ai.config import Settings
from sus_nexus_ai.security.kill_switch import KillSwitch
from sus_nexus_ai.service import build_service
from sus_nexus_ai.tools.core_client import HttpCoreClient
from tests.conftest import (
    CITIZEN_ID,
    REQUEST_ID,
    TENANT,
    approver_headers,
    regulation_request_fixture,
)
from tests.test_core_client import OPENAPI, request_body_validator

BASE = "http://core.test"
OPERATOR = {
    "X-Mock-Subject": "user:operador",
    "X-Mock-Roles": "agent_operator",
    "X-Mock-Tenant": TENANT,
}

pytestmark = pytest.mark.skipif(not OPENAPI.exists(), reason="contracts/ não disponível")


@pytest.fixture
def http_client(settings: Settings, kill_switch: KillSwitch) -> Iterator[TestClient]:
    service = build_service(settings, core=HttpCoreClient(BASE), kill_switch=kill_switch)
    with TestClient(create_app(settings, service=service)) as client:
        yield client


@pytest.fixture
def core_routes() -> Iterator[dict[str, respx.Route]]:
    request_payload = regulation_request_fixture().model_dump(mode="json", exclude_none=True)

    def _issue_created(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        issue = {
            "id": "iss_01J8XI09ABCDEFGHJKMNPQRSTV",
            "kind": body["kind"],
            "status": "open",
            "description": body["description"],
            "origin": body["origin"],
            "created_at": "2026-10-01T12:00:00Z",
        }
        return httpx.Response(
            201, json={**request_payload, "status": "pending_documents", "issues": [issue]}
        )

    with respx.mock(assert_all_called=False) as mock:
        routes = {
            "request": mock.get(f"{BASE}/api/v1/regulation/requests/{REQUEST_ID}").respond(
                200, json=request_payload
            ),
            "summary": mock.get(f"{BASE}/api/v1/citizens/{CITIZEN_ID}/summary").respond(
                200, json={"citizen_id": CITIZEN_ID, "open_regulation_requests": 1}
            ),
            "issues": mock.post(f"{BASE}/api/v1/regulation/requests/{REQUEST_ID}/issues").mock(
                side_effect=_issue_created
            ),
        }
        yield routes


def _run_pending(client: TestClient) -> dict[str, Any]:
    resp = client.post(
        "/agents/regulation_completeness/run",
        json={
            "tenant": TENANT,
            "trigger": {"kind": "event", "ref": "evt_x"},
            "input": {"request_id": REQUEST_ID},
        },
        headers=OPERATOR,
    )
    assert resp.status_code == 200, resp.text
    run: dict[str, Any] = resp.json()
    assert run["status"] == "completed"
    assert run["actions"][0]["status"] == "pending_approval"
    assert run["actions"][0]["action_class"] == "requires_approval"
    return run


def test_approve_calls_core_issues_endpoint_with_agent_origin(
    http_client: TestClient, core_routes: dict[str, respx.Route]
) -> None:
    run = _run_pending(http_client)
    assert core_routes["request"].called and core_routes["summary"].called
    assert not core_routes["issues"].called  # nada escrito no core antes da aprovação

    action_id = run["actions"][0]["id"]
    resp = http_client.post(
        f"/runs/{run['id']}/actions/{action_id}/approve",
        json={"justification": "Pendência procede: falta a justificativa clínica."},
        headers=approver_headers(),
    )
    assert resp.status_code == 200, resp.text
    action = next(a for a in resp.json()["actions"] if a["id"] == action_id)
    assert action["status"] == "approved" and action["approver"] == "user:regulador-1"

    assert core_routes["issues"].call_count == 1
    request = core_routes["issues"].calls.last.request
    body = json.loads(request.content)
    import yaml

    request_body_validator(
        yaml.safe_load(OPENAPI.read_text("utf-8")),
        "/api/v1/regulation/requests/{requestId}/issues",
    ).validate(body)
    assert body["kind"] == "clinical_justification"
    assert body["origin"] == {"kind": "agent", "id": "regulation_completeness", "version": "2.0.0"}
    assert request.headers["X-Tenant-Id"] == TENANT
    assert request.headers["X-Purpose-Of-Use"] == "regulation"
    assert request.headers["X-Correlation-Id"]
    assert request.headers["Idempotency-Key"].startswith("idem_")
    # identidade do agente (client credentials), não do usuário
    assert request.headers["Authorization"] == f"Bearer fake-token-regulation_completeness-{TENANT}"

    persisted = http_client.get(f"/runs/{run['id']}", headers=OPERATOR).json()
    assert persisted["tools_called"][-1]["tool"] == "core.create_pending_issue"
    assert persisted["tools_called"][-1]["status"] == "executed"
    assert persisted["tools_called"][-1]["approved_by"] == "user:regulador-1"


def test_reject_never_calls_core(
    http_client: TestClient, core_routes: dict[str, respx.Route]
) -> None:
    run = _run_pending(http_client)
    action_id = run["actions"][0]["id"]
    resp = http_client.post(
        f"/runs/{run['id']}/actions/{action_id}/reject",
        json={"justification": "Justificativa chegou por outro canal."},
        headers=approver_headers(),
    )
    assert resp.status_code == 200
    assert resp.json()["actions"][0]["status"] == "rejected"
    assert not core_routes["issues"].called


def test_kill_switch_at_approval_denies_and_never_calls_core(
    http_client: TestClient, core_routes: dict[str, respx.Route]
) -> None:
    run = _run_pending(http_client)
    action_id = run["actions"][0]["id"]
    dpo = {**OPERATOR, "X-Mock-Roles": "dpo"}
    ks = http_client.post(
        "/admin/kill-switch", json={"tools": ["core.create_pending_issue"]}, headers=dpo
    )
    assert ks.status_code == 200
    resp = http_client.post(
        f"/runs/{run['id']}/actions/{action_id}/approve",
        json={"justification": "Aprovo a pendência mesmo assim."},
        headers=approver_headers(),
    )
    assert resp.status_code == 200
    action = next(a for a in resp.json()["actions"] if a["id"] == action_id)
    assert action["status"] == "denied" and action["reasons"] == ["kill_switch:tool"]
    assert not core_routes["issues"].called


def test_core_error_on_approval_marks_action_failed(
    http_client: TestClient, core_routes: dict[str, respx.Route]
) -> None:
    run = _run_pending(http_client)
    action_id = run["actions"][0]["id"]
    core_routes["issues"].mock(
        return_value=httpx.Response(
            403, json={"type": "about:blank", "title": "papel agente_ia obrigatório", "status": 403}
        )
    )
    resp = http_client.post(
        f"/runs/{run['id']}/actions/{action_id}/approve",
        json={"justification": "Aprovo a pendência."},
        headers=approver_headers(),
    )
    assert resp.status_code == 200
    action = next(a for a in resp.json()["actions"] if a["id"] == action_id)
    assert action["status"] == "failed" and "CoreError" in (action["error"] or "")
    assert core_routes["issues"].call_count == 1
