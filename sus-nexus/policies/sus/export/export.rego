# Exportações (SEC-008): somente dpo, gestor (agregados) e auditor
# (produção), sempre com mascaramento e justificativa. Nunca agentes;
# break-glass não habilita export.
#
# Usa o mesmo contrato de `input` de sus.authz com `action == "export"`.
# `data.sus.authz.permits` incorpora `data.sus.export.permits`, então a
# decisão final continua sendo `data.sus.authz.decision`. Este pacote também
# expõe `data.sus.export.decision` para avaliação isolada.
package sus.export

import rego.v1

import data.sus.authz

export_roles := data.data.role_groups.export_roles

default allow := false

allow if {
	authz.tenant_match
	count(permits) > 0
}

permits contains authz.permit("export.dpo_audit", true, [], true, false) if {
	authz.tenant_match
	authz.action_is("export")
	authz.has_role("dpo")
	authz.type_in(authz.audit_types)
	authz.purpose_is("security_audit")
	not authz.is_agent
}

permits contains authz.permit("export.gestor_aggregate", true, [], true, false) if {
	authz.tenant_match
	authz.action_is("export")
	authz.has_role("gestor")
	authz.type_in(authz.aggregate_types)
	authz.purpose_allowed_for("gestor")
	not authz.is_agent
}

permits contains authz.permit("export.auditor_production", true, data.data.redactions.auditor, true, false) if {
	authz.tenant_match
	authz.action_is("export")
	authz.has_role("auditor")
	authz.type_in(authz.production_types)
	authz.purpose_is("production_audit")
	not authz.is_agent
}

# Razões de negação específicas de export (agregadas por sus.authz).
deny_reasons contains "export_requires_dpo_gestor_or_auditor" if {
	authz.action_is("export")
	not has_export_role
}

has_export_role if {
	some role in export_roles
	authz.has_role(role)
}

deny_reasons contains "export_not_allowed_for_agents" if {
	authz.action_is("export")
	authz.is_agent
}

deny_reasons contains "export_resource_out_of_role_scope" if {
	authz.action_is("export")
	has_export_role
	not authz.is_agent
	count(permits) == 0
}

deny_reasons contains "break_glass_does_not_grant_export" if {
	authz.action_is("export")
	authz.break_glass_requested
}

obligations := {
	"mask_identifiers": true,
	"redact_fields": sort({f | some p in permits; some f in p.redact}),
	"log_access": true,
	"require_justification": true,
	"alert_dpo": false,
}

decision := {
	"allow": allow,
	"reasons": sort({p.rule | some p in permits}),
	"obligations": obligations,
} if {
	allow
}

decision := {
	"allow": false,
	"reasons": sort(deny_reasons),
	"obligations": obligations,
} if {
	not allow
}
