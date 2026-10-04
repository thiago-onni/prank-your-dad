# SUS Nexus — ai-service

Serviço de agentes de IA do SUS Nexus (Python 3.11, FastAPI, Pydantic v2, LangGraph, LiteLLM).
Implementa a seção 9 do `docs/sus-nexus/PLANO_IMPLEMENTACAO.md` (AIA-001…010/012):
ferramentas formais, política OPA com identidade própria do agente, kill switch, minimização de
dados, saída estruturada, aprovação humana e registro reprodutível de cada execução.

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
| `tools/` | `ToolRegistry`/`ToolSpec` (schema, risco, classe, escopo), `ToolExecutor` (único caminho até o core), `core_client.py` (API pública do core; `InMemoryCoreClient` para dev/test) |
| `privacy/minimizer.py` | pseudonimização (`[PESSOA_n]`), remoção de CPF/CNS/telefone/e-mail/endereço, mapa reversível só em memória do run |
| `llm/` | `LLMClient` → `LiteLLMClient` / `FakeLLMClient`; `complete_structured` (JSON → Pydantic, até 2 retries de correção) |
| `agents/` | `AgentDefinition`, `AgentRunner` (LangGraph) e os agentes iniciais |
| `prompts/<agent>/<versão>.md` | prompts versionados |
| `persistence/` | SQLAlchemy 2: `agent_run`, `agent_approval`, `agent_event_inbox` (SQLite em testes, PostgreSQL em prod) |
| `api/` | FastAPI + auth JWT (JWKS Keycloak) ou `mock` |
| `evaluation/` + `evals/<agent>/cases.jsonl` | avaliação por agente (AIA-008) |
| `consumers/kafka.py` | consumidor `sus.hospital.discharge.v1` / `sus.regulation.request.v1`, idempotente por `event_id` |

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
é a referência para o Rego em `policies/`. OPA indisponível ⇒ negação (fail-closed).

## Agentes iniciais

| agente | entrada | saída | ações |
|---|---|---|---|
| `regulation_completeness` | `{request_id}` | `missing_fields`, `summary`, `suggested_pending_issue`, `confidence` | `core.create_pending_issue` (requires_approval) |
| `mpi_duplicate_suggestion` | `{case_id}` | `verdict` (`probable_same_person` / `probable_different_person` / `inconclusive`), `justification`, `confidence`, `key_evidence` | nenhuma (somente sugestão) |
| `post_discharge_followup` | `{event, reference_team}` | `risk_level`, `risk_score`, `rationale`, `followup_due_days`, `create_task` | `core.create_task` (auto) — risco pela regra versionada `post_discharge_risk_v1` em código |

## Como adicionar

**Ferramenta** (`tools/core_tools.py`): crie `input_model`/`output_model` Pydantic, um handler
`async def h(ctx: ToolContext, args) -> BaseModel` que usa **somente** `ctx.core` (API pública) e
registre um `ToolSpec(name, description, risk, action_class, scope, handler, kind, stub)`.
Acrescente o método correspondente em `CoreClient`/`HttpCoreClient`/`InMemoryCoreClient` e
atualize `policies/` (tools_granted do agente) e `tests/test_registry.py`.

**Agente** (`agents/<id>.py`): defina `input_model`, `output_model` (estrito), `build_context(tools, inp)`
(só ferramentas), `plan_actions(output, minimized_context, inp) -> list[PlannedAction]`, um
`fake_responder` determinístico (evals/testes) e `definition()`. Crie `prompts/<id>/v1.md`,
`evals/<id>/cases.jsonl` (≥ 8 casos sintéticos) e um comparador em `evaluation/runner.py`.
Registre o módulo em `agents/catalog.py`. Versione: `version`, `prompt_version`, `rule_versions`.

## Kill switch (AIA-009)

União de três fontes, verificada antes de cada execução e de cada chamada de ferramenta:

1. arquivo JSON `AI_KILL_SWITCH_FILE` (relido quando o mtime muda; inválido ⇒ bloqueio global);
2. variável `AI_KILL_SWITCH='{"global":false,"agents":[],"tools":[],"tenants":[]}'`;
3. `POST /admin/kill-switch` (papéis `admin`/`dpo`; `GET` mostra estado efetivo e administrativo).

O estado também é enviado ao OPA em toda decisão.

## API

| rota | papel | descrição |
|---|---|---|
| `POST /agents/{agent_id}/run` | autenticado | `{tenant, trigger{kind,ref}, input}` → `AgentRunRecord` |
| `GET /runs/{run_id}`, `GET /runs?agent_id&status` | autenticado | consulta |
| `POST /runs/{run_id}/actions/{action_id}/approve\|reject` | `agent_approver`/`admin` | `{justification}` (≥ 10 caracteres); aprovador = `sub` do token |
| `GET /approvals?status` | autenticado | fila de aprovações |
| `GET/POST /admin/kill-switch` | `admin`/`dpo` | kill switch |
| `GET /agents`, `GET /tools` | autenticado | catálogo |
| `GET /health`, `GET /metrics` | público | saúde; Prometheus (`agent_tool_call_total`, `agent_tool_call_denied_total`, `agent_human_approval_rate`, `agent_run_total{status}`, …) |

Erros em `application/problem+json` (RFC 9457). Auth: `AI_AUTH_MODE=jwt` valida Bearer contra o JWKS
do Keycloak (`aud=AI_KEYCLOAK_AUDIENCE`, papéis de `realm_access`/`resource_access`, tenant de
`municipality_id`); `AI_AUTH_MODE=mock` usa `X-Mock-Subject`, `X-Mock-Roles`, `X-Mock-Tenant`.

## Evals (AIA-008)

```bash
.venv/bin/python -m sus_nexus_ai.evaluation run all            # ou um agente; --json; --threshold 0.9
```

Cada linha de `evals/<agent>/cases.jsonl` tem `input`, `fixtures` (carregados no `InMemoryCoreClient`)
e `expected`. `tests/test_evals.py` falha se a acurácia ficar abaixo do limiar (`DEFAULT_THRESHOLDS`).
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
SQLite em memória; HTTP mockado com `respx`).

## Variáveis (prefixo `AI_`)

| variável | padrão | descrição |
|---|---|---|
| `ENVIRONMENT` | `dev` | `test` usa `InMemoryCoreClient` |
| `CORE_BASE_URL` | `http://core-municipal:8080` | API pública do core |
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
| `KAFKA_ENABLED`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP`, `KAFKA_TOPICS` | `false` | consumidor (extra `kafka`) |
| `LANGFUSE_ENABLED`, `LANGFUSE_*`, `OTEL_ENABLED` | `false` | observabilidade (extra `observability`) |

## Limitações conhecidas

* `core.get_regulation_request`, `core.create_pending_issue` e `communication.request_message` são
  **stubs**: o contrato OpenAPI do core ainda não expõe regulação/comunicação; os modelos locais
  devem ser alinhados quando o core publicar os endpoints.
* O minimizador cobre chaves conhecidas e padrões regex; nomes que apareçam **só** em texto livre
  (sem nenhuma chave de nome) não são detectados — não envie texto clínico livre ao LLM.
* Langfuse/OpenTelemetry estão declarados como extras e desligados; a instrumentação ainda não
  está ligada ao runner.
