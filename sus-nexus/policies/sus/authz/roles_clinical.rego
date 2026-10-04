# Regras dos papéis clínicos e de apoio territorial:
# profissional_aps, acs, profissional_hospitalar, regulador, agendador.
#
# Toda regra exige `tenant_match` explicitamente (defesa em profundidade:
# `decision.rego` também exige), e só adiciona `permits` positivos.
package sus.authz

import rego.v1

redactions := data.data.redactions

# ---------------------------------------------------------------------------
# profissional_aps
# ---------------------------------------------------------------------------

aps_read_types := {"citizen", "identifier", "timeline_event", "appointment", "task", "care_plan"}

aps_write_types := {"task", "care_plan"}

# Leitura por vínculo de equipe: qualquer sensibilidade (inclui highly_restricted).
permits contains permit("aps.read_team_bound", false, [], false, false) if {
	tenant_match
	has_role("profissional_aps")
	action_is("read")
	type_in(aps_read_types)
	purpose_allowed_for("profissional_aps")
	team_bound
}

# Leitura por vínculo de unidade (CNES): até `restricted`.
permits contains permit("aps.read_cnes_bound", false, [], false, false) if {
	tenant_match
	has_role("profissional_aps")
	action_is("read")
	type_in(aps_read_types)
	purpose_allowed_for("profissional_aps")
	cnes_bound
	sensitivity_at_most("restricted")
}

# Escrita de plano de cuidado / tarefa da própria equipe.
permits contains permit("aps.write_team_task_or_careplan", false, [], false, false) if {
	tenant_match
	has_role("profissional_aps")
	action_in({"write", "transition_task"})
	type_in(aps_write_types)
	domain_in({"careplan", "task"})
	purpose_allowed_for("profissional_aps")
	aps_team_scope
}

aps_team_scope if team_bound

aps_team_scope if assignee_team_bound

aps_team_scope if assignee_is_subject

# ---------------------------------------------------------------------------
# acs — somente a própria microárea, domínios identity/schedule/task,
# nunca sensibilidade alta, sempre mascarado e com campos clínicos redigidos.
# ---------------------------------------------------------------------------

acs_types := {"citizen", "timeline_event", "appointment", "task"}

acs_domains := {"identity", "schedule", "task"}

permits contains permit("acs.read_microarea_minimal", true, redactions.acs, false, false) if {
	tenant_match
	has_role("acs")
	action_is("read")
	type_in(acs_types)
	domain_in(acs_domains)
	purpose_allowed_for("acs")
	microarea_bound
	sensitivity_at_most("restricted")
}

permits contains permit("acs.transition_own_task", true, redactions.acs, false, false) if {
	tenant_match
	has_role("acs")
	action_in({"write", "transition_task"})
	type_is("task")
	domain_is("task")
	purpose_allowed_for("acs")
	microarea_bound
	sensitivity_at_most("internal")
}

# ---------------------------------------------------------------------------
# profissional_hospitalar — domínio hospital do próprio CNES.
# ---------------------------------------------------------------------------

hosp_types := {"citizen", "identifier", "timeline_event", "task", "care_plan"}

permits contains permit("hospital.read_own_cnes", false, [], false, false) if {
	tenant_match
	has_role("profissional_hospitalar")
	action_is("read")
	type_in(hosp_types)
	domain_in({"hospital", "identity"})
	purpose_allowed_for("profissional_hospitalar")
	cnes_bound
}

permits contains permit("hospital.write_own_cnes", false, [], false, false) if {
	tenant_match
	has_role("profissional_hospitalar")
	action_in({"write", "transition_task"})
	type_in({"timeline_event", "task", "care_plan"})
	domain_in({"hospital", "task", "careplan"})
	purpose_allowed_for("profissional_hospitalar")
	cnes_bound
}

# ---------------------------------------------------------------------------
# regulador — filas de regulação, exames e agenda; finalidade `regulation`.
# Sem hospital/careplan `highly_restricted`.
# ---------------------------------------------------------------------------

permits contains permit("regulador.regulation_request", false, [], false, false) if {
	tenant_match
	has_role("regulador")
	action_in({"read", "write", "transition_task"})
	type_in({"regulation_request", "task"})
	domain_in({"regulation", "task"})
	purpose_allowed_for("regulador")
}

permits contains permit("regulador.read_timeline_regulation_domains", false, [], false, false) if {
	tenant_match
	has_role("regulador")
	action_is("read")
	type_in({"timeline_event", "citizen", "identifier", "appointment"})
	domain_in({"regulation", "exam", "schedule", "identity"})
	purpose_allowed_for("regulador")
}

permits contains permit("regulador.read_timeline_hospital_careplan_limited", false, [], false, false) if {
	tenant_match
	has_role("regulador")
	action_is("read")
	type_is("timeline_event")
	domain_in({"hospital", "careplan"})
	purpose_allowed_for("regulador")
	sensitivity_at_most("restricted")
}

# ---------------------------------------------------------------------------
# agendador — identificação/contato e agenda; identificadores mascarados;
# sem domínio clínico.
# ---------------------------------------------------------------------------

permits contains permit("agendador.read_identity_schedule", true, redactions.agendador, false, false) if {
	tenant_match
	has_role("agendador")
	action_is("read")
	type_in({"citizen", "appointment", "timeline_event", "task"})
	domain_in({"identity", "schedule", "task"})
	purpose_allowed_for("agendador")
	sensitivity_at_most("restricted")
}

permits contains permit("agendador.write_appointment", true, redactions.agendador, false, false) if {
	tenant_match
	has_role("agendador")
	action_in({"write", "transition_task"})
	type_in({"appointment", "task"})
	domain_in({"schedule", "task"})
	purpose_allowed_for("agendador")
	sensitivity_at_most("restricted")
}
