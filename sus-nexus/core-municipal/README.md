# SUS Nexus — `core-municipal`

Monólito modular do barramento municipal de saúde digital (Fase 1 — fundação + segunda leva:
`integration`, `scheduling`, `tasks`, `journey`, Kafka, Temporal, OPA, idempotência; Fase 2 —
`regulation` e `exams` com workflows `RegulationSlaWorkflow` e `ExamFollowUpWorkflow`).
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
`sus.temporal.enabled|target|namespace|task-queue`, `sus.outbox.relay.enabled|every|batch-size`,
`sus.regulation.documents-required-kinds` (tipos em que `attached_documents_count=0` gera pendência;
padrão `procedure,surgery,admission`), `sus.exams.not-scheduled-days` (15), `sus.exams.result-pending-days`
(7), `sus.exams.followup-days` (10), `sus.exams.document-base-url`, `sus.exams.document-signing-key`
(HMAC-SHA256 da URL assinada do laudo; **trocar em produção**), `sus.exams.document-link-ttl` (PT5M).
Os prazos de SLA de decisão regulatória ficam em `regulation.regulation_sla_policy` (seed global:
elective 90 d, priority 30 d, urgent 7 d, emergency 1 d; sobrescrita por tenant via linha com `tenant_id`).

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
`OpaProfileTest` com `sus.authz.mode=opa`). Os testes de Fase 2 (`RegulationFlowTest`,
`RegulationSlaWorkflowTest`, `ExamFlowTest`, `ExamFollowUpWorkflowTest`) cobrem o ciclo completo, as
pendências, os eventos contra os schemas de `regulation/` e `exam/`, a timeline (ACS não vê laudos) e o
isolamento de tenant.

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
│   ├── temporal/        TemporalClientProvider, TemporalWorkers (descobre os WorkflowRegistrar dos módulos)
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
├── regulation/          fila regulatória espelhada do sistema oficial (regulation_request + status_history +
│                        decision [somente registro] + issue [REG-005] + source_link + provider_capacity [REG-006]
│                        + regulation_sla_policy [REG-010]); /api/v1/regulation/*; consumidor `ingest-regulation-in`;
│                        RegulationSlaWorkflow; o barramento NUNCA decide nem muda prioridade (REG-009)
├── exams/               pedidos de exame (exam_order + status_history + exam_result [só metadados + document_ref]
│                        + source_link), pendências EXA-004/005/009, tempos de ciclo EXA-010, URL assinada do laudo
│                        com access_log; /api/v1/exams/orders/*; consumidor `ingest-exam-in`; ExamFollowUpWorkflow
└── journey/             read model timeline_event (projeções de identity/schedule/task/regulation/exam; merge
                         reatribui, unmerge reverte); GET /api/v1/citizens/{id}/timeline (keyset occurred_at+id,
                         filtragem por sensibilidade via AuthorizationPolicy, redação por obrigações) e /summary
                         (JOR-008: open_tasks, open_regulation_requests, pending_exams, next_appointment_at)
```

Cada módulo de domínio tem `api/` (contratos públicos), `domain/`, `application/` e
`infrastructure/`. O `ArchitectureTest` garante que um módulo só importa `..<outro>.api..` de
outros módulos e que `platform`/`sharedkernel` não conhecem módulos.

Um schema PostgreSQL por módulo (`platform`, `audit`, `reference`, `terminology`, `identity`,
`integration`, `scheduling`, `tasks`, `journey`, `regulation`, `exams`), migrações em
`src/main/resources/db/migration/V0NN__<módulo>.sql` (V001–V014). Todas as tabelas com
`tenant_id` têm RLS (`platform.current_tenant()` ↔ `app.tenant_id`); terminologia é global e
`tasks.sla_policy`/`regulation.regulation_sla_policy` expõem linhas globais (`tenant_id IS NULL`)
mais as do tenant.

## Regulação (`/api/v1/regulation`) e exames (`/api/v1/exams`)

| Operação | Papéis | Observações |
|---|---|---|
| `POST /regulation/requests` | operador_integracao, regulador | upsert por `(tenant, source.system, source_record_id)`; cidadão via identity.api; `sla_due_at = requested_at + política(prioridade)`; `cid_code` **não** é persistido |
| `POST /regulation/requests/{id}/status` e `.../by-source/{system}/{sourceRecordId}/status` | operador_integracao, regulador | histórico + `regulation_decision` (authorized/denied/returned); `appointment_source_record_id` vincula o agendamento (scheduling.api); `returned` abre pendência; `no_show` → tarefa `no_show_recovery`; agente de IA → 403 (REG-009) |
| `POST /regulation/requests/{id}/issues` | regulador, agente_ia | origem `agent` exige papel `agente_ia` (aprovação humana ocorre no ai-service); registrado em `audit_log` |
| `GET /regulation/requests` | regulador, gestor, agendador, profissional_aps, operador_integracao, agente_ia | filtros do contrato; `issue=incomplete|returned|expired|duplicate|no_capacity|sla_breached`; `sort=waiting_time_desc` (padrão: `requested_at` asc), `priority_desc`, `created_at_asc`; cursor por deslocamento; `@AuditedAccess` |
| `GET/POST /regulation/capacity` | leitura ampla / operador_integracao, regulador, gestor | upsert por `(tenant, provider_cnes, service_code, competence)`; itens inválidos contam como `rejected`; reavalia `no_capacity` dos pedidos abertos do serviço |
| `GET /regulation/queues/summary?group_by=` | gestor, regulador | agregação SQL: `open_requests`, `by_priority`, `avg/p90_waiting_days` (`percentile_cont`), `sla_breached`, `with_issues`, `scheduled_30d`, `no_show_30d`, `capacity_available` (competência ≥ atual; só para `service_code`/`provider_cnes`) |
| `POST /exams/orders` | operador_integracao, profissional_aps | upsert por vínculo de origem; `regulation_source_record_id`/`appointment_source_record_id` vinculam regulação e agenda |
| `POST /exams/orders/{id}/status` | operador_integracao, profissional_aps | `scheduled` grava `scheduled_at`; `performed`/`collected` gravam `performed_at` |
| `POST /exams/orders/{id}/results` e `.../by-source/{system}/{sourceRecordId}/results` | operador_integracao | só metadados; observações codificadas (texto livre descartado); marca `reported`; `sus.exam.result.available` com `data_ref = document_ref`; `critical=true` → `critical_flagged` + tarefa `exam_result_followup` urgente ao solicitante **sem conteúdo** (EXA-008) |
| `GET /exams/orders[/{id}]` | leitura ampla | `issues` derivadas (not_scheduled, result_pending, no_result_followup, inconclusive, critical, integration_failure) e `cycle_times` em horas (EXA-010); `@AuditedAccess` |
| `GET /exams/orders/{id}/results/{rid}/document` | profissional_aps, profissional_hospitalar, regulador | URL assinada (HMAC-SHA256, 5 min) para `sus.exams.document-base-url` + `document_ref`; `access_log` (`exam_result`/`document_link`) com finalidade; 404 sem documento |

Pendências de regulação (REG-005, origem `rule`, reavaliadas a cada escrita): `clinical_justification`
(`justification_present=false`), `missing_document` (`attached_documents_count=0` nos tipos configurados),
`duplicate` (mesmo cidadão + serviço com outro pedido aberto), `no_capacity` (serviço com oferta cadastrada e
sem `available > 0` em competência ≥ à do pedido), `sla_breached` (prazo vencido sem decisão). Decisão
registrada resolve as pendências de regra; encerramento resolve todas.

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
| `ingest-regulation-in` | `sus.ingest.regulation.v1` | `core-ingest-regulation` | `data` = `RegulationRequestRegistration` (tem `kind`) ou `RegulationStatusChange` (pedido por `source_record_id`) |
| `ingest-exam-in` | `sus.ingest.exam.v1` | `core-ingest-exam` | `data` = `ExamOrderRegistration` (`exam_code`), `ExamResultRegistration` (`reported_at`) ou `ExamStatusChange` |
| `regulation-request-in`, `regulation-status-in` | `sus.regulation.request.v1`, `sus.regulation.status.v1` | `core-regulation-sla` | `created` inicia `RegulationSlaWorkflow`; `status.changed` sinaliza |
| `exams-order-in`, `exams-result-in`, `exams-task-in`, `exams-appointment-in` | `sus.exam.order.v1`, `sus.exam.result.v1`, `sus.task.v1`, `sus.schedule.appointment.v1` | `core-exams-followup` | `created` inicia `ExamFollowUpWorkflow`; status/laudo/tarefa concluída/falta sinalizam |
| `journey-identity-in`, `journey-merge-in`, `journey-appointment-in`, `journey-task-in`, `journey-regulation-request-in`, `journey-regulation-status-in`, `journey-exam-order-in`, `journey-exam-result-in` | `sus.identity.citizen.v1`, `sus.identity.merge.v1`, `sus.schedule.appointment.v1`, `sus.task.v1`, `sus.regulation.request.v1`, `sus.regulation.status.v1`, `sus.exam.order.v1`, `sus.exam.result.v1` | `core-journey` | projeções da timeline (regulação e laudos: `restricted`) |
| `citizen-out`, `merge-out`, `appointment-out`, `task-out`, `integration-command-out`, `regulation-request-out`, `regulation-status-out`, `exam-order-out`, `exam-result-out` | tópicos correspondentes | — | saída do `OutboxRelay` (dev); `aggregate_type` → canal |

Eventos produzidos: `sus.identity.citizen.*`, `sus.identity.merge.*`, `sus.schedule.appointment.*`
(`created|confirmed|cancelled|rescheduled|attended|no_show|duplicate_detected`), `sus.task.*`
(`created|assigned|completed|escalated|sla_breached|cancelled`), `sus.integration.reprocess.requested`
(tópico `sus.integration.command.v1`), `sus.regulation.request.{created|updated|returned|cancelled}`,
`sus.regulation.status.changed` (`actor_kind` nunca `agent`), `sus.exam.order.{created|status_changed}` e
`sus.exam.result.{available|critical_flagged}` (`data_ref` = referência do laudo; nunca valores), todos
validados nos testes contra `contracts/events/**`.

## Temporal

`temporal-sdk` no runtime; conexão real só com `sus.temporal.enabled=true` (`platform.temporal.TemporalWorkers`
registra na fila `sus.temporal.task-queue`, no `StartupEvent`, os `WorkflowRegistrar` de cada módulo — as
activities de cada módulo usam `namePrefix` para não colidir). Workflows determinísticos com
`Workflow.getVersion`; toda I/O em activities idempotentes (`TenantTransactions.runAs` + serviços):

- `TaskSlaWorkflow` (`task-sla:<task_id>`): timer até `due_at`; se a tarefa segue aberta → `breachSla`
  (evento `sla_breached` + escalonamento para `escalate_to` da `sla_policy`); sinais `completed`/`cancelled`.
- `MpiReviewWorkflow` (`mpi-review:<case_id>`): garante a tarefa `mpi_review` (fila `cadastro_mestre`),
  aguarda `decided` até o SLA de revisão, escalona e conclui a tarefa com a decisão.
- `RegulationSlaWorkflow` (`regulation-sla:<request_id>`, REG-010): timer em 50 % do SLA (pendência
  documental aberta → tarefa `regulation_pending_document` para a UBS solicitante) e em 100 % (pendência
  `sla_breached`, `sus.regulation.status.changed` com `sla_breached=true` e `actor_kind=workflow`, tarefa
  `generic` "SLA de regulação vencido" na fila `regulacao`); sinal `status_changed` encerra ao haver decisão.
- `ExamFollowUpWorkflow` (`exam-followup:<exam_order_id>`, Workflow 1): N dias sem agendamento → pendência
  `not_scheduled` + tarefa `exam_not_scheduled` (UBS solicitante); falta → `no_show_recovery`; realizado sem
  laudo em 7 d → `result_pending`; laudo sem retorno em M dias → `exam_result_followup`; encerra em
  cancelamento/não realização ou quando a tarefa de retorno é concluída (`sus.task.completed` com origem
  `exam-followup:<id>`). Reconcilia com o estado persistido (`snapshot`) a cada prazo.

Os starters ficam nos **consumidores** (`TaskEventsConsumer`, `IdentityMergeConsumer`,
`RegulationEventsConsumer`, `ExamEventsConsumer`), não nos serviços, com
`WorkflowIdReusePolicy=REJECT_DUPLICATE` — replay de eventos não duplica workflows.

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
