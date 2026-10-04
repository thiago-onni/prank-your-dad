package sus.export_test

import rego.v1

import data.sus.authz
import data.sus.export

tenant := "ibge_3143302"

req(roles, client_type, res, purpose, ctx_extra) := {
	"subject": {"id": "u1", "roles": roles, "tenant": tenant, "cnes": [], "teams": [], "microareas": [], "client_type": client_type},
	"action": "export",
	"resource": object.union({"type": "audit_log", "tenant": tenant, "domain": "task", "sensitivity": "internal"}, res),
	"context": object.union({"purpose": purpose, "break_glass": false, "break_glass_justification": "", "channel": "web"}, ctx_extra),
}

test_dpo_export_audit_log_allowed_masked_justified if {
	d := export.decision with input as req(["dpo"], "user", {}, "security_audit", {})
	d.allow == true
	d.obligations.mask_identifiers == true
	d.obligations.require_justification == true
	d.obligations.log_access == true
}

test_dpo_export_citizen_denied if {
	d := export.decision with input as req(["dpo"], "user", {"type": "citizen", "domain": "identity"}, "security_audit", {})
	d.allow == false
	"export_resource_out_of_role_scope" in d.reasons
}

test_dpo_export_wrong_purpose_denied if {
	not export.allow with input as req(["dpo"], "user", {}, "management_analytics", {})
}

test_gestor_export_aggregate_allowed if {
	export.allow with input as req(["gestor"], "user", {"type": "indicator", "domain": "production"}, "management_analytics", {})
}

test_gestor_export_individual_denied if {
	not export.allow with input as req(["gestor"], "user", {"type": "timeline_event", "domain": "aps"}, "management_analytics", {})
}

test_auditor_export_production_allowed_with_redaction if {
	d := export.decision with input as req(["auditor"], "user", {"type": "production_record", "domain": "production"}, "production_audit", {})
	d.allow == true
	d.obligations.redact_fields == ["clinical_notes"]
	d.obligations.mask_identifiers == true
}

test_auditor_export_citizen_denied if {
	not export.allow with input as req(["auditor"], "user", {"type": "citizen", "domain": "identity"}, "production_audit", {})
}

test_profissional_aps_export_denied if {
	d := export.decision with input as req(["profissional_aps"], "user", {"type": "timeline_event", "domain": "aps"}, "care_coordination", {})
	d.allow == false
	"export_requires_dpo_gestor_or_auditor" in d.reasons
}

test_agent_export_denied_even_as_dpo if {
	d := export.decision with input as req(["dpo"], "agent", {}, "security_audit", {})
	d.allow == false
	"export_not_allowed_for_agents" in d.reasons
}

test_export_tenant_mismatch_denied if {
	not export.allow with input as req(["dpo"], "user", {"tenant": "ibge_9999999"}, "security_audit", {})
}

test_break_glass_does_not_grant_export if {
	d := export.decision with input as req(["profissional_aps"], "user", {"type": "timeline_event", "domain": "aps"}, "care_coordination", {"break_glass": true, "break_glass_justification": "Justificativa longa o suficiente para o teste."})
	d.allow == false
	"break_glass_does_not_grant_export" in d.reasons
}

# Integração com data.sus.authz: a decisão principal incorpora export.
test_authz_decision_includes_export_permit if {
	d := authz.decision with input as req(["dpo"], "user", {}, "security_audit", {})
	d.allow == true
	"export.dpo_audit" in d.reasons
	d.obligations.mask_identifiers == true
	d.obligations.require_justification == true
}

test_authz_decision_propagates_export_deny_reason if {
	d := authz.decision with input as req(["acs"], "user", {}, "care_coordination", {})
	d.allow == false
	"export_requires_dpo_gestor_or_auditor" in d.reasons
}
