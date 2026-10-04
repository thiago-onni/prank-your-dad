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
  FhirResourceEndpoint  /fhir/r4/{metadata, (POST Bundle), _history, $validate, {type}, {type}/_history,
                        {type}/{id} (GET/PUT/PATCH/DELETE), {type}/{id}/_history[/{vid}], Patient/{id}/$everything}
  InternalProjectionEndpoint /internal/projections/{citizen, health-unit, appointment, task,
                             regulation-request, exam-order, encounter, exam-result, hospital-episode,
                             care-plan, care-gap}
  FhirExceptionMappers  → tudo vira OperationOutcome (400/401/403/404/405/410/412/415/422/500)
  SecurityRequestFilter → Identity (OIDC ou headers de teste) + RequestContext (tenant, finalidade, correlação)
        │
Interaction Router                    br.gov.sus.nexus.fhir.interaction
  FhirInteractionService: read | vread | search | create | update | patch | delete | history-instance |
                          history-type | history-system | Patient/$everything | $validate
  BundleProcessor (batch/transaction) · BinaryInteractionService (Binary ↔ BinaryStorage) · JsonPatch
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
  RegulationRequestMapper, ExamOrderMapper, EncounterMapper, ExamResultMapper, HospitalEpisodeMapper,
  CarePlanMapper, CareGapMapper (canônico do core → FHIR)
  ProjectionService (upsert idempotente por conteúdo + Provenance + resolução de referências por CNES)
Direct write (FHIR → canônico)        br.gov.sus.nexus.fhir.directwrite
  DirectWriteService · PatientToCitizenMapper · ServiceRequestToExamOrderMapper → CoreMunicipalClient
        │
Projection (Kafka)                    br.gov.sus.nexus.fhir.projection
  ProjectionEventConsumer (SmallRye, 11 tópicos) → ProjectionEventHandler → CoreMunicipalClient (REST,
  client-credentials) → ProjectionService; inbox idempotente fhir.projection_inbox(event_id)
        │
Persistence (PostgreSQL 16, Flyway)   br.gov.sus.nexus.fhir.persistence
  fhir.fhir_resource (JSONB corrente) · fhir.fhir_resource_history (append-only, trigger)
  fhir.fhir_idx_token / _string / _date / _reference (reconstruídos a cada versão)
  fhir.fhir_idx_quantity (value-quantity) · fhir.fhir_binary (metadados de Binary; conteúdo no
  object storage — BinaryStorage: FileBinaryStorage em dev/teste, S3BinaryStorage em produção)
  fhir.projection_inbox (idempotência do consumidor Kafka)
  RLS por tenant em todas as tabelas; papel sus_nexus_fhir_app sem BYPASSRLS; Flyway como admin
```

### Registro de capacidades (`CapabilityRegistry`)

Tudo o que o servidor expõe é registrado em código: tipo, interações, parâmetros de busca (com a
expressão FHIRPath usada na indexação) e perfil. `GET /fhir/r4/metadata` gera o `CapabilityStatement`
**a partir do registro**, o roteador recusa o que não está registrado e o teste
`RouteRegistryConsistencyTest` garante que cada rota JAX-RS (`@FhirRoute`) corresponde a uma
interação/operação registrada e vice-versa.

Recursos (FHIR-1..3): `Patient`, `Organization`, `Location`, `Practitioner`, `PractitionerRole`,
`Encounter`, `Appointment`, `ServiceRequest`, `Task`, `Condition`, `CarePlan`, `Observation`,
`DiagnosticReport`, `DocumentReference` (read, vread, search, create, update, patch, delete lógico,
history-instance, history-type); `Binary` (somente create/read, conteúdo no object storage) e,
somente leitura (read/search), `AuditEvent` e `Provenance` (gerados pelo gateway). Interações de
sistema: `transaction`, `batch`, `history-system`; operações: `$validate` (sistema/tipo) e
`Patient/$everything`.

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
| Observation | `subject`/`patient`, `code`, `date` (effective), `status`, `category`, `value-quantity`, `based-on`, `encounter` | `Observation:patient`, `Observation:based-on` |
| DiagnosticReport | `subject`/`patient`, `date` (effective), `status`, `code`, `category`, `based-on`, `result`, `performer` | `DiagnosticReport:patient`, `DiagnosticReport:result`, `DiagnosticReport:based-on` |
| DocumentReference | `subject`/`patient`, `date`, `status`, `type`, `category`, `author`, `related` (context.related) | `DocumentReference:patient`, `DocumentReference:subject` |
| Binary | — (sem busca; `patient` = `securityContext` só para compartimento/`$everything`) | |
| AuditEvent | `entity`, `date`, `action`, `agent`, `patient` | |
| Provenance | `target`, `recorded`, `agent` | |

Modificadores: `:exact`, `:contains` (string), `:Tipo` (referência). Prefixos de data
`eq ne gt lt ge le sa eb`. `value-quantity=[prefixo]número[|system|code]` (ou `número|code`):
`eq` (padrão) usa a precisão implícita do número (`5.4` casa 5.35..5.45); `ne gt lt ge le`;
índice em `fhir_idx_quantity` (`Quantity.code`, senão `unit`). Parâmetro repetido = AND; vírgula = OR. Strings são normalizadas
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
  (read/vread/history-instance=r, search/history-type=s, create=c, update/patch=u, delete=d);
  **`DocumentReference` e `Binary` exigem escopo que nomeie o tipo** (`user/DocumentReference.read`,
  `user/Binary.write`…; o curinga `*` não basta — `CapabilityRegistry.EXPLICIT_SCOPE_TYPES`); contexto `patient/` limitado ao compartimento do próprio
  paciente: `Patient` pelo id; `Encounter`, `Appointment`, `ServiceRequest`, `Task`, `Condition`,
  `CarePlan` e `AuditEvent` pelo parâmetro `patient` registrado (busca recebe o filtro
  `patient=Patient/<id>` em AND; leituras por id verificam o pertencimento após a carga —
  `PatientCompartment` — e negam com 403); tipos sem compartimento (`Organization`, `Provenance`…)
  são negados nesse contexto; escrita nunca.
- `RedactionPolicy` — `ScopeRedactionPolicy`: leitura por escopo granular (ex.: `user/Condition.rs`)
  em vez de completo (`user/Condition.read`, `user/*.read`) remove `Patient.telecom/address`,
  `Condition.code.text`, `ServiceRequest.reasonCode`, `Encounter.reasonCode`, `Task.description`,
  `CarePlan.description`, `Observation.valueString`/`note`, `DiagnosticReport.conclusion`/
  `presentedForm` e marca `meta.tag REDACTED` + extensão `redacted-elements`. Recursos com
  `meta.security` `highly_restricted` (CodeSystem municipal `sensitivity`) ou `V`
  (v3-Confidentiality) só são visíveis com escopo completo: 403 em leitura por id, omitidos em busca,
  `_include` e histórico.
- Logs JSON sem PII (apenas ids técnicos, tipos e correlation-id).

### Auditoria e proveniência

Toda interação (sucesso ou negação) gera um `AuditEvent` persistido como recurso FHIR (tipo `rest`,
subtipo = interação, `agent.who` = subject, `agent.policy` = escopos, `source.site` = tenant,
`entity.what` = recurso/versão, `purposeOfEvent` do header `X-Purpose-Of-Use`). Operações
compostas usam o subtipo da operação (`everything`, `transaction`, `batch`, `history-system`) com
uma entidade por recurso tocado/devolvido; leituras de `Binary` (sucesso e negação) são auditadas
como qualquer leitura. A auditoria é gravada em transação própria (sobrevive ao rollback de um
Bundle `transaction`). Recursos projetados
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
  regulation-request, exam-order, encounter, exam-result, hospital-episode, care-plan, care-gap}` (escopo `system/*.write` ou `system/<Tipo>.write`;
  corpo = canônico completo do OpenAPI; headers opcionais `X-Source-System`/`X-Source-Record-Id`
  sobrepõem `source_system`/`source_record_id` do canônico). Usados para carga inicial e
  reprocessamento; resposta 201/200 com `ETag`, `Location` e `X-Provenance-Location` quando houve
  mudança.
- **Kafka** (`projection/`): `ProjectionEventConsumer` consome `sus.schedule.appointment.v1`,
  `sus.task.v1`, `sus.regulation.request.v1`, `sus.regulation.status.v1`, `sus.exam.order.v1`,
  `sus.aps.encounter.v1` e (FHIR-3) `sus.exam.result.v1`, `sus.hospital.adt.v1`,
  `sus.hospital.discharge.v1`, `sus.careplan.v1`, `sus.caregap.v1`. Para cada envelope: valida `event_id`/`tenant`, consulta o inbox
  (`fhir.projection_inbox`, RLS por tenant), busca o canônico completo no core via REST client
  (`GET /api/v1/appointments/{id}`, `/tasks/{id}`, `/regulation/requests/{id}`,
  `/exams/orders/{id}`, `/hospital/episodes/{id}`, `/careplans/{id}`) com `Authorization: Bearer` obtido por client-credentials
  (`sus.fhir.core.token-url`, `client-id`, `client-secret`; cache até expirar) e projeta; o
  atendimento APS é projetado do próprio `data` do evento (o core não expõe atendimentos), com
  `subject.municipal_citizen_id`, `source` e `privacy.classification` do envelope; a lacuna de
  cuidado (`sus.caregap.v1`) também vem do próprio `data` (+ `subject.municipal_citizen_id`). Para
  `sus.exam.result.v1` o pedido é buscado no core e o resultado é localizado em `results[]` pelo
  `exam_result_id`; se o core ainda não o expõe, usam-se os metadados do evento (status,
  `reported_at`, `critical`, `performer_cnes`). `exam-result` no canal interno projeta todos os
  `results[]` do pedido (ou só `?result_id=`). O inbox é gravado
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
| `ExamOrder` | `ServiceRequest` | requested, authorized, scheduled, collected→active; performed, reported→completed; cancelled, not_performed→revoked (ext. `exam-order-status`) | `category` laboratory→SNOMED 108252007, imaging→363679005 (+ `exam-category` municipal); `code` SIGTAP/LOINC/local; `basedOn` ServiceRequest da regulação; ext. `exam-issue`, `care-line` |
| `ExamOrder` + `ExamResult` (+ `observations[]` de `ExamResultRegistration`) | `DiagnosticReport` + `Observation` (uma por item) + `DocumentReference` (quando `has_document`/`document_ref`/`binary_id`) | final→final; preliminary→preliminary; amended→amended; **inconclusive→partial** (Observation: unknown); cancelled→cancelled (DocumentReference entered-in-error); canônico em ext. `exam-result-status` | `category` v2-0074 LAB/RAD/OTH (+ `exam-category`); `code` do pedido; `basedOn` ServiceRequest do pedido; `result` → Observations (`<resultado>-obs-N`); `performer` Organization por CNES; Observation `valueQuantity` UCUM ou `valueString` mascarado, `interpretation` N/A/AA (crítico); DocumentReference LOINC 11502-2/18748-4, `context.related` pedido + laudo, `attachment.url` = `{base}/Binary/{binary_id}` ou referência opaca da origem em ext. `document-ref` (link assinado do core nunca é persistido); nada inline |
| `HospitalEpisode` | `Encounter` | admitted, in_progress, transferred→in-progress; discharged, deceased→finished; cancelled→cancelled (ext. `hospital-episode-status`) | `class` inpatient→IMP, emergency→EMER, observation→OBSENC, day_hospital→SS; `period` admissão/alta; `hospitalization.dischargeDisposition` (home, alt-home, other-hcf, aadvice, exp, oth) e `admitSource`; `reasonCode` CID-10 **só se presente**; `location` Location e `serviceProvider` Organization por CNES; AIH em `identifier`; `basedOn` regulação; ext. ward/bed, risk-level, length-of-stay, readmission, previous-episode |
| `CarePlan` | `CarePlan` | active→active; on_hold→on-hold; completed→completed; cancelled→revoked (ext. `care-plan-status`) | `intent=plan`; `category` = linha de cuidado; uma `activity.detail` por item (`code` = código do item + `care-plan-item-kind`; planned→not-started, scheduled→scheduled, done→completed, missed→stopped, cancelled→cancelled; `scheduledPeriod.end` = `expected_by`; ext. item-id/overdue); `author` profissional lógico; `contributor` Organization por CNES; `supportingInfo` = origem (ex.: Encounter da alta); ext. protocol-id/version, open-gaps |
| `CareGap` | `Task` (`code` `care_gap`) | open→requested; resolved→completed (`businessStatus` canônico) | `reasonCode` = `gap_kind` (`care-gap-kind`); `focus` = CarePlan; `for` Patient; `owner` Organization por CNES; `basedOn` Task operacional; prioridade por `days_overdue` (>30 asap, >0 urgent); ext. care-line, days-overdue, gap-resolution, contact-valid |
| evento `sus.aps.encounter.v1` (`CanonicalEncounter`) | `Encounter` | planned→planned; in_progress→in-progress; finished→finished; cancelled→cancelled | `class` aps_home_visit→HH, demais aps_* e ambulatory→AMB, emergency→EMER, inpatient→IMP (classe canônica em `type`); `subject`; `period`; `serviceProvider` por CNES; profissional lógico; CID-10/CIAP-2 em `reasonCode`; `highly_restricted` → `meta.security`; `fromTimelineEvent` deriva o mínimo de um `TimelineEvent` de domínio `aps` |

Perfis: `sus.fhir.profiles.encounter`/`condition` apontam para br-core (`BRCoreEncounter`,
`BRCoreCondition`); `appointment`, `service-request`, `task`, `care-plan` usam perfis municipais
(`sus.fhir.profiles.municipal-base-url`). Invariantes municipais por FHIRPath: `sus-enc-1..3`,
`sus-app-1..2`, `sus-sr-1..3`, `sus-task-1..2`, `sus-cond-1..2`, `sus-cp-1..2`, `sus-obs-1..2`,
`sus-dr-1..3`, `sus-doc-1..3`, `sus-bin-1..2` (ex.: Appointment com participante Patient;
ServiceRequest/Condition/Task/Observation/DiagnosticReport com `code` codificado com `system`;
**`sus-doc-1`: `DocumentReference.content.attachment.data` proibido** e `sus-dr-3` idem para
`DiagnosticReport.presentedForm` — conteúdo sempre via `Binary`; `sus-bin-2`: `Binary.securityContext`
→ Patient). FHIR-3 usa perfis municipais `SUSNexusObservation`, `SUSNexusDiagnosticReport`,
`SUSNexusDocumentReference` (`sus.fhir.profiles.*`).

### Escrita direta → canônico (seção 6.2 do plano)

`POST`/`PUT`/`PATCH` em `Patient` ou `ServiceRequest` por credencial com **escopo de sistema**
(`system/Patient.write`, `system/ServiceRequest.write`) não grava direto no `fhir-db`:
`DirectWriteService` converte o recurso validado para o canônico e chama o core pelo mesmo REST
client da projeção (client-credentials, `Idempotency-Key` derivada do identificador de origem):

- `Patient` → `CitizenRegistration` (`PatientToCitizenMapper`: CNS/CPF/sistemas de origem
  conhecidos, nome oficial/social, mãe, sexo, nascimento, endereço com IBGE, contatos, território
  por CNES) → `POST /api/v1/citizens`. 200/201 → grava com o id resolvido
  (`municipal_citizen_id` em `identifier`, tag `direct-write`) e responde 200/201; **202** (caso de
  revisão no MPI) → grava com a tag `pending-identity` e responde 202 com `OperationOutcome`
  informativo (`Location` aponta a versão gravada).
- `ServiceRequest` de **exame** (categoria SNOMED 108252007/363679005 ou `exam-category`
  laboratory/imaging) → `ExamOrderRegistration` → `POST /api/v1/exams/orders`; qualquer outra
  categoria (regulação) → **422** sem chamar o core.
- Core 4xx → 422 com o `title` do problem; 5xx/indisponível → 502; nada é gravado. `PUT` cujo id
  difere do id resolvido pelo core → 422. Credenciais `user/` mantêm a escrita local validada.

### Binary e object storage

`POST Binary` (JSON FHIR com `contentType`, `securityContext` → Patient e `data`) grava o conteúdo
no `BinaryStorage` (`sus.fhir.binary.storage=file|s3`; chave `<tenant>/<id>`) e, no banco, o
recurso **sem `data`** + metadados em `fhir.fhir_binary` (tipo, tamanho, sha256, chave; RLS).
Limite `sus.fhir.binary.max-bytes` (20 MiB). `GET Binary/{id}`: escopo explícito `Binary.read`,
compartimento do paciente pelo `securityContext` (contexto `patient/`), retenção/tenant e
`AuditEvent`; com `Accept` JSON/FHIR devolve o recurso com `data` em base64, com outro `Accept`
(ex.: `application/pdf`) devolve o conteúdo bruto. `S3BinaryStorage` (AWS SDK v2 com
`url-connection-client`; MinIO via `endpoint` + `path-style`; credenciais estáticas opcionais,
senão cadeia padrão) não tem teste de rede — só construção. Variáveis `FHIR_BINARY_STORAGE`,
`FHIR_BINARY_DIR`, `FHIR_BINARY_MAX_BYTES`, `FHIR_BINARY_S3_{BUCKET,REGION,ENDPOINT,PATH_STYLE,
ACCESS_KEY,SECRET_KEY}`.

### `Patient/$everything`

`GET Patient/{id}/$everything[?_type=&_since=&_count=&_cursor=]`: o Patient e os recursos cujo
parâmetro `patient` aponta para ele (padrão: Encounter, Appointment, ServiceRequest, Task,
Condition, CarePlan, Observation, DiagnosticReport, DocumentReference; `Binary` só com `_type`).
Cada tipo passa pela `AccessPolicy` (tipo sem escopo → omitido; DocumentReference/Binary exigem
escopo explícito), pela retenção `highly_restricted` e pela redação; contexto `patient/` só para o
próprio paciente (403 para outro). Paginação keyset (tipo, id) com cursor HMAC (`_count` padrão 50,
máx. 200) e limite de páginas `sus.fhir.everything.max-pages` (400 `too-costly` além dele).
`AuditEvent` por página com subtipo `everything`, `purposeOfEvent` e uma entidade por recurso.

### Bundles, histórico, delete e patch

- `POST /fhir/r4` com `Bundle` `transaction`: todas as entradas em **uma transação do banco**
  (`TenantTransaction.atomic`, chamadas aninhadas participam dela), ordem DELETE→POST→PUT→GET;
  qualquer falha reverte tudo e a resposta é o `OperationOutcome` da entrada (status
  correspondente). `fullUrl` `urn:uuid:` de POST/PUT recebem o id antes do processamento e todas as
  `Reference` do Bundle são reescritas; `request.ifNoneExist` (`identifier=system|value`) faz create
  condicional (0 → cria, 1 → 200 com o existente, >1 → 412); `request.ifMatch` em PUT. `batch`:
  entradas independentes, erros em `response.status`/`response.outcome`. Resposta na ordem do pedido.
- `GET [type]/_history` e `GET _history`: versões mais recentes primeiro, `_since`, `_count`, cursor
  keyset (data, id, versão); o histórico de sistema só inclui tipos com `history-type` que a
  identidade pode pesquisar (filtro no SQL) e respeita o compartimento `patient/`.
- `DELETE [type]/{id}`: exclusão **lógica** (nova versão `deleted`, índices removidos); leitura e
  `vread` da versão excluída → 410; busca não encontra; histórico mostra `DELETE`; `PUT` posterior
  recria (201). Nada é apagado fisicamente.
- `PATCH [type]/{id}` com `application/json-patch+json` (RFC 6902: add, remove, replace, move,
  copy, test): aplicado ao JSON corrente e gravado pelo mesmo caminho do `PUT` (validação completa,
  escrita direta para parceiros); `If-Match` opcional (padrão: a versão lida → 412 em corrida);
  `id`/`resourceType` imutáveis (422).

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
`CORE_TOKEN_URL`, `CORE_CLIENT_ID`, `CORE_CLIENT_SECRET`, `SIGTAP_SYSTEM`, `FHIR_BINARY_*`,
`FHIR_EVERYTHING_MAX_PAGES`.

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
   `V2__fhir2.sql` acrescentou o inbox do consumidor e um índice de apoio ao `_sort`;
   `V3__fhir3.sql` a tabela `fhir_binary`, o índice de quantidade e índices do `_history`.

## Limites atuais e próximos passos

- Sem `_revinclude`, `_sort` por mais de um parâmetro ou por parâmetros não temporais, busca
  encadeada, FHIRPath Patch (`Parameters`), delete/update condicionais e XML. `_summary`/`_elements` ignorados
  (400 por parâmetro desconhecido). `_include` limitado aos pares registrados; `_include:iterate`
  não suportado.
- Validação de perfil br-core depende do pacote NPM (ponto de extensão documentado); terminologias
  externas (CBO, CID, SIGTAP, LOINC) não são verificadas.
- Datas sem fuso são indexadas em UTC (consistente entre índice e consulta); configurar fuso do
  tenant é evolução prevista.
- Escrita direta → canônico cobre `Patient` e `ServiceRequest` de exame; os demais tipos com escopo
  de sistema gravam localmente (validados). A chamada ao core é efeito colateral fora da transação de
  um Bundle `transaction` (um rollback posterior não desfaz o registro no core, que é idempotente).
- `Binary`: só JSON FHIR na criação (sem upload bruto com `Content-Type` arbitrário); sem update,
  delete ou busca; conteúdo de um `Binary` criado em transação revertida fica órfão no storage.
- `$everything` não segue referências fora do compartimento (Organization, Practitioner…), não
  suporta `start`/`end` e pagina por (tipo, id), não por data.
- Projeção: o consumidor Kafka cobre agenda, tarefas, regulação, pedidos e resultados de exame,
  atendimento APS, ADT/alta hospitalar, planos e lacunas de cuidado; `sus.identity.citizen.v1`
  (cidadão/unidade) continua pelo endpoint interno. `Condition` ainda não tem origem canônica no core.
  O core expõe em `ExamOrder.results[]` apenas o resumo (`ExamResult`); `observations[]` e
  `document_ref` só chegam quando o core os inclui (campos de `ExamResultRegistration`) — senão o
  laudo é projetado sem Observations. Profissionais e
  equipes ficam como referências lógicas (`PractitionerRole`/`CareTeam` por identificador municipal)
  até o core expor o cadastro; `Location` por CNES só é resolvida quando projetada.
- Próximos: OPA real na `AccessPolicy`, DLQ/retry do consumidor com os tópicos `.retry` do
  catálogo, cópia do laudo da origem para `Binary` próprio (hoje `binary_id` é opcional no canônico),
  `$everything` com `start`/`end` e upload bruto de `Binary`.

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
