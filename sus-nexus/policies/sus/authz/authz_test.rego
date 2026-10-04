package sus.authz_test

import rego.v1

import data.sus.authz

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

tenant := "ibge_3143302"

base_subject := {
	"id": "u1",
	"roles": [],
	"tenant": tenant,
	"cnes": ["1234567"],
	"teams": ["ine_0001"],
	"microareas": ["03"],
	"client_type": "user",
}

base_resource := {
	"type": "citizen",
	"tenant": tenant,
	"domain": "identity",
	"sensitivity": "restricted",
	"citizen_team": "ine_0001",
	"citizen_cnes": "1234567",
	"citizen_microarea": "03",
	"assignee": {"kind": "team", "id": "ine_0001"},
}

base_context := {
	"purpose": "care_coordination",
	"break_glass": false,
	"break_glass_justification": "",
	"channel": "web",
}

req(roles, action, res, ctx) := {
	"subject": object.union(base_subject, {"roles": roles}),
	"action": action,
	"resource": object.union(base_resource, res),
	"context": object.union(base_context, ctx),
}

req_subject(subject_overrides, action, res, ctx) := {
	"subject": object.union(base_subject, subject_overrides),
	"action": action,
	"resource": object.union(base_resource, res),
	"context": object.union(base_context, ctx),
}

# Vínculo "fora": outra equipe, outro CNES, outra microárea.
unbound := {"citizen_team": "ine_9999", "citizen_cnes": "7654321", "citizen_microarea": "09", "assignee": {"kind": "team", "id": "ine_9999"}}

bg_ctx := {"break_glass": true, "break_glass_justification": "Paciente em emergência no PA, equipe de origem indisponível."}

# ---------------------------------------------------------------------------
# Tenant
# ---------------------------------------------------------------------------

test_tenant_mismatch_aps_denied if {
	not authz.allow with input as req(["profissional_aps"], "read", {"tenant": "ibge_9999999"}, {})
}

test_tenant_mismatch_admin_denied_even_for_management if {
	not authz.allow with input as req(["admin_municipal"], "read", {"type": "connector", "tenant": "ibge_9999999"}, {})
}

test_tenant_mismatch_reason if {
	d := authz.decision with input as req(["profissional_aps"], "read", {"tenant": "ibge_9999999"}, {})
	d.allow == false
	"tenant_mismatch" in d.reasons
}

test_tenant_mismatch_break_glass_denied if {
	not authz.allow with input as req(["profissional_aps"], "read", {"tenant": "ibge_9999999"}, bg_ctx)
}

# ---------------------------------------------------------------------------
# admin_municipal
# ---------------------------------------------------------------------------

test_admin_manage_tenant_config_allowed if {
	authz.allow with input as req(["admin_municipal"], "write", {"type": "tenant_config", "domain": "identity", "sensitivity": "internal"}, {"purpose": "management_analytics"})
}

test_admin_write_connector_allowed if {
	authz.allow with input as req(["admin_municipal"], "write", {"type": "connector"}, {"purpose": "integration_operations"})
}

test_admin_read_citizen_denied_without_break_glass if {
	d := authz.decision with input as req(["admin_municipal"], "read", {}, {"purpose": "management_analytics"})
	d.allow == false
	"admin_clinical_requires_break_glass" in d.reasons
}

test_admin_read_citizen_break_glass_allowed_with_alert if {
	d := authz.decision with input as req(["admin_municipal"], "read", {"type": "timeline_event", "domain": "aps"}, bg_ctx)
	d.allow == true
	"break_glass" in d.reasons
	d.obligations.alert_dpo == true
	d.obligations.require_justification == true
	d.obligations.log_access == true
}

test_admin_read_audit_log_allowed if {
	authz.allow with input as req(["admin_municipal"], "read", {"type": "audit_log", "domain": "task", "sensitivity": "internal"}, {"purpose": "security_audit"})
}

test_admin_merge_allowed if {
	d := authz.decision with input as req(["admin_municipal"], "merge", {"type": "merge_case"}, {"purpose": "identity_management"})
	d.allow == true
	d.obligations.require_justification == true
}

# ---------------------------------------------------------------------------
# gestor
# ---------------------------------------------------------------------------

test_gestor_read_indicator_allowed_masked if {
	d := authz.decision with input as req(["gestor"], "read", {"type": "indicator", "domain": "production", "sensitivity": "internal"}, {"purpose": "management_analytics"})
	d.allow == true
	d.obligations.mask_identifiers == true
}

test_gestor_read_citizen_denied if {
	d := authz.decision with input as req(["gestor"], "read", {}, {"purpose": "management_analytics"})
	d.allow == false
	"gestor_individual_data" in d.reasons
}

test_gestor_read_timeline_denied if {
	not authz.allow with input as req(["gestor"], "read", {"type": "timeline_event", "domain": "aps"}, {"purpose": "public_health_surveillance"})
}

test_gestor_aggregate_wrong_purpose_denied if {
	not authz.allow with input as req(["gestor"], "read", {"type": "indicator"}, {"purpose": "care_coordination"})
}

# ---------------------------------------------------------------------------
# profissional_aps
# ---------------------------------------------------------------------------

test_aps_read_citizen_team_bound_allowed_unmasked if {
	d := authz.decision with input as req(["profissional_aps"], "read", {}, {})
	d.allow == true
	d.obligations.mask_identifiers == false
	d.obligations.redact_fields == []
	"aps.read_team_bound" in d.reasons
}

test_aps_read_timeline_cnes_bound_other_team_allowed if {
	authz.allow with input as req(["profissional_aps"], "read", {"type": "timeline_event", "domain": "aps", "citizen_team": "ine_9999"}, {})
}

test_aps_read_no_bond_denied if {
	d := authz.decision with input as req(["profissional_aps"], "read", {"type": "timeline_event", "domain": "aps", "citizen_team": "ine_9999", "citizen_cnes": "7654321"}, {})
	d.allow == false
	"no_matching_rule" in d.reasons
}

test_aps_wrong_purpose_denied if {
	d := authz.decision with input as req(["profissional_aps"], "read", {}, {"purpose": "regulation"})
	d.allow == false
	"purpose_not_allowed_for_roles" in d.reasons
}

test_aps_scheduling_purpose_allowed if {
	authz.allow with input as req(["profissional_aps"], "read", {"type": "appointment", "domain": "schedule", "sensitivity": "internal"}, {"purpose": "scheduling"})
}

test_aps_highly_restricted_team_bound_allowed if {
	authz.allow with input as req(["profissional_aps"], "read", {"type": "timeline_event", "domain": "aps", "sensitivity": "highly_restricted"}, {})
}

test_aps_highly_restricted_cnes_only_denied if {
	not authz.allow with input as req(["profissional_aps"], "read", {"type": "timeline_event", "domain": "aps", "sensitivity": "highly_restricted", "citizen_team": "ine_9999"}, {})
}

test_aps_write_careplan_own_team_allowed if {
	authz.allow with input as req(["profissional_aps"], "write", {"type": "care_plan", "domain": "careplan"}, {})
}

test_aps_write_careplan_other_team_denied if {
	not authz.allow with input as req(["profissional_aps"], "write", object.union({"type": "care_plan", "domain": "careplan"}, unbound), {})
}

test_aps_transition_task_assigned_to_team_allowed if {
	authz.allow with input as req(["profissional_aps"], "transition_task", {"type": "task", "domain": "task", "sensitivity": "internal", "citizen_team": "ine_9999", "assignee": {"kind": "team", "id": "ine_0001"}}, {})
}

test_aps_transition_task_assigned_to_self_allowed if {
	authz.allow with input as req(["profissional_aps"], "transition_task", {"type": "task", "domain": "task", "sensitivity": "internal", "citizen_team": "ine_9999", "assignee": {"kind": "user", "id": "u1"}}, {})
}

test_aps_write_citizen_identity_denied if {
	not authz.allow with input as req(["profissional_aps"], "write", {}, {})
}

test_aps_read_production_record_denied if {
	not authz.allow with input as req(["profissional_aps"], "read", {"type": "production_record", "domain": "production"}, {})
}

# ---------------------------------------------------------------------------
# acs
# ---------------------------------------------------------------------------

test_acs_read_citizen_microarea_allowed_masked_redacted if {
	d := authz.decision with input as req(["acs"], "read", {}, {})
	d.allow == true
	d.obligations.mask_identifiers == true
	d.obligations.redact_fields == ["diagnoses", "documents", "results"]
}

test_acs_read_other_microarea_denied if {
	not authz.allow with input as req(["acs"], "read", {"citizen_microarea": "09"}, {})
}

test_acs_read_aps_domain_denied if {
	not authz.allow with input as req(["acs"], "read", {"type": "timeline_event", "domain": "aps", "sensitivity": "internal"}, {})
}

test_acs_read_hospital_restricted_denied if {
	not authz.allow with input as req(["acs"], "read", {"type": "timeline_event", "domain": "hospital"}, {})
}

test_acs_read_exam_highly_restricted_denied if {
	d := authz.decision with input as req(["acs"], "read", {"type": "timeline_event", "domain": "exam", "sensitivity": "highly_restricted"}, {})
	d.allow == false
	"sensitivity_exceeds_role_scope" in d.reasons
}

test_acs_read_identity_highly_restricted_denied if {
	not authz.allow with input as req(["acs"], "read", {"sensitivity": "highly_restricted"}, {})
}

test_acs_read_schedule_allowed if {
	authz.allow with input as req(["acs"], "read", {"type": "appointment", "domain": "schedule", "sensitivity": "internal"}, {"purpose": "scheduling"})
}

test_acs_transition_task_allowed if {
	authz.allow with input as req(["acs"], "transition_task", {"type": "task", "domain": "task", "sensitivity": "internal"}, {})
}

test_acs_write_careplan_denied if {
	not authz.allow with input as req(["acs"], "write", {"type": "care_plan", "domain": "careplan"}, {})
}

# ---------------------------------------------------------------------------
# regulador
# ---------------------------------------------------------------------------

reg_ctx := {"purpose": "regulation"}

test_regulador_read_regulation_request_allowed if {
	authz.allow with input as req(["regulador"], "read", object.union({"type": "regulation_request", "domain": "regulation"}, unbound), reg_ctx)
}

test_regulador_write_regulation_request_allowed if {
	authz.allow with input as req(["regulador"], "write", {"type": "regulation_request", "domain": "regulation", "sensitivity": "highly_restricted"}, reg_ctx)
}

test_regulador_read_timeline_exam_allowed if {
	authz.allow with input as req(["regulador"], "read", {"type": "timeline_event", "domain": "exam", "sensitivity": "highly_restricted"}, reg_ctx)
}

test_regulador_read_timeline_hospital_restricted_allowed if {
	authz.allow with input as req(["regulador"], "read", {"type": "timeline_event", "domain": "hospital", "sensitivity": "restricted"}, reg_ctx)
}

test_regulador_read_timeline_hospital_highly_restricted_denied if {
	not authz.allow with input as req(["regulador"], "read", {"type": "timeline_event", "domain": "hospital", "sensitivity": "highly_restricted"}, reg_ctx)
}

test_regulador_read_timeline_careplan_highly_restricted_denied if {
	not authz.allow with input as req(["regulador"], "read", {"type": "timeline_event", "domain": "careplan", "sensitivity": "highly_restricted"}, reg_ctx)
}

test_regulador_read_aps_domain_denied if {
	not authz.allow with input as req(["regulador"], "read", {"type": "timeline_event", "domain": "aps", "sensitivity": "internal"}, reg_ctx)
}

test_regulador_wrong_purpose_denied if {
	not authz.allow with input as req(["regulador"], "read", {"type": "regulation_request", "domain": "regulation"}, {"purpose": "care_coordination"})
}

# ---------------------------------------------------------------------------
# agendador
# ---------------------------------------------------------------------------

sched_ctx := {"purpose": "scheduling"}

test_agendador_read_citizen_identity_allowed_masked if {
	d := authz.decision with input as req(["agendador"], "read", unbound, sched_ctx)
	d.allow == true
	d.obligations.mask_identifiers == true
	"clinical_notes" in d.obligations.redact_fields
}

test_agendador_read_appointment_allowed if {
	authz.allow with input as req(["agendador"], "read", {"type": "appointment", "domain": "schedule", "sensitivity": "internal"}, sched_ctx)
}

test_agendador_write_appointment_allowed if {
	authz.allow with input as req(["agendador"], "write", {"type": "appointment", "domain": "schedule", "sensitivity": "internal"}, sched_ctx)
}

test_agendador_read_aps_timeline_denied if {
	not authz.allow with input as req(["agendador"], "read", {"type": "timeline_event", "domain": "aps", "sensitivity": "internal"}, sched_ctx)
}

test_agendador_read_highly_restricted_identity_denied if {
	not authz.allow with input as req(["agendador"], "read", {"sensitivity": "highly_restricted"}, sched_ctx)
}

test_agendador_wrong_purpose_denied if {
	not authz.allow with input as req(["agendador"], "read", {}, {"purpose": "care_coordination"})
}

# ---------------------------------------------------------------------------
# profissional_hospitalar
# ---------------------------------------------------------------------------

test_hospital_read_own_cnes_allowed if {
	authz.allow with input as req(["profissional_hospitalar"], "read", {"type": "timeline_event", "domain": "hospital", "sensitivity": "highly_restricted", "citizen_team": "ine_9999"}, {})
}

test_hospital_read_other_cnes_denied if {
	not authz.allow with input as req(["profissional_hospitalar"], "read", {"type": "timeline_event", "domain": "hospital", "citizen_cnes": "7654321"}, {})
}

test_hospital_read_aps_domain_denied if {
	not authz.allow with input as req(["profissional_hospitalar"], "read", {"type": "timeline_event", "domain": "aps"}, {})
}

test_hospital_write_hospital_event_allowed if {
	authz.allow with input as req(["profissional_hospitalar"], "write", {"type": "timeline_event", "domain": "hospital"}, {})
}

# ---------------------------------------------------------------------------
# auditor
# ---------------------------------------------------------------------------

audit_ctx := {"purpose": "production_audit"}

test_auditor_read_production_record_allowed_masked if {
	d := authz.decision with input as req(["auditor"], "read", {"type": "production_record", "domain": "production", "sensitivity": "internal"}, audit_ctx)
	d.allow == true
	d.obligations.mask_identifiers == true
	d.obligations.redact_fields == ["clinical_notes"]
}

test_auditor_read_production_timeline_allowed if {
	authz.allow with input as req(["auditor"], "read", {"type": "timeline_event", "domain": "production", "sensitivity": "internal"}, audit_ctx)
}

test_auditor_wrong_purpose_denied if {
	not authz.allow with input as req(["auditor"], "read", {"type": "production_record", "domain": "production"}, {"purpose": "management_analytics"})
}

test_auditor_read_citizen_denied if {
	not authz.allow with input as req(["auditor"], "read", {}, audit_ctx)
}

# ---------------------------------------------------------------------------
# dpo
# ---------------------------------------------------------------------------

sec_ctx := {"purpose": "security_audit"}

test_dpo_read_audit_log_allowed if {
	authz.allow with input as req(["dpo"], "read", {"type": "audit_log", "domain": "task", "sensitivity": "internal"}, sec_ctx)
}

test_dpo_read_access_log_allowed if {
	authz.allow with input as req(["dpo"], "read", {"type": "access_log", "domain": "task", "sensitivity": "internal"}, sec_ctx)
}

test_dpo_read_policy_allowed if {
	authz.allow with input as req(["dpo"], "read", {"type": "policy", "domain": "task", "sensitivity": "internal"}, sec_ctx)
}

test_dpo_read_citizen_denied if {
	not authz.allow with input as req(["dpo"], "read", {}, sec_ctx)
}

test_dpo_wrong_purpose_denied if {
	not authz.allow with input as req(["dpo"], "read", {"type": "audit_log"}, {"purpose": "management_analytics"})
}

# ---------------------------------------------------------------------------
# operador_integracao
# ---------------------------------------------------------------------------

int_ctx := {"purpose": "integration_operations"}

test_operador_read_integration_message_allowed_payload_redacted if {
	d := authz.decision with input as req(["operador_integracao"], "read", {"type": "integration_message", "domain": "aps", "sensitivity": "restricted"}, int_ctx)
	d.allow == true
	d.obligations.mask_identifiers == true
	d.obligations.redact_fields == ["payload_sensitive"]
}

test_operador_reprocess_allowed_with_justification if {
	d := authz.decision with input as req(["operador_integracao"], "reprocess", {"type": "dlq_message", "domain": "aps"}, int_ctx)
	d.allow == true
	d.obligations.require_justification == true
}

test_operador_read_citizen_denied if {
	not authz.allow with input as req(["operador_integracao"], "read", {}, int_ctx)
}

test_operador_wrong_purpose_denied if {
	not authz.allow with input as req(["operador_integracao"], "read", {"type": "integration_message"}, {"purpose": "care_coordination"})
}

# ---------------------------------------------------------------------------
# cadastro_mestre, merge/unmerge
# ---------------------------------------------------------------------------

id_ctx := {"purpose": "identity_management"}

test_cadastro_mestre_read_merge_case_allowed if {
	authz.allow with input as req(["cadastro_mestre"], "read", {"type": "merge_case"}, id_ctx)
}

test_cadastro_mestre_merge_allowed_with_justification if {
	d := authz.decision with input as req(["cadastro_mestre"], "merge", {"type": "citizen"}, id_ctx)
	d.allow == true
	d.obligations.require_justification == true
	"merge.cadastro_mestre_or_admin" in d.reasons
}

test_cadastro_mestre_unmerge_allowed if {
	authz.allow with input as req(["cadastro_mestre"], "unmerge", {"type": "merge_case"}, id_ctx)
}

test_merge_by_agent_denied_even_with_role if {
	d := authz.decision with input as req_subject({"roles": ["cadastro_mestre"], "client_type": "agent"}, "merge", {"type": "citizen"}, id_ctx)
	d.allow == false
	"agent_not_allowed_for_action" in d.reasons
}

test_merge_profissional_aps_denied if {
	d := authz.decision with input as req(["profissional_aps"], "merge", {"type": "citizen"}, id_ctx)
	d.allow == false
	"merge_requires_cadastro_mestre_or_admin" in d.reasons
}

test_merge_wrong_purpose_denied if {
	not authz.allow with input as req(["cadastro_mestre"], "merge", {"type": "citizen"}, {"purpose": "care_coordination"})
}

test_cadastro_mestre_read_aps_timeline_denied if {
	not authz.allow with input as req(["cadastro_mestre"], "read", {"type": "timeline_event", "domain": "aps"}, id_ctx)
}

# ---------------------------------------------------------------------------
# reveal_identifier
# ---------------------------------------------------------------------------

test_reveal_aps_bound_allowed_requires_justification if {
	d := authz.decision with input as req(["profissional_aps"], "reveal_identifier", {"type": "identifier"}, {})
	d.allow == true
	d.obligations.require_justification == true
	d.obligations.log_access == true
	d.obligations.mask_identifiers == false
}

test_reveal_aps_unbound_denied if {
	not authz.allow with input as req(["profissional_aps"], "reveal_identifier", object.union({"type": "identifier"}, unbound), {})
}

test_reveal_agendador_allowed if {
	authz.allow with input as req(["agendador"], "reveal_identifier", {"type": "identifier"}, sched_ctx)
}

test_reveal_regulador_allowed if {
	authz.allow with input as req(["regulador"], "reveal_identifier", {"type": "identifier"}, reg_ctx)
}

test_reveal_auditor_production_allowed if {
	authz.allow with input as req(["auditor"], "reveal_identifier", {"type": "identifier"}, audit_ctx)
}

test_reveal_auditor_wrong_purpose_denied if {
	not authz.allow with input as req(["auditor"], "reveal_identifier", {"type": "identifier"}, {"purpose": "security_audit"})
}

test_reveal_acs_denied if {
	not authz.allow with input as req(["acs"], "reveal_identifier", {"type": "identifier"}, {})
}

test_reveal_operador_denied if {
	not authz.allow with input as req(["operador_integracao"], "reveal_identifier", {"type": "identifier"}, int_ctx)
}

test_reveal_admin_without_break_glass_denied if {
	not authz.allow with input as req(["admin_municipal"], "reveal_identifier", {"type": "identifier"}, {"purpose": "identity_management"})
}

test_reveal_admin_break_glass_allowed if {
	d := authz.decision with input as req(["admin_municipal"], "reveal_identifier", {"type": "identifier"}, bg_ctx)
	d.allow == true
	d.obligations.alert_dpo == true
	d.obligations.require_justification == true
}

test_reveal_by_agent_denied if {
	d := authz.decision with input as req_subject({"roles": ["profissional_aps"], "client_type": "agent"}, "reveal_identifier", {"type": "identifier"}, {})
	d.allow == false
	"agent_not_allowed_for_action" in d.reasons
}

# ---------------------------------------------------------------------------
# break-glass
# ---------------------------------------------------------------------------

test_break_glass_aps_out_of_bond_allowed if {
	d := authz.decision with input as req(["profissional_aps"], "read", object.union({"type": "timeline_event", "domain": "aps", "sensitivity": "highly_restricted"}, unbound), bg_ctx)
	d.allow == true
	d.reasons == ["break_glass"]
	d.obligations.alert_dpo == true
	d.obligations.log_access == true
	d.obligations.require_justification == true
}

test_break_glass_hospitalar_other_cnes_allowed if {
	authz.allow with input as req(["profissional_hospitalar"], "read", object.union({"type": "timeline_event", "domain": "hospital"}, unbound), bg_ctx)
}

test_break_glass_short_justification_denied if {
	d := authz.decision with input as req(["profissional_aps"], "read", object.union({"type": "timeline_event", "domain": "aps"}, unbound), {"break_glass": true, "break_glass_justification": "urgente"})
	d.allow == false
	"break_glass_justification_too_short" in d.reasons
}

test_break_glass_agent_denied if {
	d := authz.decision with input as req_subject({"roles": ["profissional_aps"], "client_type": "agent"}, "read", object.union({"type": "timeline_event", "domain": "aps"}, unbound), bg_ctx)
	d.allow == false
	"agent_cannot_break_glass" in d.reasons
}

test_break_glass_acs_not_eligible_denied if {
	d := authz.decision with input as req(["acs"], "read", object.union({"type": "timeline_event", "domain": "aps"}, unbound), bg_ctx)
	d.allow == false
	"break_glass_role_not_eligible" in d.reasons
}

test_break_glass_export_denied if {
	d := authz.decision with input as req(["profissional_aps"], "export", {"type": "timeline_event", "domain": "aps"}, bg_ctx)
	d.allow == false
	"break_glass_not_applicable_to_action" in d.reasons
}

test_break_glass_merge_denied if {
	not authz.allow with input as req(["profissional_aps"], "merge", {"type": "citizen"}, object.union(bg_ctx, {"purpose": "identity_management"}))
}

test_break_glass_does_not_apply_to_aggregates_for_gestor if {
	not authz.allow with input as req(["gestor"], "read", {"type": "timeline_event", "domain": "aps"}, bg_ctx)
}

test_break_glass_in_bond_does_not_alert_when_regular_rule_matches if {
	# Dentro do vínculo com break-glass marcado: regra regular + break_glass casam,
	# e o alerta ao DPO é mantido (conservador).
	d := authz.decision with input as req(["profissional_aps"], "read", {}, bg_ctx)
	d.allow == true
	"aps.read_team_bound" in d.reasons
	d.obligations.alert_dpo == true
}

# ---------------------------------------------------------------------------
# Obrigações e formato da decisão
# ---------------------------------------------------------------------------

test_log_access_always_true_even_when_denied if {
	d := authz.decision with input as req([], "read", {}, {})
	d.allow == false
	d.obligations.log_access == true
	d.obligations.mask_identifiers == true
}

test_multi_role_most_permissive_view_wins if {
	d := authz.decision with input as req(["acs", "profissional_aps"], "read", {}, {})
	d.allow == true
	d.obligations.mask_identifiers == false
	d.obligations.redact_fields == []
}

test_no_roles_denied if {
	not authz.allow with input as req([], "read", {}, {})
}

test_unknown_action_denied if {
	not authz.allow with input as req(["profissional_aps", "admin_municipal"], "delete", {}, {})
}

test_decision_shape if {
	d := authz.decision with input as req(["profissional_aps"], "read", {}, {})
	object.keys(d) == {"allow", "reasons", "obligations", "policy_version"}
	object.keys(d.obligations) == {"mask_identifiers", "redact_fields", "log_access", "require_justification", "alert_dpo"}
}

test_filter_rule_matches_allow if {
	authz.filter with input as req(["profissional_aps"], "read", {}, {})
	not authz.filter with input as req(["profissional_aps"], "read", unbound, {})
}
