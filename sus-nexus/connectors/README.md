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
├── connector-ris/           Imagem: HL7 v2 ORM/ORU + metadados DICOM (JSON/CSV) → exames (Fase 3) porta 8097, MLLP 2577
├── connector-rnds/          SAÍDA para a RNDS (Fase 4): eventos Kafka → FHIR Gateway → Bundle por    porta 8098
│                            modelo habilitado → RNDS (mTLS ICP-Brasil); rnds_submission + reconciliação
└── connector-sia/           Produção BPA-C/BPA-I/APAC/AIH (CSV do sistema de origem) e retornos SIA/SIH porta 8099
                             (CSV/TXT, layout A CONFIRMAR) → Kafka sus.ingest.production.v1
```

O `connector-rnds` é o único conector de **saída**: a "fonte" é o barramento (`sus.exam.result.v1`,
`sus.hospital.discharge.v1`) e o destino é a RNDS. Reusa o mesmo pipeline do SDK (raw zone = envelope do
evento, `transform` = leitura no fhir-gateway + montagem do Bundle via `org.hl7.fhir.r4`, `validate` =
pré-validação declarativa do YAML do modelo, `publish` = POST na RNDS em vez do core) e registra o resultado
no core pelo ledger espelho (`POST /api/v1/integration/messages`), heartbeat e reconciliação. Endereços,
cabeçalhos e perfis da RNDS são exemplos **a confirmar na homologação** — ver `connector-rnds/README.md`.

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
`hospital_movement`, `hospital_discharge`, `production_record`, `production_outcome` (os dois últimos publicados pelo
`connector-sia` no tópico de ingestão `sus.ingest.production.v1`, consumido pelo core em `ingest-production-in`;
envelopes via `sdk.events.IngestEnvelopes`, com `event_id` determinístico).

### Ledger espelho, heartbeat e reconciliação no core (`sdk.core.CoreIntegrationMirror`)

`CoreIntegrationApi` (`POST /api/v1/integration/messages`, `/connectors/{id}/heartbeat`, `/reconciliation`) e a
fachada `CoreIntegrationMirror` (melhor esforço, sem PII) ficam no SDK. `connector.core.mirror.enabled=true` liga as
chamadas; `connector.core.mirror.pipeline-messages=true` faz o `ConnectorRuntime` espelhar cada mensagem ao fim do
pipeline (publicada ou DLQ) — pré-requisito para o core oferecer reprocessamento (`connector-sia`). O
`connector-rnds` espelha por conta própria (dispatcher) e deixa `pipeline-messages=false`.

### Reprocessamento (`sus.integration.command.v1`, `sdk.reprocess`)

O core publica `sus.integration.reprocess.requested` (`contracts/events/integration/reprocess.v1.schema.json`,
chave `data.connector_id`). O SDK não depende de Kafka: o conector liga um `@Incoming` de uma linha ao
`ReprocessCommandHandler.handle(envelope)`, que valida o contrato (`suppress_external_effects=true`), filtra
`connector_id` e tenant (`connector.tenant-id`), deduplica por `event_id` e delega ao `Reprocessor`:

- padrão `PipelineReprocessor`: mensagem `failed`/`dead_lettered` → relê o bruto na raw zone (SHA-256 conferido),
  reabre a **mesma** `integration_message` (header `PipelineHeaders.REPROCESS_MESSAGE_ID`, sem nova gravação na raw
  zone, tentativas zeradas) e reexecuta o pipeline (lote com atributo `reprocess=true` → `replay=true` em
  envelopes); `published`/`processed` → nada é reenviado (estado reespelhado); ledger em memória sem a mensagem →
  reconstrói pelo `raw_ref` do comando + metadados da raw zone (`RawMessageStore.describe`);
- `connector-rnds`: `RndsReprocessor` reenvia só submissões `failed` (nunca aceitas pela RNDS).

Métrica `connector_reprocess_total{connector_id,result}` (`reprocessed`, `dead_lettered`, `already_done`,
`not_reprocessable`, `not_found`, `ignored`, `duplicate`, `invalid`, `error`). Consumidores atuais: `connector-rnds`
(canal `rnds-integration-command`) e `connector-sia` (`sia-integration-command`).

## Build

```bash
cd sus-nexus/connectors
mvn -q verify                      # compila, testa (WireMock, sem Docker/Kafka) e checa Google Java Format
mvn com.spotify.fmt:fmt-maven-plugin:format   # formata
mvn -pl connector-pec quarkus:dev  # dev mode de um conector
docker build -f connector-cnes/src/main/docker/Dockerfile -t sus-nexus/connector-cnes .
docker build --build-arg CONNECTOR_MODULE=connector-lis --build-arg PORT=8095 -t sus-nexus/connector-lis .   # Dockerfile raiz genérico
docker build --build-arg CONNECTOR_MODULE=connector-rnds --build-arg PORT=8098 -t sus-nexus/connector-rnds .
docker build --build-arg CONNECTOR_MODULE=connector-sia --build-arg PORT=8099 -t sus-nexus/connector-sia .
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
| `connector.core.mirror.enabled` / `.pipeline-messages` | `false` / `false` | Ledger espelho/heartbeat/reconciliação no core; espelhamento automático de cada mensagem pelo runtime |
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
sumário de alta nunca aparecem no payload enviado ao core. O `connector-rnds` usa um WireMock com porta HTTPS de
certificado de cliente obrigatório (keystores autoassinados de teste) para o serviço de autenticação da RNDS e
Kafka em memória (`smallrye-in-memory`) para os gatilhos e para o comando de reprocessamento. O `connector-sia` usa Kafka
em memória como destino (`sus.ingest.production.v1`) e fixtures sintéticas de produção e retornos SIA/SIH.
