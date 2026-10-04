# SUS Nexus — ai-service

Serviço de agentes de IA do SUS Nexus (Python 3.11, FastAPI, Pydantic v2, LangGraph, LiteLLM).
Implementa a seção 9 do `docs/sus-nexus/PLANO_IMPLEMENTACAO.md` (AIA-001…010/012):
ferramentas formais, política OPA com identidade própria do agente, kill switch, minimização de
dados, saída estruturada, aprovação humana e registro reprodutível de cada execução.

**Fase 2** (atual): ferramentas alinhadas ao contrato real do core (`contracts/openapi/core-municipal.yaml`
— regulação e exames), ciclo de ação fechado com aprovação humana (`POST …/issues` só após aprovar),
agente de resultado crítico de exame, consumidor Kafka para os eventos reais e OpenAPI do próprio
serviço exportado para `contracts/openapi/ai-service.yaml`.

## Arquitetura

```text
Gatilho (POST /agents/{id}/run | Kafka | workflow)
   ▼
AgentRunner (LangGraph, checkpoint em memória ou PostgreSQL)
   build_context ──► minimize ──► reason (LLM) ⇄ validate_output ──► classify_actions
        │                                                               │
        │ (somente ferramentas registradas,            auto ────────────► execute_auto
        │  via ToolExecutor)                           requires_approval ► queue_approvals
        ▼                                              forbidden ────────► blocked
   ToolExecutor = KillSwitch.check → OPA (AgentPolicyClient) → identidade (Keycloak) → handler
   ▼
record: agent_run (prompt/modelo/versões, input_ref=hash, contexto minimizado, ferramentas,
        saída validada, ações e decisões) + agent_approval  — sem PII
```

Pacote `sus_nexus_ai/`:

| módulo | responsabilidade |
|---|---|
| `config.py` | `Settings` (prefixo `AI_`) |
| `security/` | `AgentPolicyClient` (OPA, cache curto) + `LocalPolicyEvaluator` (espelho em memória), `KillSwitch`, `KeycloakIdentityProvider` (client-credentials + token exchange "em nome de") |
| `tools/` | `ToolRegistry`/`ToolSpec` (schema, risco, classe, escopo), `ToolExecutor` (único caminho até o core), `core_client.py` (API pública do core, tipada pelo contrato; `InMemoryCoreClient` para dev/test) |
| `privacy/minimizer.py` | pseudonimização (`[PESSOA_n]`), remoção de CPF/CNS/telefone/e-mail/endereço, mapa reversível só em memória do run |
| `llm/` | `LLMClient` → `LiteLLMClient` / `FakeLLMClient`; `complete_structured` (JSON → Pydantic, até 2 retries de correção) |
| `agents/` | `AgentDefinition`, `AgentRunner` (LangGraph) e os agentes |
| `prompts/<agent>/<versão>.md` | prompts versionados |
| `persistence/` | SQLAlchemy 2: `agent_run`, `agent_approval`, `agent_event_inbox` (SQLite em testes, PostgreSQL em prod) |
| `api/` | FastAPI + auth JWT (JWKS Keycloak) ou `mock` |
| `evaluation/` + `evals/<agent>/cases.jsonl` | avaliação por agente (AIA-008) |
| `consumers/kafka.py` | consumidor dos eventos de regulação, exame, MPI e alta, idempotente por `event_id` |
| `export_openapi.py` | exporta o OpenAPI do ai-service para `contracts/openapi/ai-service.yaml` |

## Ferramentas (contrato real do core)

Toda chamada ao core leva `Authorization: Bearer <token do agente>`, `X-Tenant-Id`,
`X-Purpose-Of-Use` (finalidade por ferramenta) e `X-Correlation-Id`; escritas levam `Idempotency-Key`.

| ferramenta | classe | risco | endpoint do core | purpose |
|---|---|---|---|---|
| `core.get_citizen_summary` | auto | low | `GET /api/v1/citizens/{id}/summary` | parâmetro |
| `core.get_regulation_request` | auto | low | `GET /api/v1/regulation/requests/{id}` (`RegulationRequest`: status, priority, `justification_present`, `attached_documents_count`, `issues[]`, `requesting_cnes`, `specialty`, `requested_service_code`, `sla_due_at`, `waiting_days`) | `regulation` |
| `core.get_exam_order` | auto | low | `GET /api/v1/exams/orders/{id}` (`ExamOrder` com `issues[]` e `results[]` sem valores) | `care_coordination` |
| `core.get_merge_case` / `core.list_merge_case` | auto | low | `GET /api/v1/mpi/cases[/{id}]` (`MergeCase` com `evidence[]`/`conflicts[]`) | `identity_management` |
| `core.create_task` | auto | low | `POST /api/v1/tasks` (`TaskCreate`, `origin={kind:"agent",id,version}` sempre preenchido pelo handler) | `care_coordination` |
| `core.create_pending_issue` | **requires_approval** | medium | `POST /api/v1/regulation/requests/{id}/issues` — `{kind, description, origin:{kind:"agent",id,version}}` | `regulation` |
| `communication.request_message` | requires_approval | medium | **stub** — o core não tem módulo de comunicação; `HttpCoreClient` responde 501 | — |
| `regulation.change_priority`, `regulation.decide`, `production.transmit`, `mpi.merge` | forbidden | high | — | — |

`POST …/issues` exige o papel `agente_ia` e o core cria a pendência **já aberta**; por isso o ai-service
só o chama depois de aprovação humana. Os kinds aceitos são `missing_document`, `missing_field`,
`clinical_justification`, `duplicate`, `other`.

## Classes de ação (AIA-004)

| classe | comportamento | exemplos |
|---|---|---|
| `auto` | executa na própria execução, após OPA + kill switch | leituras, `core.create_task` |
| `requires_approval` | enfileira `agent_approval`; executa só em `POST /runs/{run}/actions/{action}/approve` com justificativa e identidade do aprovador (token) | `core.create_pending_issue`, `communication.request_message` |
| `forbidden` | nunca executa — negado pelo OPA **e** pelo executor (defesa em profundidade), registrado como `blocked`/`denied` | `regulation.change_priority`, `regulation.decide`, `production.transmit`, `mpi.merge` |

A classe do catálogo é a *solicitada*; o OPA pode elevar (`auto` → `requires_approval`) mas nunca
rebaixar. Contrato OPA: `POST {AI_OPA_URL}/v1/data/sus/agents/decision` com
`{"input": {"agent": {"id","version","tools_granted"}, "tool", "action_class_requested", "tenant", "kill_switch"}}`
→ `{"result": {"allow","action_class","requires_approval","reasons"}}`. `security/policy.py::evaluate_locally`
é a referência para o Rego em `policies/`; as ferramentas acima constam de `policies/data/agent_tools.json`
(`tests/test_registry.py` confere). OPA indisponível ⇒ negação (fail-closed).

## Agentes

| agente (versão) | gatilho | entrada | saída | regra versionada | ações |
|---|---|---|---|---|---|
| `regulation_completeness` (2.0.0, prompt v2) | `sus.regulation.request.created\|updated` | `{request_id, citizen_id?}` | `missing_items[] {kind, description}`, `summary`, `duplicate_suspected`, `confidence` | `completeness_rules_v2` | um `core.create_pending_issue` (requires_approval) por `kind` faltante → ao aprovar, `POST …/issues` |
| `exam_critical_result` (1.0.0) | `sus.exam.result.critical_flagged` | `{order_id, result_id?, requesting_cnes?}` | `urgency` (`urgent`/`tracked`/`none`), `rationale`, `create_task` | `exam_critical_rule_v1` | `core.create_task` (auto) `exam_result_followup`, prioridade `urgent`, para a unidade solicitante (`health_unit`) → equipe (`team`) → fila; **só se** nenhum `results[].followup_task_id` existir; título/descrição sem conteúdo clínico (EXA-008) |
| `mpi_duplicate_suggestion` (2.0.0) | `sus.identity.merge.case_opened` | `{case_id}` | `verdict`, `justification`, `confidence`, `key_evidence` | `duplicate_heuristics_v1` | nenhuma (somente sugestão) |
| `post_discharge_followup` (1.0.0) | `sus.hospital.discharge.completed` | `{event, reference_team}` | `risk_level`, `risk_score`, `rationale`, `followup_due_days`, `create_task` | `post_discharge_risk_v1` | `core.create_task` (auto) |

### `regulation_completeness` v2 — regra determinística pré-LLM

A regra decide **o que** falta; o LLM só redige a descrição de cada item e o resumo (o prompt v2
proíbe acrescentar kinds). `plan_actions` usa a regra, não a saída do LLM, para gerar as ações.

* `justification_present == false` → `clinical_justification`;
* `attached_documents_count == 0` e `kind ∈ {exam, procedure, surgery, admission}` → `missing_document`
  (consulta não exige anexo);
* `requesting_cnes` ausente, ou `specialty` ausente em `consultation` → `missing_field`;
* pendência do mesmo `kind` já **aberta** em `issues[]` → `already_open`, não duplica
  (pendências `resolved` não bloqueiam);
* status fora de `{requested, pending_documents, returned, under_review}` → nada a fazer;
* `citizen_summary.open_regulation_requests >= 2` → `duplicate_suspected` (sinal, sem ação).

## Como adicionar

**Ferramenta** (`tools/core_tools.py`): crie `input_model`/`output_model` Pydantic, um handler
`async def h(ctx: ToolContext, args) -> BaseModel` que usa **somente** `ctx.core` (API pública,
passando `correlation_id=ctx.correlation_id`) e registre um
`ToolSpec(name, description, risk, action_class, scope, handler, kind, stub)`. Acrescente o método
correspondente em `CoreClient`/`HttpCoreClient`/`InMemoryCoreClient` (mesmo *shape* do contrato),
a entrada em `policies/data/agent_tools.json` e atualize `tests/test_registry.py`.

**Agente** (`agents/<id>.py`): defina `input_model`, `output_model` (estrito), `build_context(tools, inp)`
(só ferramentas), `plan_actions(output, minimized_context, inp) -> list[PlannedAction]`, um
`fake_responder` determinístico (evals/testes) e `definition()`. Crie `prompts/<id>/v1.md`,
`evals/<id>/cases.jsonl` (≥ 8 casos sintéticos) e um comparador em `evaluation/runner.py`.
Registre o módulo em `agents/catalog.py` e o mapeamento de evento em `consumers/kafka.py`.
Versione: `version`, `prompt_version`, `rule_versions`.

## Kill switch (AIA-009)

União de três fontes, verificada antes de cada execução, de cada chamada de ferramenta e **na
aprovação** (uma ação aprovada com a ferramenta desligada fica `denied`):

1. arquivo JSON `AI_KILL_SWITCH_FILE` (relido quando o mtime muda; inválido ⇒ bloqueio global);
2. variável `AI_KILL_SWITCH='{"global":false,"agents":[],"tools":[],"tenants":[]}'`;
3. `POST /admin/kill-switch` (papéis `admin`/`dpo`; `GET` mostra estado efetivo e administrativo).

O estado também é enviado ao OPA em toda decisão.

## API

| rota | papel | descrição |
|---|---|---|
| `POST /agents/{agent_id}/run` | autenticado | `{tenant, trigger{kind,ref}, input}` → `AgentRunRecord` |
| `GET /runs/{run_id}`, `GET /runs?agent_id&status` | autenticado | consulta |
| `POST /runs/{run_id}/actions/{action_id}/approve\|reject` | `agent_approver`/`admin` | `{justification}` (≥ 10 caracteres); aprovador = `sub` do token; aprovar executa a ferramenta com a identidade do agente e registra `approved_by` |
| `GET /approvals?status` | autenticado | fila de aprovações |
| `GET/POST /admin/kill-switch` | `admin`/`dpo` | kill switch |
| `GET /agents`, `GET /tools` | autenticado | catálogo (`AgentDescriptor`, `ToolDescriptor`) |
| `GET /health`, `GET /metrics` | público | saúde; Prometheus |

Erros em `application/problem+json` (RFC 9457). Auth: `AI_AUTH_MODE=jwt` valida Bearer contra o JWKS
do Keycloak (`aud=AI_KEYCLOAK_AUDIENCE`, papéis de `realm_access`/`resource_access`, tenant de
`municipality_id`); `AI_AUTH_MODE=mock` usa `X-Mock-Subject`, `X-Mock-Roles`, `X-Mock-Tenant`.

### Contrato OpenAPI do ai-service

```bash
.venv/bin/python -m sus_nexus_ai.export_openapi            # grava contracts/openapi/ai-service.yaml
.venv/bin/python -m sus_nexus_ai.export_openapi --check    # 1 se estiver desatualizado
cd ../contracts && pnpm validate                           # SwaggerParser valida core + ai-service
```

`operationId` = nome da função da rota (`run_agent`, `approve_action`, …) para geração de tipos no web.
`tests/test_openapi_export.py` falha se o arquivo não refletir as rotas atuais.

### Métricas

`agent_run_total{agent_id,status}`, `agent_run_duration_seconds`, `agent_tool_call_total{agent_id,tool,status}`,
`agent_tool_call_denied_total{agent_id,tool,reason}`, **`agent_action_total{agent,class,status}`**
(ações planejadas por classe e status final: `executed`, `pending_approval`, `approved`, `rejected`,
`denied`, `blocked`, `failed`), `agent_human_decision_total{agent_id,decision}`,
`agent_human_approval_rate{agent_id}`, `agent_llm_cost_usd_total{agent_id}`.

## Eventos (Kafka)

| tópico | `event_type` aceito | agente | input |
|---|---|---|---|
| `sus.regulation.request.v1` | `….created`, `….updated` | `regulation_completeness` | `request_id = data.regulation_request_id`, `citizen_id = subject.municipal_citizen_id` |
| `sus.exam.result.v1` | `….critical_flagged` | `exam_critical_result` | `order_id = data.exam_order_id`, `result_id = data.exam_result_id`, `requesting_cnes` |
| `sus.identity.merge.v1` | `….case_opened` | `mpi_duplicate_suggestion` | `case_id = data.case_id` |
| `sus.hospital.discharge.v1` | `….completed` | `post_discharge_followup` | handler anterior mantido (aceita também os nomes do schema `hospital/discharge`) |

Tenant sempre do envelope (`tenant.municipality_id`); idempotência por `event_id` em
`agent_event_inbox`. Os envelopes de teste são validados contra `contracts/events/*.schema.json`
(`tests/test_consumer.py`).

## Evals (AIA-008)

```bash
.venv/bin/python -m sus_nexus_ai.evaluation run all            # ou um agente; --json; --threshold 0.9
```

Cada linha de `evals/<agent>/cases.jsonl` tem `input`, `fixtures` (`regulation_requests`, `exam_orders`,
`merge_cases`, `citizen_summaries`, carregados no `InMemoryCoreClient` com os *shapes* do contrato) e
`expected`. `tests/test_evals.py` falha se a acurácia ficar abaixo do limiar (`DEFAULT_THRESHOLDS`).
Os evals de `regulation_completeness` cobrem "não duplicar pendência existente"; os de
`exam_critical_result` cobrem "core já criou a tarefa" e "sem conteúdo clínico na tarefa".
Com LLM real, passe `llm=LiteLLMClient(...)` a `run_eval` (ou `AI_LLM_PROVIDER=litellm`).

## Desenvolvimento

```bash
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/mypy sus_nexus_ai
.venv/bin/pytest
AI_AUTH_MODE=mock AI_POLICY_MODE=local AI_ENVIRONMENT=test .venv/bin/sus-nexus-ai   # porta 8000
docker build -t sus-nexus/ai-service .   # imagem non-root (uid 10001)
```

Todos os testes rodam sem rede e sem chave de LLM (`FakeLLMClient`, OPA local, Keycloak fake,
SQLite em memória; HTTP mockado com `respx`). `tests/test_core_client.py` e
`tests/test_approval_flow_http.py` validam paths, headers e corpos de `POST /issues` e `POST /tasks`
contra o OpenAPI do core (OpenAPI 3.1 → JSON Schema resolvendo `$ref` locais, via `jsonschema`).

## Variáveis (prefixo `AI_`)

| variável | padrão | descrição |
|---|---|---|
| `ENVIRONMENT` | `dev` | `test` usa `InMemoryCoreClient` |
| `CORE_BASE_URL`, `CORE_TIMEOUT_SECONDS` | `http://core-municipal:8080`, `10` | API pública do core |
| `OPA_URL`, `OPA_DECISION_PATH`, `OPA_CACHE_TTL_SECONDS` | `http://opa:8181`, `/v1/data/sus/agents/decision`, `5` | política |
| `POLICY_MODE` | `opa` | `local` = avaliador em memória |
| `LLM_PROVIDER`, `LLM_MODEL`, `LLM_TEMPERATURE`, `LLM_MAX_TOKENS` | `fake`, `openai/gpt-4o-mini`, `0`, `1500` | LiteLLM |
| `LLM_API_BASE`, `LLM_API_KEY`, `LLM_MOCK_RESPONSE` | — | provedor / resposta mock |
| `LLM_MAX_OUTPUT_RETRIES` | `2` | retries de correção de JSON |
| `LLM_COST_PER_1K_INPUT_USD`, `LLM_COST_PER_1K_OUTPUT_USD` | | `cost_estimate` |
| `KILL_SWITCH_FILE`, `KILL_SWITCH` | — | fontes do kill switch |
| `AUTH_MODE` | `jwt` | `mock` em testes |
| `KEYCLOAK_BASE_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_JWKS_URL`, `KEYCLOAK_AUDIENCE` | | validação de token |
| `IDENTITY_MODE`, `KEYCLOAK_AGENT_CLIENT_ID`, `KEYCLOAK_AGENT_CLIENT_SECRET` | `fake` | identidade do agente |
| `ADMIN_ROLES`, `APPROVER_ROLES` | `["admin","dpo"]`, `["admin","agent_approver"]` | RBAC |
| `TENANT_HMAC_SECRET` | | HMAC de identificadores por tenant |
| `DATABASE_URL` | `sqlite+pysqlite:///:memory:` | `postgresql+psycopg://…` em prod |
| `CHECKPOINTER` | `memory` | `postgres` (extra `postgres-checkpoint`) |
| `KAFKA_ENABLED`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP`, `KAFKA_TOPICS` | `false`, …, `[discharge, regulation.request, exam.result, identity.merge]` | consumidor (extra `kafka`) |
| `LANGFUSE_ENABLED`, `LANGFUSE_*`, `OTEL_ENABLED` | `false` | observabilidade (extra `observability`) |

## Limitações conhecidas

* `communication.request_message` continua **stub**: o contrato do core declara o domínio
  `communication`, mas não publica endpoints; `HttpCoreClient.request_message` responde 501.
* O agente de regulação não vê a justificativa clínica (o core só expõe `justification_present`);
  a qualidade da justificativa continua sendo avaliação humana.
* `exam_critical_result` detecta "tarefa já existe" por `results[].followup_task_id`; se o core
  criar a tarefa por outro caminho sem preencher esse campo, o agente pode propor uma segunda.
* O minimizador cobre chaves conhecidas e padrões regex; nomes que apareçam **só** em texto livre
  (sem nenhuma chave de nome) não são detectados — não envie texto clínico livre ao LLM.
* Langfuse/OpenTelemetry estão declarados como extras e desligados; a instrumentação ainda não
  está ligada ao runner.
* O OpenAPI exportado depende da versão do FastAPI/Pydantic instalada; regenere após atualizar
  dependências (`tests/test_openapi_export.py` avisa).
