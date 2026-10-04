"""Fixtures compartilhadas: serviço com core em memória, LLM fake, OPA local e SQLite."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from sus_nexus_ai.api.app import create_app
from sus_nexus_ai.config import Settings
from sus_nexus_ai.security.kill_switch import KillSwitch
from sus_nexus_ai.service import AIService, build_service
from sus_nexus_ai.tools.core_client import (
    CareGap,
    CitizenOperationalSummary,
    CitizenSummary,
    ExamOrder,
    ExamResult,
    HospitalEpisode,
    InMemoryCoreClient,
    MaskedIdentifier,
    MergeCase,
    MergeEvidence,
    RegulationRequest,
)

TENANT = "ibge_3143302"
CITIZEN_ID = "cit_01J8X01ABCDEFGHJKMNPQRSTVW"
CITIZEN_ID_2 = "cit_01J8X02ABCDEFGHJKMNPQRSTVW"
REQUEST_ID = "reg_01J8XR01ABCDEFGHJKMNPQRSTV"
CASE_ID = "case_01J8XM01ABCDEFGHJKMNPQRSTV"
EXAM_ORDER_ID = "exo_01J8XX01ABCDEFGHJKMNPQRSTV"
EXAM_RESULT_ID = "exr_01J8XY01ABCDEFGHJKMNPQRSTV"
REQUESTING_CNES = "2143456"
TEAM_INE = "0001234567"
EPISODE_ID = "hep_01J8XH01ABCDEFGHJKMNPQRSTV"  # alta sem tarefa do core → fallback
EPISODE_ID_TRACKED = "hep_01J8XH02ABCDEFGHJKMNPQRSTV"  # alta com tarefa já criada pelo core
CORE_TASK_ID = "task_01J8XT02ABCDEFGHJKMNPQRSTV"
DIAGNOSIS_CID = "I50.0"

# PII sintética usada para garantir que NUNCA aparece em prompts/registros.
PII_NAME = "Maria Aparecida da Silva"
PII_PROFESSIONAL = "Dr. João Batista Figueiredo"
PII_CPF = "123.456.789-09"
PII_CNS = "898001234567890"
PII_PHONE = "(38) 99876-5432"
PII_EMAIL = "maria.silva@example.com"
PII_STRINGS = (PII_NAME, PII_PROFESSIONAL, PII_CPF, PII_CNS, PII_PHONE, PII_EMAIL, "99876")


@pytest.fixture
def settings() -> Settings:
    return Settings(
        _env_file=None,
        environment="test",
        policy_mode="local",
        identity_mode="fake",
        llm_provider="fake",
        auth_mode="mock",
        database_url="sqlite+pysqlite:///:memory:",
    )


def regulation_request_fixture(**overrides: object) -> RegulationRequest:
    """``RegulationRequest`` conforme o contrato do core — consulta sem justificativa clínica."""
    data: dict[str, object] = {
        "id": REQUEST_ID,
        "citizen_id": CITIZEN_ID,
        "kind": "consultation",
        "status": "requested",
        "priority": "priority",
        "requested_at": "2026-09-30T10:00:00-03:00",
        "requested_service_code": "0301010072",
        "code_system": "SIGTAP",
        "service_description": "Consulta em cardiologia",
        "specialty": "cardiologia",
        "requesting_cnes": REQUESTING_CNES,
        "requesting_unit_name": "UBS Centro",
        "requesting_professional_id": "prof_01J8XP01ABCDEFGHJKMNPQRSTV",
        "justification_present": False,  # → pendência clinical_justification
        "attached_documents_count": 1,
        "waiting_days": 3,
        "sla_due_at": "2026-10-30T10:00:00-03:00",
        "sla_breached": False,
        "issues": [],
        "source_system": "SISREG",
        "source_record_id": "sisreg-778",
        # campos extras com PII (extra="allow") — devem sumir/virar token no contexto minimizado
        "requesting_professional_name": PII_PROFESSIONAL,
        "patient_name": PII_NAME,
        "cpf": PII_CPF,
        "cns": PII_CNS,
        "phone": PII_PHONE,
        "email": PII_EMAIL,
        "notes": f"Paciente {PII_NAME}, CPF {PII_CPF}, tel {PII_PHONE}, e-mail {PII_EMAIL}.",
    }
    data.update(overrides)
    return RegulationRequest.model_validate(data)


def exam_order_fixture(**overrides: object) -> ExamOrder:
    """``ExamOrder`` com resultado crítico e sem tarefa de seguimento."""
    data: dict[str, object] = {
        "id": EXAM_ORDER_ID,
        "citizen_id": CITIZEN_ID,
        "status": "reported",
        "requested_at": "2026-09-28T08:00:00-03:00",
        "exam_code": "0202010473",
        "code_system": "SIGTAP",
        "exam_description": "Dosagem de potássio",
        "category": "laboratory",
        "priority": "routine",
        "requesting_cnes": REQUESTING_CNES,
        "requesting_unit_name": "UBS Centro",
        "reported_at": "2026-10-01T10:00:00-03:00",
        "issues": ["critical"],
        "results": [
            ExamResult(
                id=EXAM_RESULT_ID,
                reported_at=datetime(2026, 10, 1, 13, 0, tzinfo=UTC),
                status="final",
                critical=True,
                performer_cnes="7654321",
                has_document=True,
                observations_count=1,
                source_system="LIS",
            )
        ],
        "source_system": "LIS",
    }
    data.update(overrides)
    return ExamOrder.model_validate(data)


def hospital_episode_fixture(**overrides: object) -> HospitalEpisode:
    """``HospitalEpisode`` conforme o contrato: alta para casa, risco high pela regra do core."""
    data: dict[str, object] = {
        "id": EPISODE_ID,
        "citizen_id": CITIZEN_ID,
        "hospital_cnes": "7654321",
        "hospital_name": "Hospital Regional Sintético",
        "episode_class": "inpatient",
        "status": "discharged",
        "admitted_at": "2026-09-22T10:00:00-03:00",
        "discharged_at": "2026-10-01T15:30:00-03:00",
        "length_of_stay_days": 9,
        "disposition": "home",
        "ward": "Clínica médica",
        "bed": "12B",
        "principal_diagnosis_cid": DIAGNOSIS_CID,
        "aih_number": "3126100012345",
        "readmission_within_30d": True,
        "reference_health_unit_cnes": REQUESTING_CNES,
        "reference_team_ine": TEAM_INE,
        "risk_level": "high",
        "risk_rule_version": "hospital_risk_v1",
        "source_system": "HIS",
        "source_record_id": "his-991",
        "version": 3,
    }
    data.update(overrides)
    return HospitalEpisode.model_validate(data)


def care_gap_fixture(**overrides: object) -> CareGap:
    data: dict[str, object] = {
        "id": "gap_01J8XG01ABCDEFGHJKMNPQRSTV",
        "citizen_id": CITIZEN_ID,
        "citizen_display_name": PII_NAME,
        "care_line": "hipertensao",
        "gap_kind": "post_discharge_no_contact",
        "status": "open",
        "days_overdue": 2,
        "protocol_id": "has",
        "protocol_version": "1.0",
        "health_unit_cnes": REQUESTING_CNES,
        "team_ine": TEAM_INE,
        "detected_at": "2026-10-03T08:00:00-03:00",
        "contact_valid": False,
    }
    data.update(overrides)
    return CareGap.model_validate(data)


@pytest.fixture
def core() -> InMemoryCoreClient:
    client = InMemoryCoreClient()
    client.regulation_requests[REQUEST_ID] = regulation_request_fixture()
    client.summaries[CITIZEN_ID] = CitizenOperationalSummary(
        citizen_id=CITIZEN_ID,
        open_regulation_requests=1,
        contact_valid=False,
        care_gaps=1,
        team_ine="0001234567",
    )
    client.exam_orders[EXAM_ORDER_ID] = exam_order_fixture()
    client.hospital_episodes[EPISODE_ID] = hospital_episode_fixture()
    client.hospital_episodes[EPISODE_ID_TRACKED] = hospital_episode_fixture(
        id=EPISODE_ID_TRACKED,
        followup={
            "status": "pending",
            "task_id": CORE_TASK_ID,
            "due_at": "2026-10-03T15:30:00-03:00",
        },
    )
    client.care_gaps.append(care_gap_fixture())
    client.merge_cases[CASE_ID] = MergeCase(
        id=CASE_ID,
        score=0.96,
        rule_version="dedup_v3",
        candidates=[
            CitizenSummary(
                id=CITIZEN_ID,
                display_name=PII_NAME,
                birthdate="1958-03-12",
                identifiers=[MaskedIdentifier(system="CPF", value_masked="***.***.***-09")],
            ),
            CitizenSummary(
                id=CITIZEN_ID_2,
                display_name="Maria Aparecida Silva",
                birthdate="1958-03-12",
                identifiers=[MaskedIdentifier(system="CPF", value_masked="***.***.***-09")],
            ),
        ],
        evidence=[
            MergeEvidence(attribute="cpf", agreement="agree", weight=0.5),
            MergeEvidence(attribute="name", agreement="agree", weight=0.2),
            MergeEvidence(attribute="birthdate", agreement="agree", weight=0.3),
        ],
        conflicts=[],
        opened_at=datetime(2026, 9, 29, 12, 0, tzinfo=UTC),
    )
    return client


@pytest.fixture
def kill_switch() -> KillSwitch:
    return KillSwitch(file_path=None, env_var="AI_KILL_SWITCH_TEST_UNSET")


@pytest.fixture
def service(settings: Settings, core: InMemoryCoreClient, kill_switch: KillSwitch) -> AIService:
    return build_service(settings, core=core, kill_switch=kill_switch)


@pytest.fixture
def client(settings: Settings, service: AIService) -> Iterator[TestClient]:
    app = create_app(settings, service=service)
    with TestClient(app) as test_client:
        yield test_client


def discharge_input(episode_id: str = EPISODE_ID, **overrides: object) -> dict[str, object]:
    data: dict[str, object] = {"hospital_episode_id": episode_id}
    data.update(overrides)
    return data


def approver_headers(roles: str = "agent_approver") -> dict[str, str]:
    return {"X-Mock-Subject": "user:regulador-1", "X-Mock-Roles": roles, "X-Mock-Tenant": TENANT}
