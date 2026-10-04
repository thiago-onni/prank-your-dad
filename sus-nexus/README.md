# SUS Nexus — Barramento Municipal de Saúde Digital

Plataforma municipal que integra, normaliza, identifica, correlaciona, coordena, governa e disponibiliza os dados da rede SUS local — sem substituir os sistemas legais de registro (PEC, SISREG/e-SUS Regulação, HIS, SIA/SIH).

- Plano de implementação: [`../docs/sus-nexus/PLANO_IMPLEMENTACAO.md`](../docs/sus-nexus/PLANO_IMPLEMENTACAO.md)
- Convenções de engenharia (fonte única): [`CONVENTIONS.md`](CONVENTIONS.md)
- Decisões de arquitetura: [`docs/adr/`](docs/adr/)

## Componentes

| Diretório | Componente | Stack | Como verificar |
|---|---|---|---|
| [`contracts/`](contracts/) | Envelope de eventos, JSON Schemas, tópicos Kafka, OpenAPI do core | JSON Schema 2020-12, OpenAPI 3.1 | `pnpm install && pnpm validate` |
| [`core-municipal/`](core-municipal/) | Monólito modular: MPI, referência, terminologia, auditoria, outbox/inbox | Java 21, Quarkus 3, PostgreSQL 16 | `mvn -q verify` |
| [`fhir-gateway/`](fhir-gateway/) | FHIR R4 Gateway próprio (sem servidor HAPI) | Java 21, Quarkus 3, `org.hl7.fhir.r4` como biblioteca | `mvn -q verify` |
| [`connectors/`](connectors/) | Connector SDK + conectores (terminologia, CNES, PEC) | Java 21, Quarkus 3, Apache Camel | `mvn -q verify` |
| [`web/`](web/) | Design system, componentes de domínio, api-client, auth BFF, app shell | Next.js 15, React 19, TypeScript, Turborepo | `pnpm install && pnpm build && pnpm test` |
| [`policies/`](policies/) | Autorização RBAC+ABAC, agentes, FHIR scopes, exportação | OPA / Rego v1 | `make test` |
| [`ai-service/`](ai-service/) | Agentes com autonomia graduada, kill switch, avaliações | Python 3.11, FastAPI, LangGraph, Pydantic v2 | `pytest` |
| [`platform/`](platform/) | docker-compose local, Helm, Argo CD, Terraform, segurança, observabilidade, DR | Kubernetes, Helm, GitOps | `helm lint`, `kubeconform` |
| [`data/`](data/) | Camada analítica (Fase 4): lakehouse Iceberg (Kafka Connect Iceberg sink), dbt bronze→silver→gold pseudonimizado, indicadores com supressão n<5, Trino, Metabase "Sala de Situação", OpenMetadata | dbt (Trino / DuckDB no CI), Apache Iceberg, Trino | `dbt build --profile duckdb --target ci` (ver [`data/README.md`](data/README.md)) |

## Fluxo de dados (padrão único de escrita)

```text
fonte → conector (raw zone + ledger + mapping) → sus.ingest.* / API do core
     → core: resolução de identidade (MPI) → persistência + event_outbox (mesma transação)
     → Debezium Outbox Router → sus.<domínio>.<entidade>.vN
     → projeções: FHIR Gateway · timeline · BI · workflows Temporal · agentes
```

Conectores **nunca** escrevem diretamente em tópicos de domínio nem no banco do core. Agentes de IA **nunca** acessam bancos; agem apenas por ferramentas autorizadas via OPA.

## Subir localmente

```bash
cd platform/compose
cp .env.example .env
docker compose --profile core --profile observability up -d
```

Portas e credenciais de desenvolvimento em [`platform/README.md`](platform/README.md) e [`CONVENTIONS.md`](CONVENTIONS.md).

## Status

Fase 1 (fundação) em construção: cidadão único, referência/terminologia, auditoria encadeada, outbox/inbox, console de integrações, cadastro mestre, timeline mínima, FHIR-1 (Patient, Organization, Location, Practitioner, PractitionerRole), políticas OPA, agentes assistivos com kill switch.
