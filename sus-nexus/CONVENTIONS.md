# SUS Nexus — Convenções de engenharia (fonte única)

Todo componente deste monorepo segue estas convenções. Elas derivam de `docs/sus-nexus/PLANO_IMPLEMENTACAO.md` (ADR-001…017).

## Layout do monorepo

```text
sus-nexus/
├── contracts/        JSON Schemas de eventos, OpenAPI do core, perfis FHIR, tópicos Kafka
├── core-municipal/   Monólito modular Java 21 + Quarkus 3.x (Maven multi-módulo)
├── fhir-gateway/     FHIR R4 Gateway próprio (Quarkus) — sem servidor HAPI
├── connectors/       Connector SDK + conectores (Quarkus + Camel)
├── web/              Monorepo Turborepo + pnpm: design-system, api-client, apps
├── policies/         Políticas OPA (Rego) + testes
├── ai-service/       Serviço de agentes (Python 3.11+, FastAPI, LangGraph, Pydantic v2)
├── platform/         docker-compose local, Helm charts, Argo CD, Terraform, CI
└── docs/             ADRs e runbooks
```

## Identidade e tenancy

- IDs internos: **ULID** prefixado por tipo. Prefixos: `cit_` cidadão, `cid_` identificador de cidadão, `org_`, `hu_` unidade, `loc_`, `prof_`, `team_`, `apt_` agendamento, `enc_` atendimento, `reg_` regulação, `exo_` pedido de exame, `hep_` episódio hospitalar, `cp_` plano de cuidado, `task_`, `prod_` produção, `msg_` integration_message, `evt_` evento, `case_` merge case, `rule_`, `agent_`, `run_` execução de agente.
- `tenant_id` = `ibge_<código IBGE 7 dígitos>` (ex.: `ibge_3143302`). Toda tabela de domínio tem `tenant_id text NOT NULL` com **RLS** (`current_setting('app.tenant_id', true)`).
- Claim no token Keycloak: `municipality_id`. Header interno entre serviços: `X-Tenant-Id`.

## Tempo

- Banco: `timestamptz`. Fatos: `occurred_at` (quando aconteceu) × `recorded_at` (quando foi registrado no barramento).
- API/eventos: ISO-8601 com offset (`2026-10-03T14:00:00-03:00`).

## Eventos (Kafka)

- Tópicos: `sus.<domínio>.<entidade>.v<N>`; retry `sus.<...>.v<N>.retry.<n>`; DLQ `sus.dlq.v1`. Lista em `contracts/events/topics.yaml`.
- `event_type`: `sus.<domínio>.<entidade>.<ação>` (ex.: `sus.identity.citizen.created`).
- Envelope: `contracts/events/envelope.schema.json` (compatível com CloudEvents 1.0 por mapeamento).
- Chave de partição: `subject.municipal_citizen_id` quando existir; senão o ID da entidade principal.
- Payload mínimo; dado sensível completo somente por `data_ref` (object storage). **Nunca** CPF/CNS em claro no `data`/`subject` dos tópicos de domínio.
- Headers Kafka: `ce_id`, `ce_type`, `ce_source`, `tenant_id`, `correlation_id`, `causation_id`, `schema_version`, `replay`.
- Produtor: sempre via `event_outbox` (Debezium Outbox Event Router). Consumidor: idempotente via `event_inbox(event_id, consumer_group)`.

## APIs REST internas

- Base: `/api/v1/...`. Contrato: `contracts/openapi/core-municipal.yaml` (OpenAPI 3.1, contract-first).
- Erros: RFC 9457 `application/problem+json`. FHIR: `OperationOutcome`.
- Paginação: cursor opaco (`?cursor=&limit=`), resposta `{ "items": [...], "next_cursor": "..." }`.
- Idempotência: header `Idempotency-Key` em POST de sistemas externos.
- Concorrência: campo `version` + `If-Match`.
- Autenticação: Bearer JWT (Keycloak). Em dev, `quarkus.oidc` pode ser desabilitado via perfil `%dev`/`%test`.

## Segurança

- Logs JSON **sem PII**: CPF, CNS, nome completo e payload clínico nunca em log. Use máscaras (`***.***.***-12`).
- Identificadores de alto risco (CPF/CNS): persistir `value_hash` (HMAC-SHA256 com chave por tenant) + `value_masked`; valor em claro criptografado quando política exigir.
- Autorização real sempre no backend via OPA (`policies/`). Frontend apenas esconde ações.
- Toda leitura de dado de cidadão gera `access_log` (quem, o quê, finalidade, correlation_id).

## Java (core, fhir-gateway, connectors)

- Java 21, Quarkus **3.39.x** (BOM `io.quarkus.platform:quarkus-bom`), Maven 3.9, JUnit 5, AssertJ, ArchUnit, Flyway, Hibernate ORM com Panache (repository pattern), Jackson.
- Pacote raiz: `br.gov.sus.nexus.<componente>.<módulo>`. Ex.: `br.gov.sus.nexus.core.identity`.
- Cada módulo do core: `api/` (interfaces e DTOs públicos), `domain/`, `application/`, `infrastructure/` (JPA, REST, Kafka). Outros módulos só importam `..<módulo>.api..` (ArchUnit).
- Um schema PostgreSQL por módulo (`identity`, `reference`, `terminology`, `integration`, `scheduling`, `journey`, `tasks`, `platform`, `audit`). Migrações Flyway em `src/main/resources/db/migration/V<n>__<desc>.sql`.
- Testes de integração usam PostgreSQL local (`jdbc:postgresql://localhost:5432/sus_nexus_test`, usuário `postgres`/`postgres`) — sem Docker no CI deste ambiente. Perfil `%test`.
- Formatação: Google Java Style via `fmt-maven-plugin` (ou Spotless). Build deve passar com `mvn -q verify`.

## TypeScript (web)

- Node 22, pnpm 10, Turborepo, Next.js 15 (App Router), React 19, TypeScript 5 strict, Vitest, Testing Library, ESLint 9, Prettier.
- Pacotes: `@sus-nexus/design-system`, `@sus-nexus/domain-components`, `@sus-nexus/api-client`, `@sus-nexus/auth`.
- Apps: `apps/shell` (único app interno com módulos por rota: `/integracoes`, `/cadastro`, `/cidadaos/[id]/timeline`, `/regulacao`, `/cuidado`, `/producao`, `/agentes`, `/situacao`).
- Acessibilidade WCAG 2.1 AA; pt-BR; CPF/CNS mascarados por padrão.

## Python (ai-service)

- Python 3.11+, FastAPI, Pydantic v2, LangGraph, LiteLLM, httpx, pytest, ruff, mypy. Gerenciado com `pip`/`pyproject.toml`.
- Agentes só chamam ferramentas registradas em `tools/registry.py`; cada ferramenta tem `risk` e `action_class` (`auto` | `requires_approval` | `forbidden`).

## Portas locais (docker-compose / dev)

| Serviço | Porta |
|---|---|
| core-municipal | 8080 |
| fhir-gateway | 8081 |
| connectors (cada) | 8090+ |
| ai-service | 8000 |
| web shell | 3000 |
| PostgreSQL | 5432 |
| Kafka | 9092 |
| Apicurio | 8085 |
| Keycloak | 8180 |
| OPA | 8181 |
| Temporal | 7233 / UI 8233 |
| MinIO | 9000 / console 9001 |
| Redis | 6379 |
| Grafana | 3001 |

## Commits

Conventional Commits em português: `feat(core): ...`, `fix(fhir): ...`, `docs: ...`, `infra: ...`.

## Nota sobre `event_type`

Tópicos com dois segmentos (`sus.task.v1`, `sus.careplan.v1`, `sus.audit.v1`) usam `event_type` com três partes (`sus.task.created`); o schema de dados fica em `contracts/events/<domínio>/<domínio>.v1.schema.json`. Tópicos com três segmentos (`sus.identity.citizen.v1`) usam quatro partes (`sus.identity.citizen.created`).
