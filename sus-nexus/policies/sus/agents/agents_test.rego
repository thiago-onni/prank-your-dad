package sus.agents_test

import rego.v1

import data.sus.agents

no_kill := {"global": false, "agents": [], "tools": [], "tenants": []}

req(tool, granted, requested, kill) := {
	"agent": {"id": "agent_busca_ativa", "version": "1.3.0", "tools_granted": granted},
	"tool": tool,
	"action_class_requested": requested,
	"tenant": "ibge_3143302",
	"kill_switch": kill,
}

test_auto_tool_granted_allowed if {
	d := agents.decision with input as req("task.create", ["task.create"], "auto", no_kill)
	d.allow == true
	d.action_class == "auto"
	d.requires_approval == false
	d.risk == "medium"
	d.reasons == ["granted"]
}

test_requires_approval_tool_allowed_with_flag if {
	d := agents.decision with input as req("communication.send_reminder", ["communication.send_reminder"], "requires_approval", no_kill)
	d.allow == true
	d.action_class == "requires_approval"
	d.requires_approval == true
}

test_requires_approval_tool_requested_as_auto_denied if {
	d := agents.decision with input as req("communication.send_reminder", ["communication.send_reminder"], "auto", no_kill)
	d.allow == false
	"requested_action_class_below_catalog" in d.reasons
}

test_auto_tool_requested_as_requires_approval_allowed if {
	agents.allow with input as req("task.create", ["task.create"], "requires_approval", no_kill)
}

test_no_requested_class_defaults_to_catalog if {
	d := agents.decision with input as object.remove(req("task.create", ["task.create"], "auto", no_kill), ["action_class_requested"])
	d.allow == true
}

test_forbidden_regulation_change_priority_denied_even_if_granted if {
	d := agents.decision with input as req("regulation.change_priority", ["regulation.change_priority"], "requires_approval", no_kill)
	d.allow == false
	d.action_class == "forbidden"
	"tool_forbidden_for_agents" in d.reasons
}

test_forbidden_regulation_decide_denied if {
	not agents.allow with input as req("regulation.decide", ["regulation.decide"], "forbidden", no_kill)
}

test_forbidden_production_transmit_denied if {
	not agents.allow with input as req("production.transmit", ["production.transmit"], "auto", no_kill)
}

test_forbidden_mpi_merge_denied if {
	not agents.allow with input as req("mpi.merge", ["mpi.merge"], "auto", no_kill)
}

test_forbidden_send_clinical_content_denied if {
	not agents.allow with input as req("communication.send_clinical_content", ["communication.send_clinical_content"], "auto", no_kill)
}

test_forbidden_db_direct_access_denied if {
	not agents.allow with input as req("db.direct_access", ["db.direct_access"], "auto", no_kill)
}

test_tool_not_in_catalog_denied if {
	d := agents.decision with input as req("shell.exec", ["shell.exec"], "auto", no_kill)
	d.allow == false
	d.action_class == "unknown"
	"tool_not_in_catalog" in d.reasons
}

test_tool_not_granted_denied if {
	d := agents.decision with input as req("task.create", ["protocol.search"], "auto", no_kill)
	d.allow == false
	"tool_not_granted_to_agent" in d.reasons
}

test_kill_switch_global_denies if {
	d := agents.decision with input as req("task.create", ["task.create"], "auto", object.union(no_kill, {"global": true}))
	d.allow == false
	"kill_switch_global" in d.reasons
}

test_kill_switch_agent_denies if {
	d := agents.decision with input as req("task.create", ["task.create"], "auto", object.union(no_kill, {"agents": ["agent_busca_ativa"]}))
	d.allow == false
	"kill_switch_agent" in d.reasons
}

test_kill_switch_tool_denies if {
	d := agents.decision with input as req("task.create", ["task.create"], "auto", object.union(no_kill, {"tools": ["task.create"]}))
	d.allow == false
	"kill_switch_tool" in d.reasons
}

test_kill_switch_tenant_denies if {
	d := agents.decision with input as req("task.create", ["task.create"], "auto", object.union(no_kill, {"tenants": ["ibge_3143302"]}))
	d.allow == false
	"kill_switch_tenant" in d.reasons
}

test_kill_switch_other_agent_does_not_affect if {
	agents.allow with input as req("task.create", ["task.create"], "auto", object.union(no_kill, {"agents": ["agent_pos_alta"], "tenants": ["ibge_9999999"]}))
}

test_agent_identity_incomplete_denied if {
	base := req("task.create", ["task.create"], "auto", no_kill)
	d := agents.decision with input as object.union(base, {"agent": {"id": "", "version": "1.0.0", "tools_granted": ["task.create"]}})
	d.allow == false
	"agent_identity_incomplete" in d.reasons
}

test_missing_tenant_denied if {
	base := req("task.create", ["task.create"], "auto", no_kill)
	not agents.allow with input as object.union(base, {"tenant": ""})
}

test_decision_shape if {
	d := agents.decision with input as req("task.create", ["task.create"], "auto", no_kill)
	object.keys(d) == {"allow", "action_class", "requires_approval", "risk", "reasons", "policy_version"}
}

# Garantia estática: todo item do catálogo tem action_class válido e risk.
test_catalog_integrity if {
	every name, tool in data.data.agent_tools.tools {
		tool.action_class in {"auto", "requires_approval", "forbidden"}
		tool.risk in {"low", "medium", "high", "critical"}
		count(name) > 0
	}
}
