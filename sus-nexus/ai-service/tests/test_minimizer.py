from __future__ import annotations

import json

from sus_nexus_ai.privacy.minimizer import REDACTED, Minimizer, PseudonymMap, mask_for_record
from tests.conftest import PII_CNS, PII_CPF, PII_EMAIL, PII_NAME, PII_PHONE, PII_STRINGS


def test_names_become_reversible_tokens() -> None:
    result = Minimizer().minimize(
        {
            "patient_name": PII_NAME,
            "mother_name": "Luzia Pereira",
            "requesting_professional_name": PII_NAME,
        }
    )
    assert result.data["patient_name"] == "[PESSOA_1]"
    assert result.data["mother_name"] == "[PESSOA_2]"
    assert result.data["requesting_professional_name"] == "[PESSOA_1]"  # mesmo nome → mesmo token
    assert result.pseudonyms.reidentify("Contato com [PESSOA_1] e [PESSOA_2]") == (
        f"Contato com {PII_NAME} e Luzia Pereira"
    )


def test_identifier_and_contact_keys_are_dropped() -> None:
    result = Minimizer().minimize(
        {
            "id": "reg_1",
            "cpf": PII_CPF,
            "cns": PII_CNS,
            "phone": PII_PHONE,
            "email": PII_EMAIL,
            "address": {"street": "Rua A", "number": "10"},
            "contacts": [{"kind": "mobile", "value": PII_PHONE}],
            "identifiers": [{"system": "CPF", "value": PII_CPF}],
        }
    )
    assert result.data == {"id": "reg_1"}
    assert "$.cpf" in result.removed_fields and "$.contacts" in result.removed_fields


def test_free_text_is_scrubbed_with_regex_and_known_names() -> None:
    notes = (
        f"Paciente {PII_NAME.upper()} CPF {PII_CPF} CNS {PII_CNS} "
        f"tel {PII_PHONE} {PII_EMAIL} CEP 39400-000"
    )
    result = Minimizer().minimize({"patient_name": PII_NAME, "notes": notes})
    scrubbed = result.data["notes"]
    assert "[PESSOA_1]" in scrubbed
    assert scrubbed.count(REDACTED) >= 5
    for pii in PII_STRINGS:
        assert pii.lower() not in scrubbed.lower()
    assert result.redactions >= 6


def test_nested_structures_and_non_pii_values_are_preserved() -> None:
    data = {
        "request": {
            "id": "reg_1",
            "citizen_id": "cit_01J8X01ABCDEFGHJKMNPQRSTVW",
            "attachments": ["ecg", "encaminhamento"],
            "score": 0.9,
            "candidates": [{"display_name": PII_NAME, "birthdate": "1958-03-12"}],
        }
    }
    result = Minimizer().minimize(data)
    req = result.data["request"]
    assert req["id"] == "reg_1" and req["citizen_id"].startswith("cit_")
    assert req["attachments"] == ["ecg", "encaminhamento"] and req["score"] == 0.9
    assert req["candidates"][0] == {"display_name": "[PESSOA_1]", "birthdate": "1958-03-12"}


def test_masked_identifiers_are_kept_and_pseudonym_map_shared() -> None:
    shared = PseudonymMap()
    first = Minimizer().minimize({"name": PII_NAME}, pseudonyms=shared)
    second = Minimizer().minimize(
        {"display_name": PII_NAME, "value_masked": "***.***.***-09"}, shared
    )
    assert first.data["name"] == second.data["display_name"] == "[PESSOA_1]"
    assert second.data["value_masked"] == "***.***.***-09"
    assert len(shared) == 1


def test_mask_for_record_has_no_pii() -> None:
    masked = json.dumps(mask_for_record({"reason": f"ligar para {PII_PHONE}", "name": PII_NAME}))
    for pii in (PII_PHONE, PII_NAME):
        assert pii not in masked


def test_operational_codes_are_not_mistaken_for_phones() -> None:
    """INE (10 dígitos), CNES (7) e SIGTAP (10) ficam intactos; telefone em chave de contato não."""
    data = {
        "assignee": {"kind": "team", "id": "0001234567"},
        "reference_team": {"team_ine": "0009876543", "health_unit_cnes": "2143456"},
        "requested_service_code": "0301010072",
        "requesting_professional_cbo": "225125",
        "phone": PII_PHONE,
        "notes": f"ligar para {PII_PHONE}; equipe 0001234567",
    }
    result = Minimizer().minimize(data)
    assert result.data["assignee"] == {"kind": "team", "id": "0001234567"}
    assert result.data["reference_team"] == {
        "team_ine": "0009876543",
        "health_unit_cnes": "2143456",
    }
    assert result.data["requested_service_code"] == "0301010072"
    assert result.data["requesting_professional_cbo"] == "225125"
    assert "phone" not in result.data
    # em texto livre continua conservador: qualquer sequência "telefônica" é removida
    assert PII_PHONE not in result.data["notes"] and REDACTED in result.data["notes"]
    assert mask_for_record({"assignee": {"kind": "team", "id": "0001234567"}})["assignee"][
        "id"
    ] == ("0001234567")
