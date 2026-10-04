# SUS Nexus — Políticas de autorização (OPA / Rego v1)

Políticas de autorização da plataforma, avaliadas pelo OPA (`:8181`) e chamadas por todos os serviços (core-municipal, fhir-gateway, connectors, ai-service). Derivam de `docs/sus-nexus/PLANO_IMPLEMENTACAO.md` §12.2 (RBAC + ABAC + finalidade), §9.2 (ações proibidas de agentes) e `CONVENTIONS.md` (segurança).

Princípios:

- **Deny by default.** Toda decisão é `false` a menos que uma regra positiva (`permits`) case.
- **Tenant é absoluto.** `subject.tenant != resource.tenant` nega sempre, inclusive para `admin_municipal` e com break-glass.
- **Finalidade obrigatória.** Cada papel tem finalidades permitidas (`data/purposes.json`); finalidade errada nega.
- **Vínculo + sensibilidade.** Acesso a dado individual exige vínculo (equipe, CNES ou microárea) e respeita `sensitivity`.
- **Obrigações, não só sim/não.** A decisão devolve o que o serviço deve fazer: mascarar identificadores, redigir campos, exigir justificativa, alertar o DPO, registrar acesso.
- **Agentes nunca contornam políticas** (SEC-012): nunca merge/unmerge/export/reveal/break-glass; ferramentas `forbidden` negadas por política, não por prompt.

## Layout

```text
policies/
├── sus/
│   ├── authz/        data.sus.authz   — acesso a dados (decision, allow, filter)
│   │   ├── helpers.rego          predicados compartilhados (tenant, vínculo, finalidade, sensibilidade)
│   │   ├── roles_clinical.rego   profissional_aps, acs, profissional_hospitalar, regulador, agendador
│   │   ├── roles_management.rego admin_municipal, gestor, auditor, dpo, operador_integracao, cadastro_mestre
│   │   ├── special.rego          reveal_identifier, merge/unmerge, export (delegado), break-glass
│   │   ├── decision.rego         allow, filter, obligations, reasons, decision
│   │   └── authz_test.rego
│   ├── agents/       data.sus.agents  — ferramentas de agentes de IA, kill switch
│   ├── fhir/         data.sus.fhir    — escopos SMART-like e redação de elementos FHIR
│   └── export/       data.sus.export  — exportações (SEC-008)
├── data/             dados estáticos (montados em data.data.*)
│   ├── roles.json        catálogo de papéis + grupos (merge_roles, export_roles, ...)
│   ├── purposes.json     finalidades por papel
│   ├── domains.json      domínios → sensibilidade padrão, níveis, tipos de recurso, redações por papel
│   ├── agent_tools.json  catálogo de ferramentas de agente (action_class, risk, owner, kind, data_layer) + agent_profiles
│   └── fhir.json         tipos FHIR suportados, mapa interação→permissão, elementos redigidos
├── examples/         inputs de exemplo (não são carregados como data)
├── bundle/build.sh   gera bundle.tar.gz com .manifest (roots: sus, data)
├── Makefile          fmt | check | test | bundle | eval | partial
└── VERSION           versão semântica das políticas
```

> Todos os comandos carregam `policies/` como raiz (`opa ... -d .`). Assim `data/*.json` fica em `data.data.*` (o nome do arquivo é descartado pelo OPA; só o diretório conta), exatamente como no bundle servido em produção.

## Comandos

```bash
cd sus-nexus/policies
make test      # opa fmt --diff --fail + opa check --strict + opa test -v  (173 casos)
make check     # só formatação + verificação estrita
make bundle    # bundle/bundle.tar.gz (revision = VERSION+git sha)
make eval      # decisão para examples/aps_read_team.json
make partial   # partial evaluation (filtros de consulta)
make eval EXAMPLE=examples/break_glass.json
```

## 1. Autorização de dados — `data.sus.authz`

### Input

```json
{
  "subject": {
    "id": "u1",
    "roles": ["profissional_aps"],
    "tenant": "ibge_3143302",
    "cnes": ["1234567"],
    "teams": ["ine_0001"],
    "microareas": ["03"],
    "client_type": "user"
  },
  "action": "read",
  "resource": {
    "type": "timeline_event",
    "tenant": "ibge_3143302",
    "domain": "aps",
    "sensitivity": "restricted",
    "citizen_team": "ine_0001",
    "citizen_cnes": "1234567",
    "citizen_microarea": "03",
    "assignee": {"kind": "team", "id": "ine_0001"}
  },
  "context": {
    "purpose": "care_coordination",
    "break_glass": false,
    "break_glass_justification": "",
    "channel": "web"
  }
}
```

| Campo | Valores |
|---|---|
| `subject.client_type` | `user` \| `service` \| `agent` |
| `subject.roles` | ver `data/roles.json`: `admin_municipal`, `gestor`, `profissional_aps`, `acs`, `regulador`, `agendador`, `profissional_hospitalar`, `auditor`, `dpo`, `operador_integracao`, `cadastro_mestre` |
| `action` | `read` \| `write` \| `reveal_identifier` \| `merge` \| `unmerge` \| `reprocess` \| `export` \| `transition_task` |
| `resource.type` | `citizen` \| `timeline_event` \| `merge_case` \| `task` \| `integration_message` \| `appointment` \| `regulation_request` \| `production_record` \| `audit_log` \| `identifier` — e os tipos estendidos de `data/domains.json#resource_types` (`care_plan`, `access_log`, `dlq_message`, `indicator`, `connector`, `policy`, ...) |
| `resource.domain` | `identity` \| `aps` \| `schedule` \| `regulation` \| `exam` \| `hospital` \| `careplan` \| `task` \| `production` \| `communication` |
| `resource.sensitivity` | `public` < `internal` < `restricted` < `highly_restricted` |
| `resource.assignee` | `{"kind": "team"\|"user", "id": "..."}` (tarefas) |
| `context.purpose` | `care_coordination` \| `regulation` \| `scheduling` \| `identity_management` \| `production_audit` \| `public_health_surveillance` \| `management_analytics` \| `integration_operations` \| `security_audit` |
| `context.channel` | `web` \| `api` \| `agent` |

Campos opcionais do contrato (`citizen_*`, `assignee`) podem ser omitidos para recursos que não são de cidadão (ex.: `connector`, `audit_log`); a regra que depende deles simplesmente não casa.

### Output

```json
{
  "allow": true,
  "reasons": ["aps.read_team_bound"],
  "obligations": {
    "mask_identifiers": false,
    "redact_fields": [],
    "log_access": true,
    "require_justification": false,
    "alert_dpo": false
  },
  "policy_version": "1.0.0"
}
```

- `reasons`: ids das regras que concederam (`allow=true`) ou códigos de negação (`allow=false`, ex.: `tenant_mismatch`, `purpose_not_allowed_for_roles`, `gestor_individual_data`, `admin_clinical_requires_break_glass`, `break_glass_justification_too_short`, `agent_not_allowed_for_action`, `merge_requires_cadastro_mestre_or_admin`, `export_requires_dpo_gestor_or_auditor`).
- `obligations` (o serviço **deve** cumprir, senão deve tratar como negado):
  - `mask_identifiers`: devolver CPF/CNS mascarados (`***.***.***-12`). `true` a menos que alguma regra concedida seja explicitamente "sem máscara" (ex.: profissional vinculado).
  - `redact_fields`: remover estes campos da resposta (ex.: ACS → `diagnoses`, `results`, `documents`). União das regras concedidas; vazio se alguma regra concedida não exige redação (visão mais ampla prevalece quando o usuário acumula papéis).
  - `log_access`: sempre `true` — toda leitura gera `access_log` (quem, o quê, finalidade, correlation_id).
  - `require_justification`: a chamada precisa carregar justificativa (reveal, merge/unmerge, reprocess, export, break-glass).
  - `alert_dpo`: disparar alerta imediato ao DPO (break-glass, SEC-011).
- `data.sus.authz.allow` (booleano) para quem só precisa do sim/não.

### Matriz de regras (resumo)

| Papel | Lê | Escreve | Condições |
|---|---|---|---|
| `admin_municipal` | `tenant_config`, `user`, `connector`, `policy`, `role_assignment`, `agent_config`; `audit_log`/`access_log`; `integration_*` | idem gestão | **Não** lê dado de cidadão sem break-glass. Merge/unmerge com `identity_management`. |
| `gestor` | só agregados (`indicator`, `dashboard`, `aggregate_report`, `production_summary`) | — | `management_analytics` \| `public_health_surveillance`. Dado individual → `gestor_individual_data`. |
| `profissional_aps` | `citizen`, `identifier`, `timeline_event`, `appointment`, `task`, `care_plan` | `task`, `care_plan` da própria equipe (ou `assignee` = equipe/usuário) | `citizen_team ∈ teams` (qualquer sensibilidade) **ou** `citizen_cnes ∈ cnes` (até `restricted`); `care_coordination` \| `scheduling`. |
| `acs` | `citizen`, `timeline_event`, `appointment`, `task` em `identity`/`schedule`/`task` | `transition_task`/`write` em `task` | `citizen_microarea ∈ microareas`; nunca `highly_restricted`; nunca `aps`/`hospital`/`exam`; mascara + redige `diagnoses, results, documents`. |
| `regulador` | `regulation_request`, `task`; `timeline_event` em `regulation`/`exam`/`schedule`/`identity`; `hospital`/`careplan` só até `restricted` | `regulation_request`, `task` | `purpose = regulation`. Sem vínculo (filas do município). |
| `agendador` | `citizen` (identity), `appointment`, `timeline_event`/`task` em `schedule`/`task` | `appointment`, `task` | `scheduling`; até `restricted`; mascara; sem clínico. |
| `profissional_hospitalar` | `hospital` + `identity` | `timeline_event`/`task`/`care_plan` em `hospital`/`task`/`careplan` | `citizen_cnes ∈ cnes`; `care_coordination`. |
| `auditor` | `production_record`, `production_evidence`; `timeline_event`/`task` em `production` | `production_*` | `production_audit`; mascara; redige `clinical_notes`. |
| `dpo` | `audit_log`, `access_log`, `decision_log`, `policy` | — | `security_audit`; mascara. |
| `operador_integracao` | `integration_message`, `dlq_message`, `reconciliation_report` | `reprocess`/`write` (justificativa) | `integration_operations`; mascara; redige `payload_sensitive`. |
| `cadastro_mestre` | `citizen`, `identifier`, `merge_case` (identity) | idem | `identity_management`. Merge/unmerge. |

Ações especiais:

- **`reveal_identifier`**: `profissional_aps` (vinculado), `agendador`, `regulador`, `auditor` (`production_audit`), `admin_municipal` só com break-glass. Sempre `require_justification=true`, `log_access=true`. Nunca agentes.
- **`merge` / `unmerge`**: `cadastro_mestre` ou `admin_municipal`, `purpose=identity_management`, nunca `client_type=agent`. Justificativa obrigatória.
- **`export`**: ver §4. Nunca agentes; break-glass não habilita.
- **Break-glass** (SEC-011): `context.break_glass=true` + justificativa ≥ 20 caracteres + `client_type=user` + papel `break_glass_eligible` (`profissional_aps`, `profissional_hospitalar`, `regulador`, `admin_municipal`) ⇒ permite `read` de recurso de cidadão fora do vínculo com `reasons=["break_glass"]`, `alert_dpo=true`, `require_justification=true`. Só para `read` (e `reveal_identifier` do admin); nunca export/merge; nunca agentes. Tenant continua absoluto.

## 2. Agentes de IA — `data.sus.agents`

```json
{
  "agent": {"id": "agent_busca_ativa", "version": "1.3.0", "tools_granted": ["task.create", "communication.send_reminder"]},
  "tool": "communication.send_reminder",
  "action_class_requested": "requires_approval",
  "tenant": "ibge_3143302",
  "kill_switch": {"global": false, "agents": [], "tools": [], "tenants": []}
}
```

→ `{"allow": true, "action_class": "requires_approval", "requires_approval": true, "risk": "high", "reasons": ["granted"], "policy_version": "1.1.0"}`

Regras: a ferramenta deve existir em `data/agent_tools.json` **e** em `agent.tools_granted`; `action_class=forbidden` sempre nega (`regulation.change_priority`, `regulation.decide`, `production.transmit`, `mpi.merge`, `mpi.unmerge`, `communication.send_clinical_content`, `identifier.reveal`, `db.direct_access`); kill switch global/por agente/por ferramenta/por tenant nega; o runner não pode pedir classe mais branda que a do catálogo (`auto` para ferramenta `requires_approval` → `requested_action_class_below_catalog`). Identidade (`agent.id`, `agent.version`, `tenant`) é obrigatória.

**Perfis de agente** (`agent_profiles` em `data/agent_tools.json`): cada ferramenta tem `data_layer` (`operational` por padrão; `aggregated` para `bi.*`) e `kind`. Um agente com perfil só usa ferramentas das camadas em `allowed_data_layers` (`tool_data_layer_not_allowed_for_agent`) e, se `read_only`, só `kind=read` (`agent_is_read_only`). Hoje: `bi_situation_analyst` → `["aggregated"]`, somente leitura — o agente de BI nunca lê o core por cidadão nem escreve, mesmo que uma ferramenta seja concedida por engano. O ai-service espelha a regra no executor (`AGENT_PROFILES`, paridade em `ai-service/tests/test_registry.py`).

**Invocação** (`data.sus.agents.invoke`): `{"agent": {"id"}, "subject": {"roles", "tenant"}, "tenant", "kill_switch"}` → `{"allow", "reasons", "policy_version"}`. Permitida se o agente tem perfil, algum papel do sujeito está em `invoker_roles` (BI: `gestor`, `auditor`, `admin_municipal`), o tenant do token é o tenant pedido e não há kill switch; motivos: `agent_without_invocation_profile`, `role_not_allowed_to_invoke`, `tenant_mismatch`, `kill_switch`.

## 3. FHIR — `data.sus.fhir`

```json
{
  "scopes": ["user/*.rs"],
  "interaction": "read",
  "resourceType": "Patient",
  "subject": {"id": "u3", "tenant": "ibge_3143302", "client_type": "user", "patient": "cit_01HZX"},
  "resource": {"tenant": "ibge_3143302", "patient": "cit_01HZX", "security": []}
}
```

→ `{"allow": true, "reasons": ["user/*.rs"], "matched_scopes": ["user/*.rs"], "redact_elements": ["address","contact","extension","identifier","meta.security","photo","telecom"]}`

Gramática `<context>/<ResourceType|*>.<perm>`:

| Escopo | Contexto | Visão |
|---|---|---|
| `patient/*.read`, `patient/Observation.read` | `client_type=user`, só tipos do compartimento Patient e `subject.patient == resource.patient` | completa |
| `user/Patient.read`, `user/*.write`, `user/*.*` | `client_type=user` | completa |
| `system/*.write`, `system/Encounter.read` | `client_type=service` (clientes técnicos) | completa |
| `user/*.rs`, `user/Patient.r`, `user/Patient.cru` (letras `c r u d s`) | idem acima | **restrita**: habilita a interação, mas o gateway remove `redact_elements` (`data/fhir.json#restricted_redactions`) |

Interações: `read`/`vread`/`history` → `r`, `search` → `s`, `create` → `c`, `update`/`patch` → `u`, `delete` → `d`. Recursos com `meta.security` contendo `highly_restricted` exigem escopo completo. Tenant sempre deve coincidir. Escopos malformados são ignorados.

## 4. Exportações — `data.sus.export`

Mesmo input de `sus.authz` com `action="export"`. Permitido apenas para `dpo` (`audit_log`, `access_log`, `decision_log`; `security_audit`), `gestor` (agregados) e `auditor` (`production_record`/`production_evidence`; `production_audit`). Obrigações sempre `mask_identifiers=true` e `require_justification=true` (o fluxo de aprovação, marca d'água e expiração do link ficam no serviço — SEC-008). `data.sus.authz.decision` já incorpora essas regras e razões; `data.sus.export.decision` existe para avaliação isolada.

## Como os serviços chamam o OPA

OPA roda como sidecar/serviço (`http://opa:8181`) com o bundle desta pasta. Endpoint: `POST /v1/data/<path>` com `{"input": {...}}`; a resposta vem em `result`.

```bash
curl -s http://localhost:8181/v1/data/sus/authz/decision \
  -H 'Content-Type: application/json' \
  -d @- <<EOF
{"input": $(cat examples/aps_read_team.json)}
EOF
# {"result": {"allow": true, "reasons": ["aps.read_team_bound"], "obligations": {...}}}
```

Java (Quarkus, `core-municipal`/`fhir-gateway`) — cliente REST com `Decision` tipada; o `subject` vem do JWT (`sub`, `roles`, `municipality_id` → `tenant`, `cnes`, `teams`, `microareas`); `client_type` deriva do tipo de client Keycloak; obrigações aplicadas pelo serializador (máscara/redação) e pelo `AccessLogInterceptor`:

```java
@RegisterRestClient(configKey = "opa")
public interface OpaClient {
  @POST @Path("/v1/data/sus/authz/decision")
  OpaResponse<Decision> authz(OpaRequest<AuthzInput> request);

  @POST @Path("/v1/data/sus/fhir/decision")
  OpaResponse<FhirDecision> fhir(OpaRequest<FhirInput> request);
}
// Decision(boolean allow, List<String> reasons, Obligations obligations, String policyVersion)
// if (!d.allow()) throw new ForbiddenException(problem("authz_denied", d.reasons()));
// if (d.obligations().maskIdentifiers()) view = view.masked();
// accessLog.record(subject, resource, purpose, d.reasons(), d.policyVersion(), correlationId);
```

Python (`ai-service`) — antes de **cada** chamada de ferramenta (AIA-001/012):

```python
async def check_tool(agent: AgentIdentity, tool: str, action_class: str, tenant: str) -> AgentDecision:
    payload = {"input": {"agent": agent.model_dump(), "tool": tool,
                         "action_class_requested": action_class, "tenant": tenant,
                         "kill_switch": await flags.kill_switch()}}
    r = await http.post(f"{OPA_URL}/v1/data/sus/agents/decision", json=payload, timeout=2.0)
    r.raise_for_status()
    return AgentDecision.model_validate(r.json()["result"])   # allow, action_class, requires_approval, reasons
```

Falha de rede/timeout no OPA ⇒ **negar** (fail-closed). Registrar `policy_version` e `reasons` no `audit_log`/`agent_run`. Habilitar *decision logs* do OPA para o tópico `sus.audit.v1`.

## Partial evaluation → filtros de consulta

Para listagens ("todos os cidadãos que este usuário pode ver"), em vez de avaliar item a item, use partial evaluation com `input.resource` desconhecido e a regra `data.sus.authz.filter` (igual a `allow`, mas sem `default`, para o OPA devolver queries planas em vez de módulo de suporte):

```bash
opa eval -d . --ignore examples --ignore bundle \
  -i examples/aps_read_team.json \
  --partial --unknowns input.resource --format pretty \
  'data.sus.authz.filter'
```

```text
+---------+--------------------------------------------------------------------------------------------------------+
| Query 1 | "ibge_3143302" = input.resource.tenant                                                                 |
|         | input.resource.type in {"appointment", "care_plan", "citizen", "identifier", "task", "timeline_event"} |
|         | input.resource.citizen_team in ["ine_0001"]                                                            |
+---------+--------------------------------------------------------------------------------------------------------+
| Query 2 | "ibge_3143302" = input.resource.tenant                                                                 |
|         | input.resource.type in {"appointment", "care_plan", "citizen", "identifier", "task", "timeline_event"} |
|         | input.resource.citizen_cnes in ["1234567"]                                                             |
|         | input.resource.sensitivity in ["public", "internal", "restricted"]                                     |
+---------+--------------------------------------------------------------------------------------------------------+
```

Cada *Query* é uma disjunção (OR); as linhas dentro dela, conjunções (AND). Tradução para SQL (o serviço faz, com `--format json` via `POST /v1/compile`):

```sql
WHERE tenant_id = 'ibge_3143302'
  AND type IN ('appointment','care_plan','citizen','identifier','task','timeline_event')
  AND ( citizen_team IN ('ine_0001')
     OR (citizen_cnes IN ('1234567') AND sensitivity IN ('public','internal','restricted')) )
```

Via API: `POST /v1/compile` com `{"query": "data.sus.authz.filter == true", "input": {...sem resource...}, "unknowns": ["input.resource"]}`. As regras foram escritas só com predicados positivos sobre `input.resource` (igualdade, `in`), sem `not`/`count` sobre o desconhecido, justamente para que o residual seja traduzível. Mantenha esse estilo ao adicionar regras.

## Como adicionar um papel

1. `data/roles.json`: entrada em `roles` (`description`, `category`, `clinical`, `break_glass_eligible`, `default_mask_identifiers`) e, se aplicável, nos grupos `role_groups.*`.
2. `data/purposes.json#by_role`: finalidades permitidas.
3. `data/domains.json#redactions`: campos a redigir, se o papel tiver visão parcial.
4. Regras em `sus/authz/roles_*.rego` no formato `permits contains permit("<papel>.<regra>", mask, redact, justify, break_glass) if { tenant_match; has_role("<papel>"); action_...; type_...; purpose_allowed_for("<papel>"); <vínculo>; <sensibilidade> }`. Sempre começar por `tenant_match`; só predicados positivos sobre `input.resource`.
5. Testes em `authz_test.rego`: pelo menos um positivo, um de tenant errado, um de finalidade errada, um de sensibilidade/vínculo fora.
6. `make test`; incrementar `VERSION` (ver abaixo).

## Como adicionar uma ferramenta de agente

1. `data/agent_tools.json#tools`: `"<dominio>.<acao>": {"action_class": "auto|requires_approval|forbidden", "risk": "low|medium|high|critical", "owner": "<módulo>", "description": "..."}`. O nome deve coincidir com o registrado em `ai-service/tools/registry.py`.
2. Conceder a ferramenta ao agente no registro (vai em `agent.tools_granted` do input) — a política não concede por si.
3. Teste em `agents_test.rego` (positivo e, para `forbidden`, negativo mesmo quando concedida). `test_catalog_integrity` valida a forma do catálogo.

## Versionamento

- `VERSION` (semver) é a versão das políticas; vai no `.manifest` (`metadata.version`) e em `policy_version` de cada decisão, que os serviços gravam no `audit_log`/`agent_run` (reprodutibilidade).
- `revision` do bundle = `VERSION+<git sha curto>` (`make bundle`), visível em `opa inspect bundle/bundle.tar.gz` e no status do OPA.
- Regra: **major** quando uma decisão antes permitida passa a ser negada para algum papel ou o contrato de input/output muda; **minor** para papel/ferramenta/regra nova que só concede; **patch** para correção que não altera decisões em casos testados.
- Fluxo: PR com `make test` verde no CI (cobertura obrigatória), revisão do DPO para mudanças que ampliam acesso, bundle publicado por tag; OPA em produção consome o bundle via `services`/`bundles` (polling) com a revision registrada no `audit_log`.

## Cobertura de testes

`make test` executa 160 casos: tenant mismatch (todos os pacotes), cada papel com casos permitidos e negados (vínculo, finalidade, sensibilidade, domínio), acumulação de papéis, `reveal_identifier`, `merge/unmerge`, break-glass (justificativa curta, agente, papel não elegível, export/merge, tenant), exportações, agentes (forbidden, não catalogada, não concedida, kill switch global/agente/ferramenta/tenant, rebaixamento de classe, identidade incompleta, integridade do catálogo) e escopos FHIR (patient/user/system, compartimento, restrito com redação, `highly_restricted`, escopos malformados, interações desconhecidas).
