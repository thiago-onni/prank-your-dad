# SUS Nexus — `core-municipal`

Monólito modular do barramento municipal de saúde digital (Fase 1 — fundação + segunda leva:
`integration`, `scheduling`, `tasks`, `journey`, Kafka, Temporal, OPA, idempotência).
Java 21 + Quarkus 3.39.x + PostgreSQL 16 (+ Kafka, Temporal e OPA em prod). Segue `../CONVENTIONS.md`
e o plano em `docs/sus-nexus/PLANO_IMPLEMENTACAO.md` (§5.1–5.8, §8).

## Como rodar

Pré-requisitos: JDK 21, Maven 3.9, PostgreSQL 16 local com extensões `pg_trgm` e `unaccent`
disponíveis (pacote `postgresql-contrib`). Não há dependência de Docker.

```bash
# banco de desenvolvimento (uma vez)
PGPASSWORD=postgres createdb -h localhost -U postgres sus_nexus

# dev mode (perfil %dev: OIDC desligado, autenticação por header, hot reload)
mvn quarkus:dev
```

As migrações Flyway rodam no start com o usuário administrador (`postgres`) e criam o papel
`sus_nexus_app`, usado pela aplicação. Esse papel **não** é superusuário nem dono das tabelas,
portanto as políticas de RLS se aplicam a ele (sem `app.tenant_id` na transação nenhuma linha é
visível — fail-closed).

Em dev/test cada chamada precisa dos headers:

| Header | Exemplo | Função |
|---|---|---|
| `X-Tenant-Id` | `ibge_3143302` | tenant (em prod vem do claim JWT `municipality_id`) |
| `X-Test-User` | `dra.ana` | ator fake (somente perfis dev/test) |
| `X-Test-Roles` | `profissional_aps,gestor` | papéis fake (somente perfis dev/test) |
| `X-Purpose-Of-Use` | `care_coordination` | finalidade LGPD (obrigatória em leituras de cidadão) |
| `X-Correlation-Id` | opcional | propagado em MDC, resposta e eventos |
| `Idempotency-Key` | opcional em POST | resposta memorizada por 72 h (ver "Idempotência") |
| `X-Test-Cnes`, `X-Test-Teams`, `X-Test-Microareas` | `1234567`, `0000123456`, `03` | vínculos fake do ator (em prod: claims `cnes`, `teams`, `microareas`) |

```bash
curl -s localhost:8080/api/v1/citizens \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: ibge_3143302' \
  -H 'X-Test-User: connector-pec' -H 'X-Test-Roles: operador_integracao' \
  -d '{"source":{"system":"ESUS_APS_PEC","connector":"connector-pec","source_record_id":"PEC-1"},
       "identifiers":[{"system":"CNS","value":"898001234565678"}],
       "demographics":{"legal_name":"Maria da Silva","birthdate":"1985-03-10","mother_name":"Ana da Silva"}}'
```

Endpoints auxiliares: `/q/health` (liveness/readiness), `/q/metrics` (Prometheus).

## Perfis e variáveis

| Perfil | OIDC | Auth por header | Tenant por header | OTel | Log |
|---|---|---|---|---|---|
| `%dev` | desligado | sim | sim | desligado | texto |
| `%test` | desligado | sim | sim | desligado | texto |
| `%prod` | Keycloak | não | não | OTLP | JSON |

| Variável | Padrão | Uso |
|---|---|---|
| `SUS_DB_URL` | `jdbc:postgresql://localhost:5432/sus_nexus` | datasource da aplicação |
| `SUS_DB_APP_USER` / `SUS_DB_APP_PASSWORD` | `sus_nexus_app` / `sus_nexus_app` | papel sem bypass de RLS |
| `SUS_DB_ADMIN_USER` / `SUS_DB_ADMIN_PASSWORD` | `postgres` / `postgres` | usuário do Flyway |
| `SUS_IDENTITY_HMAC_KEY` | chave de exemplo | HMAC-SHA256 de CPF/CNS (derivada por tenant) |
| `SUS_IDENTITY_ENC_KEY` | chave de exemplo (base64, 32 bytes) | AES-256-GCM do `value_enc` |
| `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | — | OIDC em prod |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry em prod |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka (prod/dev) |
| `TEMPORAL_TARGET`, `TEMPORAL_NAMESPACE` | `localhost:7233`, `default` | Temporal (só com `sus.temporal.enabled=true`) |
| `OPA_URL` | `http://localhost:8181` | OPA (só com `sus.authz.mode=opa`) |

Parâmetros SUS Nexus (`application.properties`): `sus.authz.mode` (`rbac` | `opa`), `sus.authz.opa-timeout`,
`sus.idempotency.ttl` (PT72H) / `purge-every`, `sus.scheduling.duplicate-window-hours` (72),
`sus.temporal.enabled|target|namespace|task-queue`, `sus.outbox.relay.enabled|every|batch-size`.

Parâmetros do MPI ficam em `sus.mpi.*` (`application.properties`): `threshold.high/low`,
`jaro-winkler.agree/partial`, `blocking.*` e pesos m/u por campo (`weights.<campo>.m|u`).
As chaves de exemplo **não** devem ir para produção; o desenho prevê OpenBao/transit.

## Testes

```bash
PGPASSWORD=postgres createdb -h localhost -U postgres sus_nexus_test   # uma vez
mvn -q verify
```

`mvn verify` executa: Google Java Format (`fmt-maven-plugin`, goal `format`), compilação,
testes unitários (value objects, normalização, Fellegi-Sunter, mascaramento de log, ULID, cliente OPA
com WireMock), ArchUnit (modularidade) e `@QuarkusTest` + REST-assured contra `sus_nexus_test`
(Flyway `clean-at-start` no perfil test). Os contratos de evento são copiados de
`../contracts/events` para `target/test-classes/contracts/events` pelo `maven-resources-plugin`
e validados nos testes de outbox (json-schema-validator, draft 2020-12 com asserção de formatos).

Sem Docker: no perfil `test` o Kafka é substituído pelo conector **in-memory** do SmallRye
(`%test.mp.messaging.*.connector=smallrye-in-memory`; o helper `support/Bus` faz o papel do broker:
`OutboxRelay.relayOnce()` → canais de saída → canais de entrada que assinam o mesmo tópico), o Temporal
roda **in-process** (`TestWorkflowEnvironment`, time-skipping, cliente injetado no
`TemporalClientProvider`) e o OPA é um **WireMock** (`OpaAuthorizationPolicyTest` unitário e
`OpaProfileTest` com `sus.authz.mode=opa`).

## Estrutura

```text
br.gov.sus.nexus.core
├── platform/            cross-cutting (sem dependência de módulos de domínio)
│   ├── tenant/          TenantContext, TenantFilter (claim/header), @TenantTransactional
│   │                    (JTA + set_config('app.tenant_id', ?, true)), TenantTransactions
│   ├── correlation/     CorrelationId + filtro (MDC, header de resposta)
│   ├── security/        CurrentActor (papéis, cnes/teams/microareas, client_type), Purpose, Roles,
│   │                    AuthorizationPolicy (Decision + Obligations), RoleBasedAuthorizationPolicy
│   │                    (padrão), OpaAuthorizationPolicy (sus.authz.mode=opa), HeaderAuthenticationMechanism
│   ├── events/          EventEnvelope (envelope.schema.json), DomainEvent, EventPublisher (outbox),
│   │                    EventInbox (idempotência de consumidores), InboundEventProcessor (suporte
│   │                    aos consumidores Kafka), OutboxRelay (relay de desenvolvimento)
│   ├── idempotency/     IdempotencyFilter (Idempotency-Key) + IdempotencyStore (expurgo @Scheduled)
│   ├── ingestion/       BatchSource, UpsertResult (lotes dos conectores)
│   ├── errors/          ProblemException + mappers RFC 9457
│   ├── logging/         PiiMasker, PiiLogFilter (quarkus.log.console.filter=pii-mask)
│   ├── pagination/      Cursor opaco, Page { items, next_cursor }
│   └── ids/             Ulid com prefixos
├── sharedkernel/        Cns, Cpf, Cnes, Cbo, Competence, IdentifierHash (HMAC por tenant), Masks
├── audit/               audit_log encadeado por hash (append-only), access_log, @AuditedAccess,
│                        GET /api/v1/audit/access
├── reference/           organization, health_unit, professional, professional_role, care_team,
│                        territory, microarea; upsert por (tenant, cnes); GET/PUT /api/v1/reference/health-units,
│                        POST /api/v1/reference/health-units/upsert (lote, UpsertResult)
├── terminology/         terminology.code (global), busca trigram+unaccent, TerminologyService.isValid;
│                        GET /api/v1/terminology/{system}/codes, POST .../codes/upsert (lote por competência)
├── identity/            MPI: citizen + identifiers (hash/enc/masked) + histórico bitemporal +
│                        endereço/contato/source_link + merge case/merge + match candidate/evidence +
│                        golden record com proveniência; IdentityResolutionService; /api/v1/citizens, /api/v1/mpi;
│                        consumidor de ingestão `ingest-pec-in` (mesma porta do POST /citizens)
├── integration/         registry de conectores (heartbeat + eventos de status), ledger espelho SEM payload,
│                        erros, DLQ, reconciliação, reprocessamento (comando sus.integration.reprocess.requested);
│                        /api/v1/integration/*; consumidor `integration-status-in`
├── scheduling/          appointment + status_history + source_link + duplicate (AGE-004, janela configurável);
│                        resolução de cidadão via identity.api; no-show → tarefa no_show_recovery (AGE-006);
│                        /api/v1/appointments; consumidor `ingest-agenda-in`
├── tasks/               care_task + task_history + sla_policy (seed global; override por tenant); máquina de
│                        estados; TaskCommands/TaskQueries (API pública); workflows Temporal TaskSlaWorkflow e
│                        MpiReviewWorkflow; consumidores `tasks-task-in` (starter) e `tasks-merge-in` (mpi_review)
└── journey/             read model timeline_event (projeções de identity/schedule/task; merge reatribui, unmerge
                         reverte); GET /api/v1/citizens/{id}/timeline (keyset occurred_at+id, filtragem por
                         sensibilidade via AuthorizationPolicy, redação por obrigações) e /summary (JOR-008)
```

Cada módulo de domínio tem `api/` (contratos públicos), `domain/`, `application/` e
`infrastructure/`. O `ArchitectureTest` garante que um módulo só importa `..<outro>.api..` de
outros módulos e que `platform`/`sharedkernel` não conhecem módulos.

Um schema PostgreSQL por módulo (`platform`, `audit`, `reference`, `terminology`, `identity`,
`integration`, `scheduling`, `tasks`, `journey`), migrações em
`src/main/resources/db/migration/V0NN__<módulo>.sql` (V001–V012). Todas as tabelas com
`tenant_id` têm RLS (`platform.current_tenant()` ↔ `app.tenant_id`); terminologia é global e
`tasks.sla_policy` expõe linhas globais (`tenant_id IS NULL`) mais as do tenant.

## Kafka — canais e tópicos

Produção publica via **Debezium Outbox Event Router** lendo `platform.event_outbox` (CDC); o
`OutboxRelay` (`sus.outbox.relay.enabled=true`, ligado no perfil `%dev`) existe apenas para ambientes
sem Debezium: lê o outbox não publicado, emite no canal do `aggregate_type` e marca `published_at`.
Consumidores são `@Incoming` + `@Blocking`, idempotentes via `platform.event_inbox` (`InboundEventProcessor`:
tenant do envelope, correlation id, transação nova com o inbox na mesma transação do handler).

| Canal (SmallRye) | Tópico | Grupo | Função |
|---|---|---|---|
| `ingest-pec-in` | `sus.ingest.pec.v1` | `core-ingest-pec` | `data` = `CitizenRegistration` → `CitizenService.register` |
| `ingest-agenda-in` | `sus.ingest.agenda.v1` | `core-ingest-agenda` | `data` = `AppointmentRegistration` → `AppointmentService.register` |
| `integration-status-in` | `sus.integration.status.v1` | `core-integration-status` | registry de conectores (health, métricas, gaps) |
| `tasks-task-in` | `sus.task.v1` | `core-tasks-sla` | `created` inicia `TaskSlaWorkflow`; `completed/cancelled` sinalizam |
| `tasks-merge-in` | `sus.identity.merge.v1` | `core-tasks-merge` | `case_opened` → tarefa `mpi_review` + `MpiReviewWorkflow`; decisão conclui |
| `journey-identity-in`, `journey-merge-in`, `journey-appointment-in`, `journey-task-in` | `sus.identity.citizen.v1`, `sus.identity.merge.v1`, `sus.schedule.appointment.v1`, `sus.task.v1` | `core-journey` | projeções da timeline |
| `citizen-out`, `merge-out`, `appointment-out`, `task-out`, `integration-command-out` | tópicos correspondentes | — | saída do `OutboxRelay` (dev) |

Eventos produzidos: `sus.identity.citizen.*`, `sus.identity.merge.*`, `sus.schedule.appointment.*`
(`created|confirmed|cancelled|rescheduled|attended|no_show|duplicate_detected`), `sus.task.*`
(`created|assigned|completed|escalated|sla_breached|cancelled`) e `sus.integration.reprocess.requested`
(tópico `sus.integration.command.v1`), todos validados nos testes contra `contracts/events/**`.

## Temporal

`temporal-sdk` no runtime; conexão real só com `sus.temporal.enabled=true` (`TemporalWorkers` registra
os workers na fila `sus.temporal.task-queue` no `StartupEvent`). Workflows determinísticos com
`Workflow.getVersion`; toda I/O em activities idempotentes (`TenantTransactions.runAs` + serviços):

- `TaskSlaWorkflow` (`task-sla:<task_id>`): timer até `due_at`; se a tarefa segue aberta → `breachSla`
  (evento `sla_breached` + escalonamento para `escalate_to` da `sla_policy`); sinais `completed`/`cancelled`.
- `MpiReviewWorkflow` (`mpi-review:<case_id>`): garante a tarefa `mpi_review` (fila `cadastro_mestre`),
  aguarda `decided` até o SLA de revisão, escalona e conclui a tarefa com a decisão.

Os starters ficam nos **consumidores** (`TaskEventsConsumer`, `IdentityMergeConsumer`), não nos serviços,
com `WorkflowIdReusePolicy=REJECT_DUPLICATE` — replay de eventos não duplica workflows.

## Autorização (OPA) e obrigações

`AuthorizationPolicy.Decision` carrega `reasons`, `policy_version` e `Obligations`
(`mask_identifiers`, `redact_fields`, `log_access`, `require_justification`, `alert_dpo`).
`RoleBasedAuthorizationPolicy` (padrão, dev/test) implementa a matriz local, incluindo a regra de
timeline (ACS não vê `restricted`/`highly_restricted` de `aps`/`hospital`/`exam`). Com
`sus.authz.mode=opa`, `OpaAuthorizationPolicy` monta o input de `policies/README.md` (`subject` com
roles/tenant/cnes/teams/microareas/client_type; `action`; `resource` com type/tenant/domain/sensitivity/
citizen_*; `context` com purpose/break_glass/justification/channel) e chama
`POST {sus.authz.opa-url}/v1/data/sus/authz/decision` — **fail-closed** (OPA fora, timeout, resposta
inválida ⇒ deny). Obrigações: `redact_fields` é aplicada na timeline (`summary`, `detail_ref`,
`professional_ref`, `health_unit_name`; campos clínicos removem `detail_ref`); `mask_identifiers` é
satisfeita por construção (listagens só carregam `value_masked`; reveal é ação própria);
`log_access` é cumprida pelo `@AuditedAccess`.

## Idempotência de API

`IdempotencyFilter` (POST com `Idempotency-Key`): hash SHA-256 de método + caminho + corpo; mesma
chave + mesmo hash devolve a resposta armazenada (`Idempotent-Replayed: true`); mesma chave + hash
diferente → 422 (`idempotency-key-reused`). Armazenamento em `platform.idempotency_key` (RLS por tenant,
transação própria); expurgo de 72 h por `@Scheduled` via função `SECURITY DEFINER`
`platform.purge_idempotency_keys`.

## Pipeline de resolução de identidade (resumo)

1. Normalização (maiúsculas, sem acento, partículas removidas, nome social separado) e validação
   (DV de CNS/CPF, data plausível).
2. Determinístico: CNS → CPF → `source_link` → nome + data de nascimento + nome da mãe.
   Conflito (mesmo CNS/CPF com data de nascimento diferente, CNS/CPF divergentes, identificador já
   de outro cidadão) **nunca vincula**: cria registro `divergent` e abre `citizen_merge_case` com
   `conflicts`.
3. Probabilístico (Fellegi-Sunter com Jaro-Winkler em nome/mãe, data, sexo, telefone):
   `≥ high` → `probable`, `low..high` → `pending` — ambos apenas fila de revisão; `< low` → novo.
4. Persistência de vínculo, evidências (`citizen_match_evidence`) e proveniência; evento
   `sus.identity.citizen.{created|linked}` / `sus.identity.merge.*` via `platform.event_outbox`.

Merge marca `status=merged` + `merged_into_id` (nada é apagado) e guarda snapshot para unmerge.
