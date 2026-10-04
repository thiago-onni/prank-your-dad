# Funções e predicados compartilhados pelas regras de autorização.
#
# Estilo: predicados pequenos e positivos (sem `not` sobre `input.resource`)
# para que `opa eval --partial --unknowns input.resource` gere residuais
# simples (igualdades e disjunções) traduzíveis em filtros de consulta.
package sus.authz

import rego.v1

# ---------------------------------------------------------------------------
# Sujeito
# ---------------------------------------------------------------------------

has_role(role) if role in input.subject.roles

is_agent if input.subject.client_type == "agent"

is_human if input.subject.client_type == "user"

# ---------------------------------------------------------------------------
# Tenant (isolamento absoluto — nenhum papel atravessa tenant)
# ---------------------------------------------------------------------------

tenant_match if input.subject.tenant == input.resource.tenant

# ---------------------------------------------------------------------------
# Finalidade
# ---------------------------------------------------------------------------

purpose_is(purpose) if input.context.purpose == purpose

purpose_in(purposes) if input.context.purpose in purposes

# Finalidade declarada está no catálogo do papel (data/purposes.json).
purpose_allowed_for(role) if input.context.purpose in data.data.purposes.by_role[role]

# ---------------------------------------------------------------------------
# Vínculo assistencial / territorial
# ---------------------------------------------------------------------------

team_bound if input.resource.citizen_team in input.subject.teams

cnes_bound if input.resource.citizen_cnes in input.subject.cnes

microarea_bound if input.resource.citizen_microarea in input.subject.microareas

assignee_team_bound if {
	input.resource.assignee.kind == "team"
	input.resource.assignee.id in input.subject.teams
}

assignee_is_subject if {
	input.resource.assignee.kind == "user"
	input.resource.assignee.id == input.subject.id
}

# ---------------------------------------------------------------------------
# Ação, tipo, domínio e sensibilidade
# ---------------------------------------------------------------------------

action_is(action) if input.action == action

action_in(actions) if input.action in actions

type_is(resource_type) if input.resource.type == resource_type

type_in(resource_types) if input.resource.type in resource_types

domain_is(domain) if input.resource.domain == domain

domain_in(domains) if input.resource.domain in domains

# Sensibilidade do recurso no máximo `level` (inclusive).
sensitivity_at_most(level) if input.resource.sensitivity in data.data.sensitivity.at_most[level]

sensitivity_is(level) if input.resource.sensitivity == level

aggregate_types := data.data.resource_types.aggregate

management_types := data.data.resource_types.management

citizen_types := data.data.resource_types.citizen_individual

integration_types := data.data.resource_types.integration

audit_types := data.data.resource_types.audit

production_types := data.data.resource_types.production

# ---------------------------------------------------------------------------
# Break-glass (SEC-011): só humanos, justificativa mínima de 20 caracteres.
# ---------------------------------------------------------------------------

break_glass_requested if input.context.break_glass == true

break_glass_justified if count(trim_space(input.context.break_glass_justification)) >= 20

break_glass_valid if {
	break_glass_requested
	break_glass_justified
	is_human
}

# ---------------------------------------------------------------------------
# Construtor de permissão (objeto padronizado consumido por decision.rego)
# ---------------------------------------------------------------------------

permit(rule, mask, redact, justify, break_glass) := {
	"rule": rule,
	"mask": mask,
	"redact": redact,
	"justify": justify,
	"break_glass": break_glass,
}
