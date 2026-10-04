# Regras dos papéis de gestão, auditoria e operação:
# admin_municipal, gestor, auditor, dpo, operador_integracao, cadastro_mestre.
package sus.authz

import rego.v1

# ---------------------------------------------------------------------------
# admin_municipal — gestão do tenant (usuários, conectores, políticas,
# configuração). NÃO lê dado clínico de cidadão por padrão: para isso
# precisa de break-glass (ver special.rego).
# ---------------------------------------------------------------------------

permits contains permit("admin.manage_tenant", false, [], false, false) if {
	tenant_match
	has_role("admin_municipal")
	action_in({"read", "write"})
	type_in(management_types)
}

permits contains permit("admin.read_audit", true, [], false, false) if {
	tenant_match
	has_role("admin_municipal")
	action_is("read")
	type_in(audit_types)
	purpose_allowed_for("admin_municipal")
}

permits contains permit("admin.read_integration", true, data.data.redactions.operador_integracao, false, false) if {
	tenant_match
	has_role("admin_municipal")
	action_is("read")
	type_in(integration_types)
	purpose_allowed_for("admin_municipal")
}

# ---------------------------------------------------------------------------
# gestor — somente recursos agregados.
# ---------------------------------------------------------------------------

permits contains permit("gestor.read_aggregate", true, [], false, false) if {
	tenant_match
	has_role("gestor")
	action_is("read")
	type_in(aggregate_types)
	purpose_allowed_for("gestor")
}

# ---------------------------------------------------------------------------
# auditor — produção e evidências, finalidade production_audit,
# identificadores mascarados.
# ---------------------------------------------------------------------------

permits contains permit("auditor.read_production", true, data.data.redactions.auditor, false, false) if {
	tenant_match
	has_role("auditor")
	action_in({"read", "write"})
	type_in(production_types)
	purpose_allowed_for("auditor")
}

permits contains permit("auditor.read_production_timeline", true, data.data.redactions.auditor, false, false) if {
	tenant_match
	has_role("auditor")
	action_is("read")
	type_in({"timeline_event", "task"})
	domain_in({"production", "task"})
	purpose_allowed_for("auditor")
}

# ---------------------------------------------------------------------------
# dpo — trilhas de auditoria, logs de acesso e políticas.
# ---------------------------------------------------------------------------

permits contains permit("dpo.read_audit", true, [], false, false) if {
	tenant_match
	has_role("dpo")
	action_is("read")
	type_in(audit_types)
	purpose_allowed_for("dpo")
}

permits contains permit("dpo.read_policy", false, [], false, false) if {
	tenant_match
	has_role("dpo")
	action_is("read")
	type_in({"policy", "decision_log"})
	purpose_allowed_for("dpo")
}

# ---------------------------------------------------------------------------
# operador_integracao — mensagens, DLQ, reconciliação e reprocessamento.
# Payload sensível sempre redigido.
# ---------------------------------------------------------------------------

permits contains permit("integration.read_messages", true, data.data.redactions.operador_integracao, false, false) if {
	tenant_match
	has_role("operador_integracao")
	action_is("read")
	type_in(integration_types)
	purpose_allowed_for("operador_integracao")
}

permits contains permit("integration.reprocess", true, data.data.redactions.operador_integracao, true, false) if {
	tenant_match
	has_role("operador_integracao")
	action_in({"reprocess", "write"})
	type_in(integration_types)
	purpose_allowed_for("operador_integracao")
}

# ---------------------------------------------------------------------------
# cadastro_mestre — revisão de duplicidades (leitura de identidade).
# merge/unmerge ficam em special.rego.
# ---------------------------------------------------------------------------

permits contains permit("cadastro_mestre.read_identity", false, [], false, false) if {
	tenant_match
	has_role("cadastro_mestre")
	action_in({"read", "write"})
	type_in({"citizen", "identifier", "merge_case"})
	domain_is("identity")
	purpose_allowed_for("cadastro_mestre")
}
