# SUS Nexus — `core-municipal`

Monólito modular do barramento municipal de saúde digital (Fase 1 — fundação).
Java 21 + Quarkus 3.39.x + PostgreSQL 16. Segue `../CONVENTIONS.md` e o plano em
`docs/sus-nexus/PLANO_IMPLEMENTACAO.md` (§5.1–5.4).

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

Parâmetros do MPI ficam em `sus.mpi.*` (`application.properties`): `threshold.high/low`,
`jaro-winkler.agree/partial`, `blocking.*` e pesos m/u por campo (`weights.<campo>.m|u`).
As chaves de exemplo **não** devem ir para produção; o desenho prevê OpenBao/transit.

## Testes

```bash
PGPASSWORD=postgres createdb -h localhost -U postgres sus_nexus_test   # uma vez
mvn -q verify
```

`mvn verify` executa: Google Java Format (`fmt-maven-plugin`, goal `format`), compilação,
testes unitários (value objects, normalização, Fellegi-Sunter, mascaramento de log, ULID),
ArchUnit (modularidade) e `@QuarkusTest` + REST-assured contra `sus_nexus_test`
(Flyway `clean-at-start` no perfil test). Os contratos de evento são copiados de
`../contracts/events` para `target/test-classes/contracts/events` pelo `maven-resources-plugin`
e usados pelo `OutboxContractTest` (json-schema-validator, draft 2020-12 com asserção de formatos).

## Estrutura

```text
br.gov.sus.nexus.core
├── platform/            cross-cutting (sem dependência de módulos de domínio)
│   ├── tenant/          TenantContext, TenantFilter (claim/header), @TenantTransactional
│   │                    (JTA + set_config('app.tenant_id', ?, true)), TenantTransactions
│   ├── correlation/     CorrelationId + filtro (MDC, header de resposta)
│   ├── security/        CurrentActor, Purpose, Roles, AuthorizationPolicy (+ impl por papéis;
│   │                    ponto de extensão OPA), HeaderAuthenticationMechanism (dev/test), FieldCipher
│   ├── events/          EventEnvelope (envelope.schema.json), DomainEvent, EventPublisher (outbox),
│   │                    EventInbox (idempotência de consumidores)
│   ├── errors/          ProblemException + mappers RFC 9457
│   ├── logging/         PiiMasker, PiiLogFilter (quarkus.log.console.filter=pii-mask)
│   ├── pagination/      Cursor opaco, Page { items, next_cursor }
│   └── ids/             Ulid com prefixos
├── sharedkernel/        Cns, Cpf, Cnes, Cbo, Competence, IdentifierHash (HMAC por tenant), Masks
├── audit/               audit_log encadeado por hash (append-only), access_log, @AuditedAccess,
│                        GET /api/v1/audit/access
├── reference/           organization, health_unit, professional, professional_role, care_team,
│                        territory, microarea; upsert por (tenant, cnes); GET/PUT /api/v1/reference/health-units
├── terminology/         terminology.code (global), busca trigram+unaccent,
│                        TerminologyService.isValid; GET /api/v1/terminology/{system}/codes
└── identity/            MPI: citizen + identifiers (hash/enc/masked) + histórico bitemporal +
                         endereço/contato/source_link + merge case/merge + match candidate/evidence +
                         golden record com proveniência; IdentityResolutionService; /api/v1/citizens, /api/v1/mpi
```

Cada módulo de domínio tem `api/` (contratos públicos), `domain/`, `application/` e
`infrastructure/`. O `ArchitectureTest` garante que um módulo só importa `..<outro>.api..` de
outros módulos e que `platform`/`sharedkernel` não conhecem módulos.

Um schema PostgreSQL por módulo (`platform`, `audit`, `reference`, `terminology`, `identity`),
migrações em `src/main/resources/db/migration/V00N__<módulo>.sql`. Todas as tabelas com
`tenant_id` têm RLS (`platform.current_tenant()` ↔ `app.tenant_id`); terminologia é global.

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
