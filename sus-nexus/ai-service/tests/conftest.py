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
    CitizenOperationalSummary,
    CitizenSummary,
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


@pytest.fixture
def core() -> InMemoryCoreClient:
    client = InMemoryCoreClient()
    client.regulation_requests[REQUEST_ID] = RegulationRequest(
        id=REQUEST_ID,
        citizen_id=CITIZEN_ID,
        specialty="cardiologia",
        procedure_code="0301010072",
        procedure_description="Consulta em cardiologia",
        priority_requested="amarelo",
        clinical_justification="Dor precordial aos esforços, HAS e DM2 descompensados.",
        cid10=None,  # faltante → pendência sugerida
        requesting_unit_cnes="2143456",
        requesting_professional_name=PII_PROFESSIONAL,
        requesting_professional_cbo="225125",
        attachments=["encaminhamento"],
        required_documents=["ecg", "encaminhamento"],
        # campos extras com PII (extra="allow") — devem sumir no contexto minimizado
        patient_name=PII_NAME,
        cpf=PII_CPF,
        cns=PII_CNS,
        phone=PII_PHONE,
        email=PII_EMAIL,
        notes=f"Paciente {PII_NAME}, CPF {PII_CPF}, tel {PII_PHONE}, e-mail {PII_EMAIL}.",
    )
    client.summaries[CITIZEN_ID] = CitizenOperationalSummary(
        citizen_id=CITIZEN_ID, open_regulation_requests=1, contact_valid=False, care_gaps=1
    )
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


def discharge_input(**overrides: object) -> dict[str, object]:
    event: dict[str, object] = {
        "citizen_id": CITIZEN_ID,
        "episode_id": "hep_01J8XH01ABCDEFGHJKMNPQRSTV",
        "discharged_at": "2026-10-01T15:30:00-03:00",
        "discharge_type": "home",
        "length_of_stay_days": 9,
        "primary_diagnosis_cid10": "I50.0",
        "readmissions_30d": 1,
        "age_years": 72,
        "comorbidities_count": 3,
        "has_care_plan": False,
    }
    event.update(overrides)
    return {
        "event": event,
        "reference_team": {"team_ine": "0001234567", "health_unit_cnes": "2143456"},
    }


def approver_headers(roles: str = "agent_approver") -> dict[str, str]:
    return {"X-Mock-Subject": "user:regulador-1", "X-Mock-Roles": roles, "X-Mock-Tenant": TENANT}
