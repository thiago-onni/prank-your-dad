# SUS Nexus — Connectors

Connector SDK e conectores de integração em Java 21 + Quarkus 3.39 + Apache Camel (Camel Quarkus).
Segue `sus-nexus/CONVENTIONS.md` e a seção 7 de `docs/sus-nexus/PLANO_IMPLEMENTACAO.md`.

```text
connectors/
├── pom.xml                  reactor Maven (quarkus-bom + quarkus-camel-bom 3.39.5, fmt-maven-plugin em verify)
├── connector-sdk/           biblioteca: Connector, descriptor, raw zone, ledger, mapping, CoreClient, retry/DLQ, métricas, runtime Camel
├── connector-template/      módulo-exemplo para copiar ao criar um conector novo (README com o passo a passo)
├── connector-terminology/   SIGTAP (largura fixa) + CID-10/CBO/CIAP-2 (CSV) → upsertCodes      porta 8090
├── connector-cnes/          tbEstabelecimento CSV/DBF por competência → upsertHealthUnits        porta 8091
├── connector-pec/           e-SUS APS/PEC (file: CSV exportado | jdbc: réplica somente leitura) porta 8092
└── contracts/ingestion-extensions.openapi.yaml  endpoints de upsert propostos para o core
```

## Build

```bash
cd sus-nexus/connectors
mvn -q verify                      # compila, testa (WireMock, sem Docker/Kafka) e checa Google Java Format
mvn com.spotify.fmt:fmt-maven-plugin:format   # formata
mvn -pl connector-pec quarkus:dev  # dev mode de um conector
docker build -f connector-cnes/src/main/docker/Dockerfile -t sus-nexus/connector-cnes .
```

## Pipeline padrão (ConnectorRuntime)

```text
fonte (file/sql/timer) → direct:connector-ingest (RawMessage)
  → RawMessageStore (bytes + SHA-256 + metadados)   → ledger: received
  → Connector.transform (MappingSet/MappingVersion) → ledger: transformed
  → Connector.validate                               → ledger: validated
  → Connector.publish (CorePublisher → CoreClient)   → ledger: published
erro transitório → Connector.retry (RetryPolicy, backoff exponencial, Camel retryWhile)
erro permanente / retry esgotado → DeadLetterHandler (DeadLetterSink) → ledger: dead_lettered
```

Toda chamada ao core envia `Authorization: Bearer` (static em dev/test, OIDC client credentials em prod),
`X-Tenant-Id`, `X-Correlation-Id`, `X-Purpose-Of-Use: integration_operations` e `Idempotency-Key`
(= SHA-256 de `source_record_id:source_record_version`, ou do conjunto de chaves em lotes).
O `CoreClient` tem `@Retry` + `@CircuitBreaker` (SmallRye Fault Tolerance); 4xx são permanentes (DLQ), 5xx/rede transitórios.

## Configuração comum (`connector.*`)

| Propriedade | Padrão | Descrição |
|---|---|---|
| `connector.tenant-id` | `ibge_3143302` | Tenant (`X-Tenant-Id`) |
| `connector.raw-store.type` / `.dir` / `.bucket` / `.endpoint` | `file` / `data/raw-zone` | Raw zone em arquivo ou S3/MinIO |
| `connector.ledger.type` | `memory` | `memory` ou `jdbc` (requer datasource default; DDL em `db/integration_message.sql`) |
| `connector.retry.max-attempts` / `.initial-backoff` / `.multiplier` / `.max-backoff` | 5 / PT1S / 2.0 / PT5M | Política de retry do pipeline |
| `connector.dlq.type` / `.dir` | `file` / `data/dlq` | Dead letters em JSON ou log |
| `connector.core.auth.mode` / `.static-token` | `static` | `oidc` usa `quarkus.oidc-client.*` |
| `connector.core.batch-size` | 500 | Tamanho de lote em upserts |
| `quarkus.rest-client.core.url` | `http://localhost:8080` | URL do core |
| `quarkus.log.console.filter=pii-mask` | — | Filtro que mascara CPF/CNS em qualquer log |

Métricas (Prometheus em `/q/metrics`, tag `connector_id`): `connector_messages_received_total`,
`connector_messages_processed_total`, `connector_messages_failed_total`, `connector_processing_latency_ms`,
`integration_reconciliation_gap_total`. Health: `/q/health/ready` agrega `Connector.healthCheck()`.

## Testes

Sem serviços externos: componentes Camel `direct`/`file`, ledger em memória, raw zone/DLQ em `target/`,
H2 em memória para o ledger JDBC e WireMock (porta dinâmica via `QuarkusTestResourceLifecycleManager`) no lugar do core.
