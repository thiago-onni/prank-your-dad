package sus.fhir_test

import rego.v1

import data.sus.fhir

tenant := "ibge_3143302"

req(scopes, interaction, rtype, client_type, res) := {
	"scopes": scopes,
	"interaction": interaction,
	"resourceType": rtype,
	"subject": {"id": "u1", "tenant": tenant, "client_type": client_type, "patient": "cit_01"},
	"resource": object.union({"tenant": tenant, "patient": "cit_01", "security": []}, res),
}

test_user_patient_read_allowed_no_redaction if {
	d := fhir.decision with input as req(["user/Patient.read"], "read", "Patient", "user", {})
	d.allow == true
	d.redact_elements == []
	d.matched_scopes == ["user/Patient.read"]
}

test_user_patient_read_search_allowed if {
	fhir.allow with input as req(["user/Patient.read"], "search", "Patient", "user", {})
}

test_user_patient_read_history_allowed if {
	fhir.allow with input as req(["user/Patient.read"], "history", "Patient", "user", {})
}

test_user_patient_read_create_denied if {
	d := fhir.decision with input as req(["user/Patient.read"], "create", "Patient", "user", {})
	d.allow == false
	"no_matching_scope" in d.reasons
}

test_user_patient_read_other_type_denied if {
	not fhir.allow with input as req(["user/Patient.read"], "read", "Observation", "user", {})
}

test_user_wildcard_write_create_allowed if {
	fhir.allow with input as req(["user/*.write"], "create", "Condition", "user", {})
}

test_user_wildcard_write_read_denied if {
	not fhir.allow with input as req(["user/*.write"], "read", "Condition", "user", {})
}

test_system_write_service_client_allowed if {
	fhir.allow with input as req(["system/*.write"], "update", "Encounter", "service", {})
}

test_system_scope_with_user_client_denied if {
	not fhir.allow with input as req(["system/*.write"], "update", "Encounter", "user", {})
}

test_user_scope_with_service_client_denied if {
	not fhir.allow with input as req(["user/*.read"], "read", "Patient", "service", {})
}

test_patient_scope_in_compartment_allowed if {
	fhir.allow with input as req(["patient/*.read"], "read", "Observation", "user", {})
}

test_patient_scope_other_patient_denied if {
	not fhir.allow with input as req(["patient/*.read"], "read", "Observation", "user", {"patient": "cit_02"})
}

test_patient_scope_outside_compartment_denied if {
	not fhir.allow with input as req(["patient/*.read"], "read", "Organization", "user", {})
}

test_restricted_scope_read_patient_allowed_with_redaction if {
	d := fhir.decision with input as req(["user/*.rs"], "read", "Patient", "user", {})
	d.allow == true
	"telecom" in d.redact_elements
	"address" in d.redact_elements
	"identifier" in d.redact_elements
	"extension" in d.redact_elements
	"meta.security" in d.redact_elements
}

test_restricted_scope_search_observation_redacts_values if {
	d := fhir.decision with input as req(["user/*.rs"], "search", "Observation", "user", {})
	d.allow == true
	"value" in d.redact_elements
	"note" in d.redact_elements
}

test_restricted_scope_update_denied if {
	not fhir.allow with input as req(["user/*.rs"], "update", "Patient", "user", {})
}

test_restricted_scope_highly_restricted_resource_denied if {
	d := fhir.decision with input as req(["user/*.rs"], "read", "Condition", "user", {"security": ["highly_restricted"]})
	d.allow == false
	"highly_restricted_requires_full_scope" in d.reasons
}

test_full_scope_highly_restricted_resource_allowed if {
	fhir.allow with input as req(["user/Condition.read"], "read", "Condition", "user", {"security": ["highly_restricted"]})
}

test_mixed_scopes_full_wins_no_redaction if {
	d := fhir.decision with input as req(["user/*.rs", "user/Patient.read"], "read", "Patient", "user", {})
	d.allow == true
	d.redact_elements == []
}

test_tenant_mismatch_denied if {
	d := fhir.decision with input as req(["user/*.read"], "read", "Patient", "user", {"tenant": "ibge_9999999"})
	d.allow == false
	"tenant_mismatch" in d.reasons
}

test_unsupported_resource_type_denied if {
	d := fhir.decision with input as req(["user/*.read"], "read", "Device", "user", {})
	d.allow == false
	"unsupported_resource_type" in d.reasons
}

test_malformed_scope_ignored if {
	not fhir.allow with input as req(["Patient.read", "user/Patient", "admin/*.read", "user/*.xyz"], "read", "Patient", "user", {})
}

test_unknown_interaction_denied if {
	d := fhir.decision with input as req(["user/*.*"], "transaction", "Bundle", "user", {})
	d.allow == false
	"unknown_interaction" in d.reasons
}

test_granular_letters_parsed if {
	fhir.allow with input as req(["user/Patient.cru"], "update", "Patient", "user", {})
	not fhir.allow with input as req(["user/Patient.cru"], "delete", "Patient", "user", {})
}

test_decision_shape if {
	d := fhir.decision with input as req(["user/Patient.read"], "read", "Patient", "user", {})
	object.keys(d) == {"allow", "reasons", "redact_elements", "matched_scopes", "policy_version"}
}
