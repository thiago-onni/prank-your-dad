# Escopos FHIR (SMART-like) para o FHIR Gateway.
#
# input:
# {
#   "scopes": ["user/Patient.read", "patient/*.rs"],
#   "interaction": "read|vread|search|history|create|update|patch|delete",
#   "resourceType": "Patient",
#   "subject": {"id": "u1", "tenant": "ibge_3143302", "client_type": "user|service",
#               "patient": "cit_01H..."},            # compartimento (contexto patient/)
#   "resource": {"tenant": "ibge_3143302", "patient": "cit_01H...",
#                "security": ["highly_restricted"]}  # opcional (meta.security)
# }
#
# Gramática de escopo: <context>/<ResourceType|*>.<perm>
#   context ∈ {patient, user, system}
#   perm    ∈ {read, write, *}            -> visão COMPLETA (SMART v1)
#           | combinação de letras c r u d s -> visão RESTRITA (ex.: `rs`)
#
# Convenção SUS Nexus: escopos granulares (`rs`, `r`, `crus`...) são tratados
# como "restritos": habilitam a interação, mas o gateway deve remover os
# elementos listados em `redact_elements` (data/fhir.json). Recursos marcados
# `highly_restricted` em meta.security só são acessíveis com escopo completo.
#
# output (data.sus.fhir.decision):
#   {"allow", "reasons", "redact_elements", "matched_scopes"}
package sus.fhir

import rego.v1

policy_version := "1.0.0"

cfg := data.data.fhir

full_perms := {
	"read": {"r", "s"},
	"write": {"c", "u", "d"},
	"*": {"c", "r", "u", "d", "s"},
}

granular_letters := {"c", "r", "u", "d", "s"}

# ---------------------------------------------------------------------------
# Parsing de escopos
# ---------------------------------------------------------------------------

parsed_scopes contains scope if {
	some raw in input.scopes
	parts := split(raw, "/")
	count(parts) == 2
	parts[0] in {"patient", "user", "system"}
	type_perm := split(parts[1], ".")
	count(type_perm) == 2
	perms := perms_of(type_perm[1])
	count(perms) > 0
	scope := {
		"raw": raw,
		"context": parts[0],
		"type": type_perm[0],
		"perms": perms,
		"restricted": is_granular(type_perm[1]),
	}
}

perms_of(perm) := full_perms[perm]

perms_of(perm) := letters if {
	not full_perms[perm]
	letters := {c | some c in split(perm, ""); c in granular_letters}
	count(letters) == count(perm)
}

is_granular(perm) := false if full_perms[perm]

is_granular(perm) if not full_perms[perm]

# ---------------------------------------------------------------------------
# Predicados
# ---------------------------------------------------------------------------

tenant_match if input.subject.tenant == input.resource.tenant

supported_type if input.resourceType in cfg.resource_types

needed_perm := cfg.interaction_permission[input.interaction]

is_write if needed_perm in {"c", "u", "d"}

type_matches(scope) if scope.type == "*"

type_matches(scope) if scope.type == input.resourceType

context_matches(scope) if {
	scope.context == "patient"
	input.subject.client_type == "user"
	input.resourceType in cfg.patient_compartment
	input.subject.patient == input.resource.patient
}

context_matches(scope) if {
	scope.context == "user"
	input.subject.client_type == "user"
}

context_matches(scope) if {
	scope.context == "system"
	input.subject.client_type == "service"
}

matching_scopes contains scope if {
	some scope in parsed_scopes
	type_matches(scope)
	context_matches(scope)
	needed_perm in scope.perms
}

full_scope_matched if {
	some scope in matching_scopes
	scope.restricted == false
}

highly_restricted_resource if "highly_restricted" in input.resource.security

# ---------------------------------------------------------------------------
# Decisão
# ---------------------------------------------------------------------------

default allow := false

allow if {
	tenant_match
	supported_type
	count(matching_scopes) > 0
	not highly_restricted_resource
}

allow if {
	tenant_match
	supported_type
	full_scope_matched
	highly_restricted_resource
}

# Redação só se a visão for restrita (nenhum escopo completo casou) e a
# interação for de leitura.
restricted_view if {
	allow
	not full_scope_matched
	not is_write
}

redact_elements := sort(elements) if {
	restricted_view
	generic := {e | some e in cfg.restricted_redactions["*"]}
	specific := {e | some e in object.get(cfg.restricted_redactions, input.resourceType, [])}
	elements := generic | specific
}

redact_elements := [] if not restricted_view

deny_reasons contains "tenant_mismatch" if not tenant_match

deny_reasons contains "unsupported_resource_type" if not supported_type

deny_reasons contains "unknown_interaction" if not cfg.interaction_permission[input.interaction]

deny_reasons contains "no_matching_scope" if {
	tenant_match
	supported_type
	count(matching_scopes) == 0
}

deny_reasons contains "highly_restricted_requires_full_scope" if {
	highly_restricted_resource
	count(matching_scopes) > 0
	not full_scope_matched
}

reasons := sort({s.raw | some s in matching_scopes}) if allow

reasons := sort(deny_reasons) if not allow

decision := {
	"allow": allow,
	"reasons": reasons,
	"redact_elements": redact_elements,
	"matched_scopes": sort({s.raw | some s in matching_scopes}),
	"policy_version": policy_version,
}
