# Ações especiais: reveal_identifier, merge/unmerge, export e break-glass.
package sus.authz

import rego.v1

# ---------------------------------------------------------------------------
# reveal_identifier — mostrar CPF/CNS em claro.
# Papéis: profissional_aps (vinculado), agendador, regulador, auditor
# (produção) e admin_municipal apenas com break-glass. Sempre exige
# justificativa e gera log de acesso.
# ---------------------------------------------------------------------------

permits contains permit("reveal.aps_bound", false, [], true, false) if {
	tenant_match
	has_role("profissional_aps")
	action_is("reveal_identifier")
	not is_agent
	type_in({"identifier", "citizen"})
	purpose_allowed_for("profissional_aps")
	reveal_aps_scope
}

reveal_aps_scope if team_bound

reveal_aps_scope if cnes_bound

permits contains permit("reveal.agendador", false, [], true, false) if {
	tenant_match
	has_role("agendador")
	action_is("reveal_identifier")
	not is_agent
	type_in({"identifier", "citizen"})
	purpose_allowed_for("agendador")
}

permits contains permit("reveal.regulador", false, [], true, false) if {
	tenant_match
	has_role("regulador")
	action_is("reveal_identifier")
	not is_agent
	type_in({"identifier", "citizen", "regulation_request"})
	purpose_allowed_for("regulador")
}

permits contains permit("reveal.auditor_production", false, [], true, false) if {
	tenant_match
	has_role("auditor")
	action_is("reveal_identifier")
	not is_agent
	type_in({"identifier", "citizen", "production_record"})
	purpose_is("production_audit")
}

permits contains permit("reveal.admin_break_glass", false, [], true, true) if {
	tenant_match
	has_role("admin_municipal")
	action_is("reveal_identifier")
	not is_agent
	type_in({"identifier", "citizen"})
	break_glass_valid
}

# ---------------------------------------------------------------------------
# merge / unmerge — somente cadastro_mestre ou admin_municipal, nunca agente.
# ---------------------------------------------------------------------------

merge_types := {"citizen", "merge_case"}

permits contains permit("merge.cadastro_mestre_or_admin", false, [], true, false) if {
	tenant_match
	action_in({"merge", "unmerge"})
	type_in(merge_types)
	some role in data.data.role_groups.merge_roles
	has_role(role)
	purpose_is("identity_management")
	not is_agent
}

# ---------------------------------------------------------------------------
# export — delegado ao pacote sus.export (regras e obrigações próprias).
# ---------------------------------------------------------------------------

permits contains p if {
	action_is("export")
	some p in data.sus.export.permits
}

# ---------------------------------------------------------------------------
# produção (registros, pendências, lotes, retornos, regras) — delegado ao
# pacote sus.production (matriz por ação, quatro olhos, agente só lê pendências).
# ---------------------------------------------------------------------------

permits contains p if {
	some p in data.sus.production.permits
}

# ---------------------------------------------------------------------------
# break-glass (SEC-011) — leitura de dado clínico fora do vínculo por papéis
# clínicos elegíveis e pelo admin_municipal. Nunca para agentes; nunca para
# export/merge/unmerge (ação restrita a `read`). Gera obrigação de alerta ao
# DPO e justificativa.
# ---------------------------------------------------------------------------

break_glass_types := {"citizen", "identifier", "timeline_event", "appointment", "task", "care_plan", "regulation_request"}

permits contains permit("break_glass", false, [], true, true) if {
	tenant_match
	action_is("read")
	type_in(break_glass_types)
	break_glass_valid
	some role in input.subject.roles
	data.data.roles[role].break_glass_eligible == true
}
