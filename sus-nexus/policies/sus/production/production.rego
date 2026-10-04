# Produção ambulatorial/hospitalar (BPA-C/BPA-I/APAC/AIH — PRO-001..010):
# registros, pendências de pré-auditoria, lotes, exportação, retornos oficiais
# e versões da regra de pré-auditoria.
#
# Mesmo contrato de `input` de sus.authz. `data.sus.authz.permits` incorpora
# `data.sus.production.permits` (como export), então o core continua chamando
# `POST /v1/data/sus/authz/decision`; este pacote também expõe
# `data.sus.production.decision` para avaliação isolada.
#
# Princípios:
#   - RBAC espelhando o `ProductionResource` do core (matriz abaixo) + ABAC
#     (tenant, quatro olhos na aprovação do lote) + finalidade
#     `production_audit` (o operador de integração também pode declarar
#     `integration_operations`).
#   - Agente de IA (client_type=agent ou papel agente_ia) só LÊ pendências
#     (`production_issue`); nunca registra, corrige, gera/aprova/exporta lote,
#     registra retorno ou altera regra (PRO-010, SEC-012).
#   - Quatro olhos: quem gerou o lote (`resource.created_by`) não pode
#     aprová-lo; sem `created_by` a aprovação é negada (fail-closed).
package sus.production

import rego.v1

import data.sus.authz

# ---------------------------------------------------------------------------
# Matriz ação × tipo de recurso → papéis (espelha @RolesAllowed do core).
# Ações específicas (register_record, correct, ...) não colidem com as ações
# genéricas read/write das regras de papel de sus.authz.
# ---------------------------------------------------------------------------

matrix := {
	"read": {
		"production_record": {"auditor", "gestor", "operador_integracao", "admin_municipal"},
		"production_issue": {"auditor", "gestor", "admin_municipal"},
		"production_batch": {"auditor", "gestor", "admin_municipal"},
		"production_summary": {"auditor", "gestor", "admin_municipal"},
		"production_deadline": {"auditor", "gestor", "operador_integracao", "admin_municipal"},
	},
	"register_record": {"production_record": {"operador_integracao", "auditor"}},
	"correct": {"production_record": {"auditor"}},
	"create_batch": {"production_batch": {"auditor"}},
	"approve_batch": {"production_batch": {"auditor", "gestor"}},
	"export_batch": {"production_batch": {"auditor"}},
	"register_outcome": {"production_outcome": {"operador_integracao", "auditor"}},
	"create_rule_version": {"production_rule": {"gestor", "admin_municipal"}},
}

resource_types := {t | some by_type in matrix; some t, _ in by_type}

actions := {a | some a, _ in matrix}

# Ações que exigem justificativa (obrigação devolvida ao serviço).
justified_actions := {"correct", "approve_batch", "create_rule_version"}

# Finalidades aceitas por papel nas ações de produção.
purposes := {
	"auditor": {"production_audit"},
	"gestor": {"production_audit"},
	"admin_municipal": {"production_audit"},
	"operador_integracao": {"production_audit", "integration_operations"},
}

# ---------------------------------------------------------------------------
# Predicados
# ---------------------------------------------------------------------------

production_request if {
	input.action in actions
	input.resource.type in resource_types
}

agent_actor if authz.is_agent

agent_actor if authz.has_role("agente_ia")

purpose_ok(role) if input.context.purpose in purposes[role]

default justify := false

justify if input.action in justified_actions

# Quatro olhos: só vale para approve_batch; demais ações passam.
four_eyes_ok if input.action != "approve_batch"

four_eyes_ok if {
	input.action == "approve_batch"
	input.resource.created_by != input.subject.id
}

# ---------------------------------------------------------------------------
# Permissões (consumidas por sus.authz)
# ---------------------------------------------------------------------------

# Humanos (e serviços) conforme a matriz.
permits contains authz.permit(sprintf("production.%s.%s", [input.action, input.resource.type]), true, data.data.redactions.auditor, justify, false) if {
	authz.tenant_match
	some role in input.subject.roles
	role in matrix[input.action][input.resource.type]
	purpose_ok(role)
	not agent_actor
	four_eyes_ok
}

# Agente de IA: somente leitura de pendências (sem dado do cidadão).
permits contains authz.permit("production.agent_read_issues", true, data.data.redactions.auditor, false, false) if {
	authz.tenant_match
	agent_actor
	authz.action_is("read")
	authz.type_is("production_issue")
	authz.purpose_is("production_audit")
}

default allow := false

allow if {
	authz.tenant_match
	count(permits) > 0
}

# ---------------------------------------------------------------------------
# Razões de negação (agregadas por sus.authz quando nada concede)
# ---------------------------------------------------------------------------

deny_reasons contains "production_agent_read_only" if {
	production_request
	agent_actor
	not agent_reading_issues
}

agent_reading_issues if {
	authz.action_is("read")
	authz.type_is("production_issue")
}

deny_reasons contains "production_four_eyes_creator_cannot_approve" if {
	authz.action_is("approve_batch")
	input.resource.created_by == input.subject.id
}

deny_reasons contains "production_batch_creator_unknown" if {
	authz.action_is("approve_batch")
	not input.resource.created_by
}

deny_reasons contains "production_role_not_allowed" if {
	production_request
	not agent_actor
	not role_in_matrix
}

role_in_matrix if {
	some role in input.subject.roles
	role in matrix[input.action][input.resource.type]
}

deny_reasons contains "production_purpose_not_allowed" if {
	production_request
	role_in_matrix
	not purpose_for_matrix_role
}

purpose_for_matrix_role if {
	some role in input.subject.roles
	role in matrix[input.action][input.resource.type]
	purpose_ok(role)
}

obligations := {
	"mask_identifiers": true,
	"redact_fields": sort({f | some p in permits; some f in p.redact}),
	"log_access": true,
	"require_justification": count({p | some p in permits; p.justify}) > 0,
	"alert_dpo": false,
}

decision := {
	"allow": true,
	"reasons": sort({p.rule | some p in permits}),
	"obligations": obligations,
	"policy_version": authz.policy_version,
} if {
	allow
}

decision := {
	"allow": false,
	"reasons": sort(all_deny_reasons),
	"obligations": obligations,
	"policy_version": authz.policy_version,
} if {
	not allow
}

all_deny_reasons contains "tenant_mismatch" if not authz.tenant_match

all_deny_reasons contains "no_matching_rule" if {
	authz.tenant_match
	count(deny_reasons) == 0
}

all_deny_reasons contains r if some r in deny_reasons
