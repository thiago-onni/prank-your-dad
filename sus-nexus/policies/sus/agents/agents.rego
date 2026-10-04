# Ações de agentes de IA (AIA-001/004/009/012, SEC-012).
#
# input:
# {
#   "agent": {"id": "agent_busca_ativa", "version": "1.3.0", "tools_granted": ["task.create"]},
#   "tool": "task.create",
#   "action_class_requested": "auto",
#   "tenant": "ibge_3143302",
#   "kill_switch": {"global": false, "agents": [], "tools": [], "tenants": []}
# }
#
# output (data.sus.agents.decision):
#   {"allow": bool, "action_class": "auto|requires_approval|forbidden|unknown",
#    "requires_approval": bool, "reasons": [...]}
#
# Regras:
#   - a ferramenta deve existir no catálogo (data/agent_tools.json) E estar em tools_granted;
#   - action_class "forbidden" é sempre negada (bloqueio por política, não por prompt);
#   - kill switch (global, por agente, por ferramenta, por tenant) nega;
#   - o agente não pode "rebaixar" a classe: pedir `auto` para uma ferramenta
#     `requires_approval` é negado (o runner deve reenviar como requires_approval);
#   - agentes com perfil em `agent_profiles` (ex.: `bi_situation_analyst`) só usam ferramentas da
#     camada de dado permitida (`data_layer`, padrão "operational") e, se `read_only`, só `kind=read`.
#
# Invocação humana (data.sus.agents.invoke) de agentes com perfil:
#   input: {"agent": {"id": "bi_situation_analyst"},
#           "subject": {"roles": ["gestor"], "tenant": "ibge_3143302"},
#           "tenant": "ibge_3143302", "kill_switch": {...}}
#   → {"allow": bool, "reasons": [...]} — papel em `invoker_roles` e tenant do token = tenant.
package sus.agents

import rego.v1

policy_version := "1.1.0"

catalog := data.data.agent_tools.tools

tool_entry := catalog[input.tool]

in_catalog if catalog[input.tool]

granted if input.tool in input.agent.tools_granted

default action_class := "unknown"

action_class := tool_entry.action_class

forbidden if action_class == "forbidden"

default requires_approval := false

requires_approval if action_class == "requires_approval"

class_rank := {"auto": 0, "requires_approval": 1, "forbidden": 2}

# A classe pedida pelo runner deve ser pelo menos tão restritiva quanto a do catálogo.
requested_class_ok if {
	requested_rank := class_rank[input.action_class_requested]
	requested_rank >= class_rank[action_class]
}

# Sem pedido explícito, assume-se a classe do catálogo.
requested_class_ok if not input.action_class_requested

identity_ok if {
	count(input.agent.id) > 0
	count(input.agent.version) > 0
	count(input.tenant) > 0
}

# ---------------------------------------------------------------------------
# Perfis de agente: camada de dado e somente leitura (ex.: BI só lê agregados)
# ---------------------------------------------------------------------------

profiles := object.get(data.data.agent_tools, "agent_profiles", {})

has_profile if profiles[input.agent.id]

agent_profile := profiles[input.agent.id]

default tool_data_layer := "operational"

tool_data_layer := tool_entry.data_layer

default tool_kind := "write"

tool_kind := tool_entry.kind

data_layer_ok if not has_profile

data_layer_ok if tool_data_layer in agent_profile.allowed_data_layers

read_only_violation if {
	agent_profile.read_only == true
	tool_kind != "read"
}

# ---------------------------------------------------------------------------
# Kill switch (AIA-009)
# ---------------------------------------------------------------------------

kill_switch_active if input.kill_switch.global == true

kill_switch_active if input.agent.id in input.kill_switch.agents

kill_switch_active if input.tool in input.kill_switch.tools

kill_switch_active if input.tenant in input.kill_switch.tenants

# ---------------------------------------------------------------------------
# Decisão
# ---------------------------------------------------------------------------

default allow := false

allow if {
	identity_ok
	in_catalog
	granted
	not forbidden
	not kill_switch_active
	requested_class_ok
	data_layer_ok
	not read_only_violation
}

deny_reasons contains "agent_identity_incomplete" if not identity_ok

deny_reasons contains "tool_not_in_catalog" if not in_catalog

deny_reasons contains "tool_not_granted_to_agent" if {
	in_catalog
	not granted
}

deny_reasons contains "tool_forbidden_for_agents" if forbidden

deny_reasons contains "kill_switch_global" if input.kill_switch.global == true

deny_reasons contains "kill_switch_agent" if input.agent.id in input.kill_switch.agents

deny_reasons contains "kill_switch_tool" if input.tool in input.kill_switch.tools

deny_reasons contains "kill_switch_tenant" if input.tenant in input.kill_switch.tenants

deny_reasons contains "requested_action_class_below_catalog" if {
	in_catalog
	not forbidden
	not requested_class_ok
}

deny_reasons contains "tool_data_layer_not_allowed_for_agent" if {
	in_catalog
	not data_layer_ok
}

deny_reasons contains "agent_is_read_only" if {
	in_catalog
	read_only_violation
}

reasons := ["granted"] if allow

reasons := sort(deny_reasons) if not allow

decision := {
	"allow": allow,
	"action_class": action_class,
	"requires_approval": requires_approval,
	"risk": risk,
	"reasons": reasons,
	"policy_version": policy_version,
}

default risk := "unknown"

risk := tool_entry.risk

# ---------------------------------------------------------------------------
# Invocação humana de agentes com perfil (ex.: BI: gestor, auditor, admin_municipal)
# ---------------------------------------------------------------------------

invoker_role_ok if {
	some role in input.subject.roles
	role in agent_profile.invoker_roles
}

invoke_tenant_ok if {
	count(input.tenant) > 0
	input.subject.tenant == input.tenant
}

default invoke_allow := false

invoke_allow if {
	has_profile
	invoker_role_ok
	invoke_tenant_ok
	not kill_switch_active
}

invoke_deny_reasons contains "agent_without_invocation_profile" if not has_profile

invoke_deny_reasons contains "role_not_allowed_to_invoke" if {
	has_profile
	not invoker_role_ok
}

invoke_deny_reasons contains "tenant_mismatch" if not invoke_tenant_ok

invoke_deny_reasons contains "kill_switch" if kill_switch_active

invoke_reasons := ["granted"] if invoke_allow

invoke_reasons := sort(invoke_deny_reasons) if not invoke_allow

invoke := {
	"allow": invoke_allow,
	"reasons": invoke_reasons,
	"policy_version": policy_version,
}
