package sus.production_test

import rego.v1

import data.sus.authz
import data.sus.production

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

tenant := "ibge_3143302"

ctx := {"purpose": "production_audit", "break_glass": false, "break_glass_justification": "", "channel": "web"}

req(id, roles, client_type, action, res, purpose) := {
	"subject": {"id": id, "roles": roles, "tenant": tenant, "cnes": [], "teams": [], "microareas": [], "client_type": client_type},
	"action": action,
	"resource": object.union({"type": "production_record", "tenant": tenant, "domain": "production", "sensitivity": "internal"}, res),
	"context": object.union(ctx, {"purpose": purpose}),
}

user(roles, action, res) := req("u1", roles, "user", action, res, "production_audit")

agent(roles, action, res) := req("agent-auditoria", roles, "agent", action, res, "production_audit")

batch(created_by) := {"type": "production_batch", "id": "pbat_1", "created_by": created_by}

# ---------------------------------------------------------------------------
# Leituras (registros, pendências, lotes, painel, prazos, regras)
# ---------------------------------------------------------------------------

test_read_record_roles_allowed if {
	every role in ["auditor", "gestor", "operador_integracao", "admin_municipal"] {
		authz.allow with input as user([role], "read", {})
	}
}

test_read_record_clinical_role_denied if {
	d := authz.decision with input as user(["profissional_aps"], "read", {})
	d.allow == false
	"production_role_not_allowed" in d.reasons
}

test_read_issues_roles_allowed if {
	every role in ["auditor", "gestor", "admin_municipal"] {
		authz.allow with input as user([role], "read", {"type": "production_issue"})
	}
}

test_read_issues_operator_denied if {
	not authz.allow with input as user(["operador_integracao"], "read", {"type": "production_issue"})
}

test_read_batch_summary_deadline_rule if {
	authz.allow with input as user(["gestor"], "read", {"type": "production_batch"})
	authz.allow with input as user(["gestor"], "read", {"type": "production_summary"})
	authz.allow with input as user(["operador_integracao"], "read", {"type": "production_deadline"})
	not authz.allow with input as user(["operador_integracao"], "read", {"type": "production_batch"})
	not authz.allow with input as user(["operador_integracao"], "read", {"type": "production_summary"})
}

test_read_masked_and_logged if {
	d := authz.decision with input as user(["gestor"], "read", {"type": "production_batch"})
	d.allow == true
	d.obligations.mask_identifiers == true
	d.obligations.log_access == true
	d.obligations.require_justification == false
	d.reasons == ["production.read.production_batch"]
}

# ---------------------------------------------------------------------------
# Finalidade e tenant
# ---------------------------------------------------------------------------

test_wrong_purpose_denied if {
	d := authz.decision with input as req("u1", ["gestor"], "user", "read", {"type": "production_batch"}, "management_analytics")
	d.allow == false
	"production_purpose_not_allowed" in d.reasons
}

test_operator_integration_purpose_allowed if {
	authz.allow with input as req("connector-producao", ["operador_integracao"], "service", "register_record", {}, "integration_operations")
	not authz.allow with input as req("u1", ["auditor"], "user", "register_record", {}, "integration_operations")
}

test_tenant_mismatch_denied if {
	d := authz.decision with input as user(["auditor"], "correct", {"tenant": "ibge_9999999"})
	d.allow == false
	"tenant_mismatch" in d.reasons
}

test_tenant_mismatch_agent_issues_denied if {
	not authz.allow with input as agent(["agente_ia"], "read", {"type": "production_issue", "tenant": "ibge_9999999"})
}

# ---------------------------------------------------------------------------
# Escritas
# ---------------------------------------------------------------------------

test_register_record if {
	authz.allow with input as user(["operador_integracao"], "register_record", {})
	authz.allow with input as user(["auditor"], "register_record", {})
	not authz.allow with input as user(["gestor"], "register_record", {})
}

test_correct_requires_auditor_and_justification if {
	d := authz.decision with input as user(["auditor"], "correct", {})
	d.allow == true
	d.obligations.require_justification == true
	not authz.allow with input as user(["gestor"], "correct", {})
	not authz.allow with input as user(["operador_integracao"], "correct", {})
}

test_create_batch_only_auditor if {
	authz.allow with input as user(["auditor"], "create_batch", {"type": "production_batch"})
	not authz.allow with input as user(["gestor"], "create_batch", {"type": "production_batch"})
}

test_export_batch_only_auditor if {
	authz.allow with input as user(["auditor"], "export_batch", batch("outra.pessoa"))
	not authz.allow with input as user(["gestor"], "export_batch", batch("outra.pessoa"))
}

test_register_outcome if {
	authz.allow with input as user(["operador_integracao"], "register_outcome", {"type": "production_outcome"})
	authz.allow with input as user(["auditor"], "register_outcome", {"type": "production_outcome"})
	not authz.allow with input as user(["gestor"], "register_outcome", {"type": "production_outcome"})
}

test_create_rule_version if {
	d := authz.decision with input as user(["gestor"], "create_rule_version", {"type": "production_rule"})
	d.allow == true
	d.obligations.require_justification == true
	authz.allow with input as user(["admin_municipal"], "create_rule_version", {"type": "production_rule"})
	not authz.allow with input as user(["auditor"], "create_rule_version", {"type": "production_rule"})
}

test_unknown_action_on_production_type_denied if {
	d := authz.decision with input as user(["auditor"], "delete", {"type": "production_batch"})
	d.allow == false
}

# ---------------------------------------------------------------------------
# Quatro olhos (aprovação do lote)
# ---------------------------------------------------------------------------

test_approve_by_other_person_allowed if {
	d := authz.decision with input as req("gestor.joao", ["gestor"], "user", "approve_batch", batch("auditora.carla"), "production_audit")
	d.allow == true
	d.obligations.require_justification == true
	d.reasons == ["production.approve_batch.production_batch"]
}

test_approve_by_creator_denied if {
	d := authz.decision with input as req("auditora.carla", ["auditor"], "user", "approve_batch", batch("auditora.carla"), "production_audit")
	d.allow == false
	"production_four_eyes_creator_cannot_approve" in d.reasons
}

test_approve_by_creator_with_gestor_role_denied if {
	not authz.allow with input as req("auditora.carla", ["auditor", "gestor"], "user", "approve_batch", batch("auditora.carla"), "production_audit")
}

test_approve_without_creator_denied if {
	d := authz.decision with input as user(["auditor"], "approve_batch", {"type": "production_batch"})
	d.allow == false
	"production_batch_creator_unknown" in d.reasons
}

test_approve_wrong_role_denied if {
	not authz.allow with input as user(["operador_integracao"], "approve_batch", batch("outra.pessoa"))
}

# ---------------------------------------------------------------------------
# Agente de IA: somente leitura de pendências
# ---------------------------------------------------------------------------

test_agent_reads_issues if {
	d := authz.decision with input as agent(["agente_ia"], "read", {"type": "production_issue"})
	d.allow == true
	d.reasons == ["production.agent_read_issues"]
	d.obligations.mask_identifiers == true
}

test_agent_issues_wrong_purpose_denied if {
	not authz.allow with input as req("agent-auditoria", ["agente_ia"], "agent", "read", {"type": "production_issue"}, "care_coordination")
}

test_agent_role_with_user_client_type_still_agent if {
	d := authz.decision with input as req("agent-auditoria", ["agente_ia", "auditor"], "user", "correct", {}, "production_audit")
	d.allow == false
	"production_agent_read_only" in d.reasons
}

test_agent_never_writes_even_with_human_roles if {
	every action_type in [
		["register_record", "production_record"],
		["correct", "production_record"],
		["create_batch", "production_batch"],
		["approve_batch", "production_batch"],
		["export_batch", "production_batch"],
		["register_outcome", "production_outcome"],
		["create_rule_version", "production_rule"],
	] {
		d := authz.decision with input as agent(["agente_ia", "auditor", "gestor", "admin_municipal"], action_type[0], object.union(batch("x"), {"type": action_type[1]}))
		d.allow == false
		"production_agent_read_only" in d.reasons
	}
}

test_agent_cannot_read_records_even_as_auditor if {
	not authz.allow with input as agent(["agente_ia", "auditor"], "read", {})
	not authz.allow with input as agent(["agente_ia", "auditor"], "read", {"type": "production_batch"})
	not authz.allow with input as agent(["agente_ia", "gestor"], "read", {"type": "production_summary"})
}

# ---------------------------------------------------------------------------
# Decisão isolada (data.sus.production.decision)
# ---------------------------------------------------------------------------

test_isolated_decision_allow if {
	d := production.decision with input as user(["auditor"], "create_batch", {"type": "production_batch"})
	d.allow == true
	d.reasons == ["production.create_batch.production_batch"]
	d.policy_version == authz.policy_version
}

test_isolated_decision_deny if {
	d := production.decision with input as req("auditora.carla", ["auditor"], "user", "approve_batch", batch("auditora.carla"), "production_audit")
	d.allow == false
	"production_four_eyes_creator_cannot_approve" in d.reasons
	d.obligations.mask_identifiers == true
}

test_isolated_decision_tenant_mismatch if {
	d := production.decision with input as user(["auditor"], "create_batch", {"type": "production_batch", "tenant": "ibge_9999999"})
	d.allow == false
	"tenant_mismatch" in d.reasons
}

test_isolated_decision_no_matching_rule if {
	d := production.decision with input as user(["auditor"], "read", {"type": "citizen"})
	d.allow == false
	d.reasons == ["no_matching_rule"]
}
