# Documento de decisão: data.sus.authz.decision e data.sus.authz.allow.
#
#   POST /v1/data/sus/authz/decision  -> {"result": {"allow", "reasons", "obligations"}}
#   POST /v1/data/sus/authz/allow     -> {"result": true|false}
#
# `allow` é a única regra que precisa ser avaliada em partial evaluation:
# depende apenas de `tenant_match` e do conjunto `permits` (regras positivas).
package sus.authz

import rego.v1

policy_version := "1.0.0"

default allow := false

allow if {
	tenant_match
	some _ in permits
}

# Regra sem `default` para partial evaluation (geração de filtros de consulta):
#   opa eval --partial --unknowns input.resource 'data.sus.authz.filter'
# Sem default, OPA devolve as condições residuais como queries planas
# (disjunções), em vez de um módulo de suporte.
filter if {
	tenant_match
	some _ in permits
}

# ---------------------------------------------------------------------------
# Obrigações
# ---------------------------------------------------------------------------

# Toda leitura/escrita de dado de cidadão gera access_log (CONVENTIONS.md).
log_access := true

# Mascara identificadores a menos que ao menos uma permissão concedida
# seja explicitamente "sem máscara". Negado => mascara.
default mask_identifiers := true

mask_identifiers := false if {
	allow
	some p in permits
	p.mask == false
}

# Redige a união dos campos de todas as permissões, exceto quando alguma
# permissão concedida não exige redação (visão mais ampla prevalece).
unrestricted_permit if {
	some p in permits
	count(p.redact) == 0
}

redact_fields := [] if unrestricted_permit

redact_fields := sorted if {
	not unrestricted_permit
	fields := {f | some p in permits; some f in p.redact}
	sorted := sort(fields)
}

default require_justification := false

require_justification if {
	some p in permits
	p.justify == true
}

default alert_dpo := false

alert_dpo if {
	some p in permits
	p.break_glass == true
}

obligations := {
	"mask_identifiers": mask_identifiers,
	"redact_fields": redact_fields,
	"log_access": log_access,
	"require_justification": require_justification,
	"alert_dpo": alert_dpo,
}

# ---------------------------------------------------------------------------
# Razões (diagnóstico; não usadas por `allow`)
# ---------------------------------------------------------------------------

reasons := sort({p.rule | some p in permits}) if allow

reasons := sort(deny_reasons) if not allow

deny_reasons contains "tenant_mismatch" if not tenant_match

deny_reasons contains "no_matching_rule" if {
	tenant_match
	count(permits) == 0
}

deny_reasons contains "purpose_not_allowed_for_roles" if {
	tenant_match
	count(permits) == 0
	not purpose_allowed_for_any_role
}

purpose_allowed_for_any_role if {
	some role in input.subject.roles
	purpose_allowed_for(role)
}

deny_reasons contains "gestor_individual_data" if {
	has_role("gestor")
	count(permits) == 0
	not type_in(aggregate_types)
}

deny_reasons contains "agent_not_allowed_for_action" if {
	is_agent
	action_in({"merge", "unmerge", "export", "reveal_identifier"})
}

deny_reasons contains "agent_cannot_break_glass" if {
	is_agent
	break_glass_requested
}

deny_reasons contains "break_glass_justification_too_short" if {
	break_glass_requested
	not break_glass_justified
}

deny_reasons contains "break_glass_not_applicable_to_action" if {
	break_glass_requested
	count(permits) == 0
	not action_in({"read", "reveal_identifier"})
}

deny_reasons contains "break_glass_role_not_eligible" if {
	break_glass_requested
	count(permits) == 0
	not any_break_glass_eligible_role
}

any_break_glass_eligible_role if {
	some role in input.subject.roles
	data.data.roles[role].break_glass_eligible == true
}

deny_reasons contains "admin_clinical_requires_break_glass" if {
	has_role("admin_municipal")
	count(permits) == 0
	action_is("read")
	type_in(citizen_types)
	not break_glass_requested
}

deny_reasons contains "sensitivity_exceeds_role_scope" if {
	count(permits) == 0
	sensitivity_is("highly_restricted")
	some role in input.subject.roles
	data.data.roles[role].clinical == false
}

deny_reasons contains "merge_requires_cadastro_mestre_or_admin" if {
	action_in({"merge", "unmerge"})
	count(permits) == 0
	not has_merge_role
}

has_merge_role if {
	some role in data.data.role_groups.merge_roles
	has_role(role)
}

deny_reasons contains reason if {
	action_is("export")
	count(permits) == 0
	some reason in data.sus.export.deny_reasons
}

# ---------------------------------------------------------------------------
# Decisão completa
# ---------------------------------------------------------------------------

decision := {
	"allow": allow,
	"reasons": reasons,
	"obligations": obligations,
	"policy_version": policy_version,
}
