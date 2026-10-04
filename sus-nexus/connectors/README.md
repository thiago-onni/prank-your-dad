# SUS Nexus — Connectors

Connector SDK e conectores de integração em Java 21 + Quarkus 3.39 + Apache Camel (Camel Quarkus).
Segue `sus-nexus/CONVENTIONS.md` e a seção 7 de `docs/sus-nexus/PLANO_IMPLEMENTACAO.md`.

```text
connectors/
├── pom.xml                  reactor Maven (quarkus-bom + quarkus-camel-bom 3.39.5, fmt-maven-plugin em verify)
├── Dockerfile               build genérico de qualquer módulo (--build-arg CONNECTOR_MODULE, PORT)
├── connector-sdk/           biblioteca: Connector, descriptor, raw zone, ledger, mapping, CoreClient, retry/DLQ, métricas,
│                            runtime Camel e pacote `hl7` (HAPI: parser tolerante, campos, ACK, datas, Hl7Receiver)
├── connector-template/      módulo-exemplo para copiar ao criar um conector novo (README com o passo a passo)
├── connector-terminology/   SIGTAP (largura fixa) + CID-10/CBO/CIAP-2 (CSV) → upsertCodes      porta 8090
├── connector-cnes/          tbEstabelecimento CSV/DBF por competência → upsertHealthUnits        porta 8091
├── connector-pec/           e-SUS APS/PEC (file: CSV exportado | jdbc: réplica somente leitura) porta 8092
├── connector-sisreg/        SISREG: exportações CSV/XLSX de solicitações e oferta → regulação    porta 8093
├── connector-esus-regulacao/ e-SUS Regulação: API OAuth2 paginada ou export JSON/CSV → regulação porta 8094
├── connector-lis/           Laboratório: HL7 v2 ORM/ORU via MLLP ou .hl7 → exames (Fase 2)      porta 8095, MLLP 2575
├── connector-his/           Hospital (borda): HL7 v2 ADT A01..A13 → episódios/altas (Fase 3)    porta 8096, MLLP 2576
└── connector-ris/           Imagem: HL7 v2 ORM/ORU + metadados DICOM (JSON/CSV) → exames (Fase 3) porta 8097, MLLP 2577
```

Os endpoints de ingestão que os conectores chamam (`POST /api/v1/reference/health-units/upsert`,
`POST /api/v1/terminology/{system}/codes/upsert`, `POST /api/v1/regulation/requests`, `POST /api/v1/regulation/capacity`,
`POST /api/v1/exams/orders`, `POST /api/v1/exams/orders/by-source/{system}/{id}/results`,
`POST /api/v1/hospital/episodes`, `POST /api/v1/hospital/episodes/by-source/{system}/{id}/discharge`,
`POST /api/v1/integration/messages`, `POST /api/v1/integration/connectors/{id}/heartbeat`,
`POST /api/v1/integration/reconciliation`) fazem parte do contrato do core:
`sus-nexus/contracts/openapi/core-municipal.yaml` (tags `reference`, `terminology`, `integration`, `regulation`,
`exams`, `hospital`). Nos tipos "by-source" o `CorePublisher` lê o alvo em `target_ref` `{system, source_record_id}`
do payload (removido antes do envio). Tipos canônicos (`CanonicalBatch`): `citizen`, `appointment`, `health_unit`,
`code`, `regulation_request`, `regulation_status`, `provider_capacity`, `exam_order`, `exam_result`,
`hospital_movement`, `hospital_discharge`. Comandos de reprocessamento chegam pelo tópico `sus.integration.command.v1`
(`contracts/events/integration/reprocess.v1.schema.json`).

## Build

```bash
cd sus-nexus/connectors
mvn -q verify                      # compila, testa (WireMock, sem Docker/Kafka) e checa Google Java Format
mvn com.spotify.fmt:fmt-maven-plugin:format   # formata
mvn -pl connector-pec quarkus:dev  # dev mode de um conector
docker build -f connector-cnes/src/main/docker/Dockerfile -t sus-nexus/connector-cnes .
docker build --build-arg CONNECTOR_MODULE=connector-lis --build-arg PORT=8095 -t sus-nexus/connector-lis .   # Dockerfile raiz genérico
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

## Mapeamento YAML (MappingEngine)

Transformações: `trim`, `upper`, `lower`, `unaccent`, `digits`, `blank_to_null`, `to_integer`, `to_decimal`,
`{date: {from: "p1|p2", to: yyyy-MM-dd | iso-datetime, zone}}` (vários padrões; data sem hora vira meia-noite local;
ISO com offset passa direto), `{year_month: {from: "MM/yyyy|yyyyMM"}}` (competência), `{regex: {pattern, replacement}}`,
`{lookup: {table, default, strict}}`, `{default: v}`, `{substring: {start, end}}`, `{to_boolean: {true_values}}`.
Fontes JSON: `br.gov.sus.nexus.connectors.sdk.parse.JsonFlattener` achata um `JsonNode` em chaves `a.b[0].c`
(+ `lista.length`), permitindo mapear respostas de API com os mesmos caminhos.

## HL7 v2 compartilhado (`sdk.hl7`)

`Hl7Parser` (HAPI, `CanonicalModelClassFactory("2.5")`, sem validação estrita; normaliza envelope MLLP e `\n`;
`splitMessages` para arquivos com várias mensagens), `Hl7Fields` (modelo plano `msh.*`, `pid.*`, `evn.*`, `pv1.*`,
DG1/PR1, segmentos Z `zxx.N[.C]`, grupos ORC/OBR/OBX de ORM/ORU; timestamps já em ISO-8601 — são as fontes dos YAML),
`Hl7Acks` (ACK/NAK a partir da mensagem ou do MSH bruto) e `Hl7Dates`. `Hl7Receiver` implementa a recepção
store-and-forward comum a LIS/HIS/RIS: parse → `RawMessage` → pipeline síncrono → `AA` (persistido) / `AR` (tipo não
suportado) / `AE` (malformado) nos headers MLLP do Camel; cada conector só informa o classificador `MSH-9 →
entity_type` e o resolvedor do id de origem (nº do pedido, nº do atendimento). Conectores de borda (HIS/RIS)
usam `connector.edge=true` (descriptor MTLS, healthcheck com topologia) e saem apenas para o barramento.

## Testes

Sem serviços externos: componentes Camel `direct`/`file`, ledger em memória, raw zone/DLQ em `target/`,
H2 em memória para o ledger JDBC e WireMock (porta dinâmica via `QuarkusTestResourceLifecycleManager`) no lugar do core
(e da API do e-SUS Regulação). LIS/HIS/RIS são testados pelos `direct:*-receive` (sem socket MLLP) e por arquivos
`.hl7` com várias mensagens; o RIS também por exports DICOM JSON/CSV. XLSX de teste é gerado com POI em tempo de teste.
Amostras HL7/DICOM usam dados fictícios (CNS/CPF válidos de teste) e os testes afirmam que nome, nascimento, laudo e
sumário de alta nunca aparecem no payload enviado ao core.
