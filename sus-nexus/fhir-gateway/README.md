# SUS Nexus — FHIR Gateway próprio (FHIR R4)

Gateway FHIR R4 do barramento municipal, implementado em **Java 21 + Quarkus 3.39** conforme a
**ADR-003**: as bibliotecas de referência HL7 (`org.hl7.fhir.r4` para modelos/parser/FHIRPath e
`org.hl7.fhir.validation` para o validador oficial) são usadas **apenas como biblioteca**, atrás de
interfaces próprias (`FhirCodec`, `FhirValidator`, `ProfileValidator`). **Não há servidor HAPI**
(`hapi-fhir-server`, `RestfulServer`, interceptors ou `hapi-fhir-jpaserver-*`): API HTTP, roteamento,
persistência, segurança, auditoria e paginação são código deste módulo.

## Arquitetura

```text
HTTP (Quarkus REST / JAX-RS)          br.gov.sus.nexus.fhir.http
  FhirResourceEndpoint  /fhir/r4/{metadata, $validate, {type}, {type}/{id}, _history, _history/{vid}}
  InternalProjectionEndpoint /internal/projections/{citizen, health-unit, appointment, task,
                             regulation-request, exam-order, encounter}
  FhirExceptionMappers  → tudo vira OperationOutcome (400/401/403/404/405/410/412/415/422/500)
  SecurityRequestFilter → Identity (OIDC ou headers de teste) + RequestContext (tenant, finalidade, correlação)
        │
Interaction Router                    br.gov.sus.nexus.fhir.interaction
  FhirInteractionService: read | vread | search | create | update | history-instance | $validate
    1. CapabilityRegistry.supports(type, interaction)   (404 not-supported / 405)
    2. AccessPolicy (escopos SMART-like + tenant; ponto de extensão OPA)
    3. ValidationService (create/update/$validate)
    4. TenantTransaction (set_config app.tenant_id → RLS) + FhirResourceRepository
    5. RedactionPolicy (após leitura, antes de serializar)
    6. AuditRecorder → AuditEvent (toda interação, inclusive negações)
  SearchRequestParser (_count, _cursor, _sort, _total, _include) + SearchCursor (HMAC) + SearchSqlBuilder
        │
Validation Pipeline                   br.gov.sus.nexus.fhir.validation
  parse estrito (JsonParser HL7) → tipo coerente com a URL → CardinalityChecker (modelos)
  → ProfileValidator (InstanceValidator HL7 com definições 4.0.1 do classpath; IGs opcionais)
  → meta.profile obrigatório / perfil desconhecido rejeitado → TerminologyChecks (bindings básicos)
  → MunicipalInvariants (FHIRPath, ex.: sus-pat-1 CNS ou CPF obrigatório)
        │
Mapping                               br.gov.sus.nexus.fhir.mapping
  CitizenToPatientMapper, HealthUnitToOrganizationMapper, AppointmentMapper, TaskMapper,
  RegulationRequestMapper, ExamOrderMapper, EncounterMapper (canônico do core → FHIR)
  ProjectionService (upsert idempotente por conteúdo + Provenance + resolução de referências por CNES)
        │
Projection (Kafka)                    br.gov.sus.nexus.fhir.projection
  ProjectionEventConsumer (SmallRye, 6 tópicos) → ProjectionEventHandler → CoreMunicipalClient (REST,
  client-credentials) → ProjectionService; inbox idempotente fhir.projection_inbox(event_id)
        │
Persistence (PostgreSQL 16, Flyway)   br.gov.sus.nexus.fhir.persistence
  fhir.fhir_resource (JSONB corrente) · fhir.fhir_resource_history (append-only, trigger)
  fhir.fhir_idx_token / _string / _date / _reference (reconstruídos a cada versão)
  fhir.projection_inbox (idempotência do consumidor Kafka)
  RLS por tenant em todas as tabelas; papel sus_nexus_fhir_app sem BYPASSRLS; Flyway como admin
```

### Registro de capacidades (`CapabilityRegistry`)

Tudo o que o servidor expõe é registrado em código: tipo, interações, parâmetros de busca (com a
expressão FHIRPath usada na indexação) e perfil. `GET /fhir/r4/metadata` gera o `CapabilityStatement`
**a partir do registro**, o roteador recusa o que não está registrado e o teste
`RouteRegistryConsistencyTest` garante que cada rota JAX-RS (`@FhirRoute`) corresponde a uma
interação/operação registrada e vice-versa.

Recursos (FHIR-1 + FHIR-2): `Patient`, `Organization`, `Location`, `Practitioner`,
`PractitionerRole`, `Encounter`, `Appointment`, `ServiceRequest`, `Task`, `Condition`, `CarePlan`
(read, vread, search, create, update, history-instance) e, somente leitura, `AuditEvent` e
`Provenance` (gerados pelo gateway).

| Tipo | Parâmetros de busca | `_include` |
|---|---|---|
| todos | `_id`, `_lastUpdated`, `_count` (máx. 200), `_cursor`, `_sort`, `_total` | |
| Patient | `identifier`, `name`, `birthdate` | |
| Organization | `identifier`, `name` | |
| Location | `identifier`, `name`, `organization` | |
| Practitioner | `identifier`, `name` | |
| PractitionerRole | `practitioner`, `organization`, `role` | |
| Encounter | `patient`, `date` (period), `status`, `class`, `service-provider` | `Encounter:patient` |
| Appointment | `patient`, `date` (start), `status`, `service-type`, `location`, `actor` | `Appointment:patient` |
| ServiceRequest | `subject`/`patient`, `status`, `code`, `authored`, `requester`, `performer`, `category`, `priority` | `ServiceRequest:patient` |
| Task | `for`/`patient`, `status`, `code`, `owner`, `authored-on`, `business-status`, `priority`, `based-on` | `Task:patient`, `Task:based-on` |
| Condition | `subject`/`patient`, `code`, `clinical-status`, `category`, `onset-date` | |
| CarePlan | `subject`/`patient`, `status`, `category`, `date` (period) | |
| AuditEvent | `entity`, `date`, `action`, `agent`, `patient` | |
| Provenance | `target`, `recorded`, `agent` | |

Modificadores: `:exact`, `:contains` (string), `:Tipo` (referência). Prefixos de data
`eq ne gt lt ge le sa eb`. Parâmetro repetido = AND; vírgula = OR. Strings são normalizadas
(minúsculas, sem acento) e a busca padrão é por prefixo. Parâmetros desconhecidos → 400 (modo
estrito). Resultados com `_count` + 1 decidem a existência de `link[next]`.

- `_sort=<param>` / `_sort=-<param>`: apenas parâmetros de data do tipo e `_lastUpdated`; a chave é
  o menor valor indexado do parâmetro (recursos sem o elemento ficam no fim em ordem crescente e no
  início em decrescente). O cursor carrega a chave da última entrada (keyset) — sem `OFFSET`.
- `_total=accurate` executa `count(*)` com os mesmos filtros (inclusive o compartimento do paciente);
  `none`/`estimate` não calculam `total`.
- `_include=Tipo:param[:Alvo]`: apenas os pares anunciados em `searchInclude` do `CapabilityStatement`.
  Os alvos vêm do índice de referências da página; cada um passa pela `AccessPolicy` de leitura (sem
  escopo → omitido em silêncio), pela retenção `highly_restricted` e pela redação; entradas com
  `search.mode=include`.
- Referências lógicas (somente `identifier`, ex.: `Task.owner` por CNES ainda não projetado) são
  indexadas pelo valor do identificador com o `Reference.type` declarado; `owner=2112345` encontra.

### Versionamento e concorrência

`meta.versionId`/`meta.lastUpdated` são atribuídos pelo servidor; `ETag: W/"n"` e `Last-Modified`
em leituras; `If-None-Match` → 304; `If-Match` em `PUT` → 412 em conflito; `PUT` em id inexistente
cria (update-as-create, 201). Todo histórico fica em `fhir_resource_history` (append-only).

### Segurança

- Produção: OIDC/Keycloak (`quarkus-oidc`), escopos SMART-like no claim `scope`
  (`patient/*.read`, `user/*.read`, `user/*.write`, `system/*.read`, `system/*.write`, ou letras v2
  como `user/Patient.rs`), tenant no claim `municipality_id` (`X-Tenant-Id` só para credenciais de
  sistema), paciente no claim `patient`.
- `%dev`/`%test`: OIDC desabilitado; identidade fake via `X-Test-User`, `X-Test-Scopes`,
  `X-Tenant-Id`, `X-Test-Patient`.
- `AccessPolicy` (ponto de extensão OPA) — implementação `ScopeAccessPolicy`: interação → permissão
  (read=r, search=s, create=c, update=u); contexto `patient/` limitado ao compartimento do próprio
  paciente: `Patient` pelo id; `Encounter`, `Appointment`, `ServiceRequest`, `Task`, `Condition`,
  `CarePlan` e `AuditEvent` pelo parâmetro `patient` registrado (busca recebe o filtro
  `patient=Patient/<id>` em AND; leituras por id verificam o pertencimento após a carga —
  `PatientCompartment` — e negam com 403); tipos sem compartimento (`Organization`, `Provenance`…)
  são negados nesse contexto; escrita nunca.
- `RedactionPolicy` — `ScopeRedactionPolicy`: leitura por escopo granular (ex.: `user/Condition.rs`)
  em vez de completo (`user/Condition.read`, `user/*.read`) remove `Patient.telecom/address`,
  `Condition.code.text`, `ServiceRequest.reasonCode`, `Encounter.reasonCode`, `Task.description`,
  `CarePlan.description` e marca `meta.tag REDACTED` + extensão `redacted-elements`. Recursos com
  `meta.security` `highly_restricted` (CodeSystem municipal `sensitivity`) ou `V`
  (v3-Confidentiality) só são visíveis com escopo completo: 403 em leitura por id, omitidos em busca,
  `_include` e histórico.
- Logs JSON sem PII (apenas ids técnicos, tipos e correlation-id).

### Auditoria e proveniência

Toda interação (sucesso ou negação) gera um `AuditEvent` persistido como recurso FHIR (tipo `rest`,
subtipo = interação, `agent.who` = subject, `agent.policy` = escopos, `source.site` = tenant,
`entity.what` = recurso/versão, `purposeOfEvent` do header `X-Purpose-Of-Use`). Recursos projetados
recebem `Provenance` (`ProvenanceFactory`) apontando para a versão criada, com `entity.what`
= registro de origem.

### Projeção a partir do core

`ProjectionService.upsert` é idempotente por conteúdo (mesmo recurso sem `meta`/`id` → nenhuma
versão nova, nenhuma `Provenance`), valida pelo mesmo pipeline das escritas diretas e resolve
referências lógicas por CNES (`Organization`/`Location` com `identifier` CNES → `Tipo/id` quando o
alvo já existe no tenant). Cada versão criada recebe `Provenance` (`target` = versão, `entity.what`
= registro de origem, `agent.who` = gateway, extensões `correlation-id` e `event-id`).

Canais:

- **Endpoints internos** `POST /internal/projections/{citizen, health-unit, appointment, task,
  regulation-request, exam-order, encounter}` (escopo `system/*.write` ou `system/<Tipo>.write`;
  corpo = canônico completo do OpenAPI; headers opcionais `X-Source-System`/`X-Source-Record-Id`
  sobrepõem `source_system`/`source_record_id` do canônico). Usados para carga inicial e
  reprocessamento; resposta 201/200 com `ETag`, `Location` e `X-Provenance-Location` quando houve
  mudança.
- **Kafka** (`projection/`): `ProjectionEventConsumer` consome `sus.schedule.appointment.v1`,
  `sus.task.v1`, `sus.regulation.request.v1`, `sus.regulation.status.v1`, `sus.exam.order.v1` e
  `sus.aps.encounter.v1`. Para cada envelope: valida `event_id`/`tenant`, consulta o inbox
  (`fhir.projection_inbox`, RLS por tenant), busca o canônico completo no core via REST client
  (`GET /api/v1/appointments/{id}`, `/tasks/{id}`, `/regulation/requests/{id}`,
  `/exams/orders/{id}`) com `Authorization: Bearer` obtido por client-credentials
  (`sus.fhir.core.token-url`, `client-id`, `client-secret`; cache até expirar) e projeta; o
  atendimento APS é projetado do próprio `data` do evento (o core não expõe atendimentos), com
  `subject.municipal_citizen_id`, `source` e `privacy.classification` do envelope. O inbox é gravado
  após a projeção; reprocessar entre os dois passos é inócuo (idempotência por conteúdo). Falhas
  propagam (nack) e seguem `mp.messaging.connector.smallrye-kafka.failure-strategy`
  (`FHIR_PROJECTION_FAILURE_STRATEGY`, padrão `fail`; `dead-letter-queue` em produção com os
  tópicos `.retry`/`sus.dlq.v1` do catálogo). Em `%test` os canais usam o conector em memória e o
  core é simulado por WireMock (`CoreWireMockResource`).

Mapeadores (canônico → FHIR; o status canônico íntegro fica sempre em extensão/`businessStatus`):

| Canônico | FHIR | Status | Outros |
|---|---|---|---|
| `Appointment` | `Appointment` | proposed→proposed; booked, confirmed→booked; arrived→arrived; fulfilled→fulfilled; cancelled→cancelled; noshow→noshow; waitlist→waitlist (ext. `appointment-status`) | `serviceType` SIGTAP (`sus.fhir.terminology.sigtap-system`) ou local; participantes Patient (SBJ), `Location` por CNES (LOC; `participant.actor` não admite Organization), profissional lógico (PPRF); ext. `appointment-kind`, `care-line`; `basedOn` ServiceRequest (regulação/exame); `end = start` quando ausente (inv. app-2) |
| `Task` | `Task` | open→requested; assigned→accepted; in_progress→in-progress; completed→completed; cancelled→cancelled; escalated→in-progress + `businessStatus` `escalated` | `intent=order`; `code` = `task_type` em `https://sus-nexus.gov.br/fhir/CodeSystem/task-type`; `for` Patient; `owner` user→PractitionerRole, team→CareTeam, health_unit→Organization (CNES), queue→Organization lógica; `restriction.period.end` = `due_at`; `basedOn` quando `origin` (direto se id com prefixo `reg_/exo_/apt_/enc_/cit_/task_`, lógico caso contrário); prioridade low/medium→routine, high→urgent, urgent→asap (ext. `task-priority`) |
| `RegulationRequest` | `ServiceRequest` | requested, pending_documents, under_review, authorized, scheduled, no_show→active; returned→on-hold; performed→completed; denied, cancelled, expired→revoked; ext. `regulation-status` sempre, `regulation-authorized=true` a partir de authorized | `intent=order`; prioridade elective→routine, priority→urgent, urgent→asap, emergency→stat; `category` = kind (`regulation-kind`); `code` SIGTAP/local; `requester` Organization pelo CNES solicitante (PractitionerRole lógico se só houver profissional); `performer` Organization prestadora; `occurrenceDateTime` = `scheduled_at`; `performerType.text` = especialidade; ext. `waiting-days`, `sla-due-at`, `justification-present`, `scheduled-appointment` |
| `ExamOrder` | `ServiceRequest` | requested, authorized, scheduled, collected→active; performed, reported→completed; cancelled, not_performed→revoked (ext. `exam-order-status`) | `category` laboratory→SNOMED 108252007, imaging→363679005 (+ `exam-category` municipal); `code` SIGTAP/LOINC/local; `basedOn` ServiceRequest da regulação; ext. `exam-issue`, `care-line`. **Resultados não são projetados** (`DiagnosticReport`/`Observation` = FHIR-3) |
| evento `sus.aps.encounter.v1` (`CanonicalEncounter`) | `Encounter` | planned→planned; in_progress→in-progress; finished→finished; cancelled→cancelled | `class` aps_home_visit→HH, demais aps_* e ambulatory→AMB, emergency→EMER, inpatient→IMP (classe canônica em `type`); `subject`; `period`; `serviceProvider` por CNES; profissional lógico; CID-10/CIAP-2 em `reasonCode`; `highly_restricted` → `meta.security`; `fromTimelineEvent` deriva o mínimo de um `TimelineEvent` de domínio `aps` |

Perfis: `sus.fhir.profiles.encounter`/`condition` apontam para br-core (`BRCoreEncounter`,
`BRCoreCondition`); `appointment`, `service-request`, `task`, `care-plan` usam perfis municipais
(`sus.fhir.profiles.municipal-base-url`). Invariantes municipais por FHIRPath: `sus-enc-1..3`,
`sus-app-1..2`, `sus-sr-1..3`, `sus-task-1..2`, `sus-cond-1..2`, `sus-cp-1..2` (ex.: Appointment com
participante Patient; ServiceRequest/Condition/Task com `code` codificado com `system`; `Task.for`
Patient).

## Decisão sobre o validador oficial (ADR-003, spike FHIR-0)

- O registro de pacotes (`packages.fhir.org`, `packages2.fhir.org`, Simplifier) **não é acessível**
  neste ambiente e os jars `org.hl7.fhir.*` não embutem `hl7.fhir.r4.core`.
- Solução adotada (offline, fixada no build): as definições base FHIR 4.0.1
  (`profiles-types/resources.xml`, `valuesets.xml`, `v3-codesystems.xml`, `v2-tables.xml`,
  `extension-definitions.xml`) vêm do artefato Maven `hapi-fhir-validation-resources-r4` (apenas
  recursos, `hapi-fhir-base` excluído) e são carregadas no `SimpleWorkerContext` R5 via
  `R4ToR5Loader` (`fromDefinitions` + `version.info` 4.0.1 + `withDefaultParams`). O
  `InstanceValidator` oficial roda então sem rede nem servidor de terminologia (`modo official`,
  padrão): invariantes (`ext-1`...), cardinalidade mínima/máxima, bindings `required` e códigos
  desconhecidos em CodeSystems HL7 carregados são reportados com `issue.expression`.
- Ajustes necessários na biblioteca: (a) a entrada duplicada do CodeSystem `spdx-license` é
  removida do bundle porque o builder injeta uma cópia própria e não aplica
  `allowLoadingDuplicates` no caminho `fromDefinitions`; (b) referências relativas
  (`Organization/x`) usam `BasePolicyAdvisorForFullValidation(IGNORE)` — a existência é garantida
  pelo gateway/core, não pelo validador; (c) `org.fhir:ucum` é dependência opcional obrigatória para
  o `ProfileUtilities`; (d) `xpp3` traz `junit:junit:4.7` em escopo compile (excluído, conflitava com
  Hamcrest 2 do REST-assured).
- Custo medido: carga única no start ~20–30 s (4 CPUs) e ~150 MB retidos após GC; validação por
  recurso na casa de dezenas de ms após aquecimento. Em testes o contexto é carregado uma vez por
  JVM.
- Parse estrito: o `JsonParser` de referência ignora propriedades desconhecidas e tolera formas
  erradas, por isso `HapiR4JsonCodec` compara a árvore de entrada com a árvore re-serializada
  (`StrictContentChecker`) e rejeita qualquer conteúdo que não sobreviva ao parse (400
  `structure`). Códigos fora de bindings `required` de tipo `code` (ex.: `Patient.gender`) são
  rejeitados já no parse pelos enums do modelo (400); bindings de `CodeableConcept` ficam a cargo do
  validador oficial (422).
- Os modelos `org.hl7.fhir.r4` declaram `min=0` em `children()`: `CardinalityChecker` só garante a
  cardinalidade máxima; a mínima vem do validador oficial.
- `sus.fhir.validation.mode=structural` desliga o validador oficial e mantém parser estrito +
  cardinalidade dos modelos + terminologia local + invariantes FHIRPath (`StructuralProfileValidator`).
- Perfis br-core/RNDS: ponto de extensão documentado em `src/main/resources/fhir/profiles/README.md`
  (pacote NPM no classpath + `sus.fhir.validation.ig-packages`). Sem o pacote, a obrigatoriedade de
  `meta.profile` e as regras municipais continuam valendo; o canônico é parametrizável em
  `sus.fhir.profiles.*`.

## Como rodar

Pré-requisitos: Java 21, Maven 3.9, PostgreSQL 16 local (`postgres/postgres`).

```bash
# testes (cria o banco uma vez)
PGPASSWORD=postgres createdb -h localhost -U postgres sus_nexus_fhir_test
mvn -q verify

# dev (porta 8081)
PGPASSWORD=postgres createdb -h localhost -U postgres sus_nexus_fhir
mvn quarkus:dev
```

Exemplos (perfil dev, identidade por headers):

```bash
curl -s localhost:8081/fhir/r4/metadata | jq '.rest[0].resource[].type'

curl -s -X POST localhost:8081/fhir/r4/Patient \
  -H 'Content-Type: application/fhir+json' -H 'X-Test-User: dra.ana' \
  -H 'X-Test-Scopes: user/*.read user/*.write' -H 'X-Tenant-Id: ibge_3143302' \
  -H 'X-Purpose-Of-Use: care_coordination' \
  --data @src/test/resources/fixtures/patient-brcore.json

curl -s 'localhost:8081/fhir/r4/Patient?name=silva&_count=20' -H 'X-Test-User: dra.ana' \
  -H 'X-Test-Scopes: user/Patient.rs' -H 'X-Tenant-Id: ibge_3143302'   # leitura restrita (redigida)
```

Variáveis: `FHIR_DB_URL`, `FHIR_DB_APP_USER/PASSWORD` (aplicação), `FHIR_DB_ADMIN_USER/PASSWORD`
(Flyway), `OIDC_URL`, `OIDC_CLIENT_ID`, `FHIR_CURSOR_SECRET`, `FHIR_BASE_URL`,
`KAFKA_BOOTSTRAP_SERVERS`, `FHIR_PROJECTION_GROUP`, `FHIR_PROJECTION_FAILURE_STRATEGY`, `CORE_URL`,
`CORE_TOKEN_URL`, `CORE_CLIENT_ID`, `CORE_CLIENT_SECRET`, `SIGTAP_SYSTEM`.

```bash
# projeção de um agendamento canônico pelo canal interno (dev)
curl -s -X POST localhost:8081/internal/projections/appointment \
  -H 'Content-Type: application/json' -H 'X-Test-User: core' \
  -H 'X-Test-Scopes: system/*.write' -H 'X-Tenant-Id: ibge_3143302' \
  --data @src/test/resources/fixtures/canonical-appointment.json

curl -s 'localhost:8081/fhir/r4/Task?patient=01HZX4Y5K6M7N8P9Q0R1S2T3U4&_include=Task:based-on&_sort=-authored-on&_total=accurate' \
  -H 'X-Test-User: dra.ana' -H 'X-Test-Scopes: user/*.read' -H 'X-Tenant-Id: ibge_3143302'
```

## Como adicionar um recurso

1. **Registro**: em `CapabilityRegistry.init()` registre o tipo com interações, perfil e
   parâmetros (`SearchParamDef.token/string/date/reference` com a expressão FHIRPath) e os
   `_include` permitidos (`.include("param")`, só para parâmetros de referência). Os índices
   são extraídos automaticamente por `SearchIndexer` (Identifier/Coding/CodeableConcept/code →
   token; HumanName/string → string; date/dateTime/Period → date; Reference → reference, inclusive
   lógica por identifier). Registre um parâmetro `patient` para o tipo participar do compartimento
   `patient/`.
2. **Regras**: adicione invariantes em `MunicipalInvariants` (FHIRPath + `issue.expression`) e, se
   necessário, bindings em `TerminologyChecks`.
3. **Mapper** (se o recurso for projeção do core): crie o canônico (`CanonicalXxx`, Jackson
   snake_case) e o mapper em `mapping/` (construtor com `MapperSettings` para teste unitário), um
   método em `ProjectionService`, a rota em `InternalProjectionEndpoint` e, se houver tópico, o
   caso em `ProjectionEventHandler` + canal em `ProjectionEventConsumer`/`application.properties`.
4. **Testes**: CRUD/busca via REST-assured e fixture conforme ao perfil; o teste de consistência
   registro ⇄ rotas e o de `metadata` cobrem o novo tipo automaticamente.
5. Nenhuma migração nova é necessária: o modelo de persistência é genérico (JSONB + índices).
   `V2__fhir2.sql` só acrescentou o inbox do consumidor e um índice de apoio ao `_sort`.

## Limites atuais e próximos passos

- Sem `delete`, `patch`, `_revinclude`, `_sort` por mais de um parâmetro ou por parâmetros não
  temporais, busca encadeada, `_history` de tipo/sistema e XML. `_summary`/`_elements` ignorados
  (400 por parâmetro desconhecido). `_include` limitado aos pares registrados; `_include:iterate`
  não suportado.
- Validação de perfil br-core depende do pacote NPM (ponto de extensão documentado); terminologias
  externas (CBO, CID, SIGTAP, LOINC) não são verificadas.
- Datas sem fuso são indexadas em UTC (consistente entre índice e consulta); configurar fuso do
  tenant é evolução prevista.
- Escrita direta por parceiros (`POST/PUT`) grava no `fhir-db` sem converter para o canônico e enviar
  ao core (seção 6.2 do plano) — a conversão FHIR → canônico é o próximo passo da camada de mapping.
- Projeção: o consumidor Kafka cobre agenda, tarefas, regulação, exames e atendimento APS;
  `sus.identity.citizen.v1` (cidadão/unidade) continua pelo endpoint interno. `Condition` e
  `CarePlan` ainda não têm origem canônica no core (apenas escrita direta validada). Profissionais e
  equipes ficam como referências lógicas (`PractitionerRole`/`CareTeam` por identificador municipal)
  até o core expor o cadastro; `Location` por CNES só é resolvida quando projetada.
- Próximos (FHIR-3): `Observation`, `DiagnosticReport` (resultados de exame), `DocumentReference`,
  `$everything`, Bundles `transaction/batch`, OPA real na `AccessPolicy`, DLQ/retry do consumidor
  com os tópicos `.retry` do catálogo.

## Notas de implementação

- Providers JAX-RS são instanciados no static-init do Quarkus, antes do registro das
  `@ConfigMapping`; por isso `SecurityRequestFilter` injeta a configuração via `Instance<>`.
- Nos testes, REST-assured codifica corpos em ISO-8859-1 por padrão: `FhirTestSupport` declara
  `application/fhir+json; charset=UTF-8` (senão "José" nunca casa com `name=jose`).
- Nome da mãe: extensão `patient-mothersMaidenName` (valor) + extensão própria
  `masked-mothers-name=true` quando vier mascarado (uma extensão com `value[x]` não pode ter
  sub-extensões — `ext-1`).

## Persistência — nota sobre Hibernate/Panache

As convenções do monorepo preveem Hibernate ORM com Panache para o core. No gateway o conteúdo é
JSONB opaco com índices dinâmicos e `set_config` por transação para RLS; por isso a camada usa JDBC
direto (Agroal) com SQL parametrizado, mais simples e previsível para este caso. Flyway e
`timestamptz` seguem a convenção.
