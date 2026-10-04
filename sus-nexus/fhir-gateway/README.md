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
  InternalProjectionEndpoint /internal/projections/{citizen, health-unit}
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
  SearchRequestParser + SearchCursor (cursor opaco HMAC) + SearchSqlBuilder
        │
Validation Pipeline                   br.gov.sus.nexus.fhir.validation
  parse estrito (JsonParser HL7) → tipo coerente com a URL → CardinalityChecker (modelos)
  → ProfileValidator (InstanceValidator HL7 com definições 4.0.1 do classpath; IGs opcionais)
  → meta.profile obrigatório / perfil desconhecido rejeitado → TerminologyChecks (bindings básicos)
  → MunicipalInvariants (FHIRPath, ex.: sus-pat-1 CNS ou CPF obrigatório)
        │
Mapping                               br.gov.sus.nexus.fhir.mapping
  CitizenToPatientMapper, HealthUnitToOrganizationMapper (canônico do core ⇄ FHIR), ProjectionService (+Provenance)
        │
Persistence (PostgreSQL 16, Flyway)   br.gov.sus.nexus.fhir.persistence
  fhir.fhir_resource (JSONB corrente) · fhir.fhir_resource_history (append-only, trigger)
  fhir.fhir_idx_token / _string / _date / _reference (reconstruídos a cada versão)
  RLS por tenant em todas as tabelas; papel sus_nexus_fhir_app sem BYPASSRLS; Flyway como admin
```

### Registro de capacidades (`CapabilityRegistry`)

Tudo o que o servidor expõe é registrado em código: tipo, interações, parâmetros de busca (com a
expressão FHIRPath usada na indexação) e perfil. `GET /fhir/r4/metadata` gera o `CapabilityStatement`
**a partir do registro**, o roteador recusa o que não está registrado e o teste
`RouteRegistryConsistencyTest` garante que cada rota JAX-RS (`@FhirRoute`) corresponde a uma
interação/operação registrada e vice-versa.

Recursos desta entrega (FHIR-1): `Patient`, `Organization`, `Location`, `Practitioner`,
`PractitionerRole` (read, vread, search, create, update, history-instance) e, somente leitura,
`AuditEvent` e `Provenance` (gerados pelo gateway).

| Tipo | Parâmetros de busca |
|---|---|
| todos | `_id`, `_lastUpdated`, `_count` (máx. 200), `_cursor` |
| Patient | `identifier`, `name`, `birthdate` |
| Organization | `identifier`, `name` |
| Location | `identifier`, `name`, `organization` |
| Practitioner | `identifier`, `name` |
| PractitionerRole | `practitioner`, `organization`, `role` |
| AuditEvent | `entity`, `date`, `action` |
| Provenance | `target` |

Modificadores: `:exact`, `:contains` (string), `:Tipo` (referência). Prefixos de data
`eq ne gt lt ge le sa eb`. Parâmetro repetido = AND; vírgula = OR. Strings são normalizadas
(minúsculas, sem acento) e a busca padrão é por prefixo. Parâmetros desconhecidos → 400 (modo
estrito). Resultados com `_count` + 1 decidem a existência de `link[next]`; `total` não é
calculado.

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
  (read=r, search=s, create=c, update=u); contexto `patient/` limitado ao próprio `Patient`
  (leitura e busca filtrada por `_id`).
- `RedactionPolicy` — `ScopeRedactionPolicy`: leitura de `Patient` apenas por escopo granular
  (ex.: `user/Patient.rs`) remove `telecom` e `address` e marca `meta.tag REDACTED`.
- Logs JSON sem PII (apenas ids técnicos, tipos e correlation-id).

### Auditoria e proveniência

Toda interação (sucesso ou negação) gera um `AuditEvent` persistido como recurso FHIR (tipo `rest`,
subtipo = interação, `agent.who` = subject, `agent.policy` = escopos, `source.site` = tenant,
`entity.what` = recurso/versão, `purposeOfEvent` do header `X-Purpose-Of-Use`). Recursos projetados
recebem `Provenance` (`ProvenanceFactory`) apontando para a versão criada, com `entity.what`
= registro de origem.

### Projeção a partir do core

`CitizenToPatientMapper` e `HealthUnitToOrganizationMapper` recebem o JSON canônico
(`CitizenSummary`/`CitizenDetail` e `HealthUnit` de `contracts/openapi/core-municipal.yaml`).
Identificadores vindos mascarados (`value_masked`) geram `identifier.value` mascarado com a extensão
`http://sus-nexus.gov.br/fhir/StructureDefinition/masked-identifier=true`; o canal interno
`POST /internal/projections/citizen` (escopo `system/*.write`) recebe o canônico completo (valores em
claro) vindo do core. ULIDs prefixados (`cit_…`) viram id FHIR sem o prefixo e o id canônico fica como
`identifier` (`municipal-citizen-id`). `managingOrganization` é resolvido por CNES quando a
`Organization` já foi projetada. **Em produção** este endpoint será substituído por um consumidor
Kafka de `sus.identity.citizen.v1` que busca o detalhe no core (idempotente por conteúdo).

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
(Flyway), `OIDC_URL`, `OIDC_CLIENT_ID`, `FHIR_CURSOR_SECRET`, `FHIR_BASE_URL`.

## Como adicionar um recurso

1. **Registro**: em `CapabilityRegistry.init()` registre o tipo com interações, perfil e
   parâmetros (`SearchParamDef.token/string/date/reference` com a expressão FHIRPath). Os índices
   são extraídos automaticamente por `SearchIndexer` (Identifier/Coding/CodeableConcept/code →
   token; HumanName/string → string; date/dateTime/Period → date; Reference → reference).
2. **Regras**: adicione invariantes em `MunicipalInvariants` (FHIRPath + `issue.expression`) e, se
   necessário, bindings em `TerminologyChecks`.
3. **Mapper** (se o recurso for projeção do core): crie `XxxToYyyMapper` em `mapping/`, um método em
   `ProjectionService` e, se for canal interno, a rota em `InternalProjectionEndpoint`.
4. **Testes**: CRUD/busca via REST-assured e fixture conforme ao perfil; o teste de consistência
   registro ⇄ rotas e o de `metadata` cobrem o novo tipo automaticamente.
5. Nenhuma migração nova é necessária: o modelo de persistência é genérico (JSONB + índices).

## Limites atuais e próximos passos

- Sem `delete`, `patch`, `_include/_revinclude`, `_sort`, `_total`, busca encadeada, `_history` de
  tipo/sistema e XML. `_summary`/`_elements` ignorados (400 por parâmetro desconhecido).
- Validação de perfil br-core depende do pacote NPM (ponto de extensão documentado); terminologias
  externas (CBO, CID, SIGTAP) não são verificadas.
- Datas sem fuso são indexadas em UTC (consistente entre índice e consulta); configurar fuso do
  tenant é evolução prevista.
- Escrita direta por parceiros (`POST/PUT`) grava no `fhir-db` sem converter para o canônico e enviar
  ao core (seção 6.2 do plano) — a conversão FHIR → canônico é o próximo passo da camada de mapping.
- Projeção via endpoint interno; consumidor Kafka (`sus.identity.citizen.v1`) e `event_inbox`
  idempotente ficam para a integração com o core.
- Próximos recursos (FHIR-2/3): `Encounter`, `Appointment`, `ServiceRequest`, `Task`, `Condition`,
  `CarePlan`, `$everything`, Bundles `transaction/batch`, `_include`, OPA real na `AccessPolicy`.

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
