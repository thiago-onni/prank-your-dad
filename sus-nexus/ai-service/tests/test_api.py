from __future__ import annotations

from fastapi.testclient import TestClient

from sus_nexus_ai.service import AIService
from sus_nexus_ai.tools.core_client import InMemoryCoreClient
from tests.conftest import CASE_ID, REQUEST_ID, TENANT, approver_headers, discharge_input

HEADERS = {
    "X-Mock-Subject": "user:operador",
    "X-Mock-Roles": "agent_operator",
    "X-Mock-Tenant": TENANT,
}


def test_health_and_catalog(client: TestClient) -> None:
    health = client.get("/health").json()
    assert health["status"] == "ok"
    assert health["agents"] == [
        "exam_critical_result",
        "mpi_duplicate_suggestion",
        "post_discharge_followup",
        "regulation_completeness",
    ]
    agents = client.get("/agents", headers=HEADERS).json()
    assert {a["id"] for a in agents} == set(health["agents"])
    assert all("input_schema" in a and "output_schema" in a for a in agents)
    tools = client.get("/tools", headers=HEADERS).json()
    forbidden = {t["name"] for t in tools if t["action_class"] == "forbidden"}
    assert forbidden == {
        "regulation.change_priority",
        "regulation.decide",
        "production.transmit",
        "mpi.merge",
    }


def test_run_get_and_list(client: TestClient) -> None:
    resp = client.post(
        f"/agents/{'post_discharge_followup'}/run",
        json={"tenant": TENANT, "trigger": {"kind": "user"}, "input": discharge_input()},
        headers=HEADERS,
    )
    assert resp.status_code == 200, resp.text
    run = resp.json()
    assert run["id"].startswith("run_") and run["status"] == "completed"
    assert run["trigger"] == {"kind": "user", "ref": "user:operador", "on_behalf_of": None}
    assert run["actions"][0]["status"] == "executed"

    assert client.get(f"/runs/{run['id']}", headers=HEADERS).json()["id"] == run["id"]
    listed = client.get(
        "/runs",
        params={"agent_id": "post_discharge_followup", "status": "completed"},
        headers=HEADERS,
    ).json()
    assert [r["id"] for r in listed] == [run["id"]]
    assert client.get("/runs", params={"status": "failed"}, headers=HEADERS).json() == []
    assert client.get("/runs/run_nope", headers=HEADERS).status_code == 404


def test_unknown_agent_invalid_input_and_tenant_mismatch(client: TestClient) -> None:
    resp = client.post("/agents/nope/run", json={"tenant": TENANT, "input": {}}, headers=HEADERS)
    assert resp.status_code == 404
    assert resp.headers["content-type"].startswith("application/problem+json")
    assert resp.json()["title"] == "Not Found"

    resp = client.post(
        "/agents/mpi_duplicate_suggestion/run",
        json={"tenant": TENANT, "input": {}},
        headers=HEADERS,
    )
    assert resp.status_code == 422

    other = {**HEADERS, "X-Mock-Tenant": "ibge_3106200"}
    resp = client.post(
        "/agents/mpi_duplicate_suggestion/run",
        json={"tenant": TENANT, "input": {"case_id": CASE_ID}},
        headers=other,
    )
    assert resp.status_code == 403


def test_approval_endpoints_require_role_and_justification(
    client: TestClient, service: AIService, core: InMemoryCoreClient
) -> None:
    run = client.post(
        "/agents/regulation_completeness/run",
        json={"tenant": TENANT, "input": {"request_id": REQUEST_ID}},
        headers=HEADERS,
    ).json()
    action_id = run["actions"][0]["id"]
    pending = client.get("/approvals", headers=HEADERS).json()
    assert len(pending) == 1 and pending[0]["status"] == "pending"

    url = f"/runs/{run['id']}/actions/{action_id}/approve"
    body = {"justification": "Confirmo pendência: faltam CID e ECG."}
    assert client.post(url, json=body, headers=HEADERS).status_code == 403  # sem papel
    short = client.post(url, json={"justification": "curto"}, headers=approver_headers())
    assert short.status_code == 422 and short.json()["title"] == "Unprocessable Entity"

    ok = client.post(url, json=body, headers=approver_headers())
    assert ok.status_code == 200, ok.text
    action = next(a for a in ok.json()["actions"] if a["id"] == action_id)
    assert action["status"] == "approved" and action["approver"] == "user:regulador-1"
    assert len(core.issues) == 1
    assert client.post(url, json=body, headers=approver_headers()).status_code == 409

    reject = client.post(
        f"/runs/{run['id']}/actions/{action_id}/reject", json=body, headers=approver_headers()
    )
    assert reject.status_code == 409


def test_reject_endpoint(client: TestClient, core: InMemoryCoreClient) -> None:
    run = client.post(
        "/agents/regulation_completeness/run",
        json={"tenant": TENANT, "input": {"request_id": REQUEST_ID}},
        headers=HEADERS,
    ).json()
    action_id = run["actions"][0]["id"]
    resp = client.post(
        f"/runs/{run['id']}/actions/{action_id}/reject",
        json={"justification": "Documentação chegou por outro canal."},
        headers=approver_headers("admin"),
    )
    assert resp.status_code == 200
    assert resp.json()["actions"][0]["status"] == "rejected"
    assert core.issues == []
    assert (
        client.get("/approvals", params={"status": "rejected"}, headers=HEADERS).json()[0][
            "approver"
        ]
        == "user:regulador-1"
    )


def test_kill_switch_admin_endpoints(client: TestClient, core: InMemoryCoreClient) -> None:
    assert client.get("/admin/kill-switch", headers=HEADERS).status_code == 403
    dpo = {**HEADERS, "X-Mock-Roles": "dpo"}
    assert client.get("/admin/kill-switch", headers=dpo).json()["effective"]["global"] is False
    resp = client.post(
        "/admin/kill-switch",
        json={"global": False, "agents": ["post_discharge_followup"]},
        headers=dpo,
    )
    assert resp.status_code == 200
    assert resp.json()["effective"]["agents"] == ["post_discharge_followup"]

    run = client.post(
        "/agents/post_discharge_followup/run",
        json={"tenant": TENANT, "input": discharge_input()},
        headers=HEADERS,
    ).json()
    assert run["status"] == "denied" and core.tasks == []

    client.post("/admin/kill-switch", json={"global": False}, headers=dpo)
    assert client.get("/health").json()["kill_switch_global"] is False


def test_metrics_endpoint_exposes_agent_series(client: TestClient) -> None:
    client.post(
        "/agents/post_discharge_followup/run",
        json={"tenant": TENANT, "input": discharge_input()},
        headers=HEADERS,
    )
    run = client.post(
        "/agents/regulation_completeness/run",
        json={"tenant": TENANT, "input": {"request_id": REQUEST_ID}},
        headers=HEADERS,
    ).json()
    client.post(
        f"/runs/{run['id']}/actions/{run['actions'][0]['id']}/approve",
        json={"justification": "Confirmo a pendência de justificativa."},
        headers=approver_headers(),
    )
    text = client.get("/metrics").text
    for name in (
        "agent_tool_call_total",
        "agent_tool_call_denied_total",
        "agent_human_approval_rate",
        "agent_run_total",
        "agent_action_total",
    ):
        assert name in text
    assert 'agent_run_total{agent_id="post_discharge_followup",status="completed"}' in text
    assert (
        'agent_tool_call_total{agent_id="post_discharge_followup",status="executed",tool="core.create_task"}'
        in text
    )
    assert (
        'agent_action_total{agent="post_discharge_followup",class="auto",status="executed"}' in text
    )
    assert (
        'agent_action_total{agent="regulation_completeness",class="requires_approval",status="pending_approval"}'
        in text
    )
    assert (
        'agent_action_total{agent="regulation_completeness",class="requires_approval",status="approved"}'
        in text
    )


def test_jwt_mode_rejects_missing_bearer(settings, service) -> None:  # type: ignore[no-untyped-def]
    from sus_nexus_ai.api.app import create_app

    jwt_settings = settings.model_copy(update={"auth_mode": "jwt"})
    with TestClient(create_app(jwt_settings, service=service)) as jwt_client:
        resp = jwt_client.get("/runs")
        assert resp.status_code == 401
        resp = jwt_client.get("/runs", headers={"Authorization": "Bearer nao.e.jwt"})
        assert resp.status_code == 401
