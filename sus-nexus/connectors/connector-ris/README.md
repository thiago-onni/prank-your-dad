# connector-ris — Imagem (RIS/PACS) via HL7 v2.x e metadados DICOM (nunca a imagem)

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-ris` / 0.1.0 |
| source_system | RIS |
| supported_source_versions | HL7 v2.3–2.5.1 (parser tolerante do SDK); export de metadados DICOM (JSON/CSV) |
| supported_protocols | mllp (listener, porta 2577); file-hl7; file-dicom-metadata |
| supported_entities | `exam_order` (ORM^O01) → `POST /api/v1/exams/orders` (categoria `imaging`); `exam_result` (ORU^R01 laudo; metadados DICOM) → `POST /api/v1/exams/orders/by-source/RIS/{accession}/results` |
| authentication_method | NONE no MLLP (rede do serviço); mTLS/OIDC para o barramento (`connector.edge=true`) |
| required_network_access | core-municipal:8080; RIS → connector-ris:2577 (MLLP); PACS → volume `data/ris/dicom` |
| data_classification | HIGHLY_RESTRICTED |
| polling_or_event_mode | HYBRID (MLLP por evento; arquivos por polling) |
| retry_policy / rate_limit_policy | 5 tentativas backoff exponencial / 1200 msg/min, 4 concorrentes |
| field_mapping_version | `ris-exam-order-1.0.0.yaml`, `ris-exam-result-1.0.0.yaml`, `ris-dicom-study-1.0.0.yaml`; catálogo `catalogs/ris-exam-catalog.yaml` 1.0.0 |
| test_suite_version | 1.0.0 |
| owner / support_sla | equipe-integracao@sus-nexus / gold (1h, 8h) |

## Fluxo
- `mllp://0.0.0.0:2577` e `file:data/ris/in` (`*.hl7`) → `direct:ris-hl7-receive` (`Hl7Receiver` do SDK:
  parse → `RawMessage` (id = Accession Number = ORC-2/OBR-2 ou filler) → pipeline → ACK store-and-forward
  `AA`/`AR`/`AE`, métricas `connector_ris_ack_total{type}` e `connector_ris_messages_total{kind}`).
- `file:data/ris/dicom` (`*.json` | `*.csv` exportados do PACS/worklist) → `direct:ris-dicom-receive` →
  `RawMessage` (id = nome do arquivo) → pipeline: **um `exam_result` por estudo**, by-source pelo Accession Number.

## Mapeamento
- **PID-3 → citizen_ref**: CNS → CPF → identificador local (mesma regra do LIS/HIS); nome/nascimento só na raw zone.
- **ORM → ExamOrderRegistration**: `category=imaging`; `exam_code`/`code_system` a partir de OBR-4:
  coding system `SIGTAP` com 10 dígitos → `SIGTAP`; senão código local → **catálogo YAML** (`ris.catalog.path`,
  `codes: {RX-TORAX: "0204030153"}`) → `SIGTAP`; senão `LN` → `LOINC`; senão `LOCAL` (o core mapeia depois).
  `requested_at` = OBR-6 → ORC-9 → MSH-7; `requesting_cnes` = ORC-21 → ORC-17 → padrão; `priority` = OBR-5/TQ-6;
  status por ORC-1 (CA/OC/DC → cancelled) ou ORC-5 (SC→scheduled, IP/A/CM→performed), senão `requested`.
- **ORU (laudo) → ExamResultRegistration** (by-source): `status` = OBR-25; `reported_at` = OBR-22 → OBR-7 → MSH-7;
  `performer_cnes` = MSH-4 → padrão. **Nenhum texto do laudo e nenhuma observação vão ao core**
  (`observations=[]`, métrica `connector_ris_report_text_skipped_total{value_type}`): o resultado leva
  `document_ref = raw://connector-ris/sha256/<sha>`, `document_sha256`, `document_content_type =
  x-application/hl7-v2+er7`. `critical=true` quando algum OBX-8 ∈ `ris.critical.flags` (HH, LL, AA, C), quando
  OBR-13 (ou OBR-5, `ris.critical.obr-field`) contém `ris.critical.obr-value` (CRITICO) ou pelo marcador MSH.
- **Metadados DICOM → ExamResultRegistration** (by-source): linhas planas `dicom.*` (nomes normalizados:
  `StudyInstanceUID`, `Study Instance UID`, `study_instance_uid` ou tag `0020000D` são a mesma chave);
  `document_content_type = application/dicom-study-ref`, `document_ref = dicom://<AE>/<StudyInstanceUID>`
  (AE = coluna `AETitle` ou `ris.dicom.ae-title`), `document_sha256 = SHA-256(StudyInstanceUID)`,
  `reported_at` = StudyDate+StudyTime, `status` = `ris.dicom.status` (final), `source.source_record_version` =
  StudyInstanceUID. Estudos sem UID/Accession são ignorados (log). Modality, séries, instâncias e nome do
  paciente ficam só na raw zone. **A imagem (pixel data) nunca é lida nem copiada**: o core só guarda a referência
  para o visualizador do PACS.

## Plano B
Sem HL7 no RIS: só o export DICOM (worklist/PACS) já publica o resultado como referência ao estudo; o pedido pode
vir do e-SUS/regulação. ORU/estudo sem pedido correspondente → 404 no by-source → DLQ, reprocessar após o ORM.

## Pendências de homologação
- Catálogo real código local → SIGTAP do serviço (`RIS_CATALOG_PATH=file:/app/config/ris-exam-catalog.yaml`).
- Convenção do Accession Number (placer × filler) entre RIS, PACS e worklist; charset do RIS.
- Formato do export do PACS (JSON/CSV, campos, AE Title) e periodicidade; integração DICOMweb/QIDO-RS futura.
- Marcador de laudo crítico usado pelo RIS (OBX-8, OBR-13 ou MSH).
- Core: endpoint by-source de resultados e aceitação de `document_content_type=application/dicom-study-ref`.

## Operação
Porta HTTP 8097, MLLP 2577. `mvn -pl connector-ris quarkus:dev`. Variáveis: `RIS_MLLP_ENABLED`, `RIS_MLLP_PORT`,
`RIS_CHARSET`, `RIS_ZONE`, `RIS_INPUT_DIR`, `RIS_DICOM_ENABLED`, `RIS_DICOM_INPUT_DIR`, `RIS_DICOM_AE_TITLE`,
`RIS_DICOM_ACCESSION_FIELD`, `RIS_CATALOG_PATH`, `RIS_ORDER_ID_SOURCE`, `RIS_DEFAULT_REQUESTING_CNES`,
`RIS_DEFAULT_PERFORMER_CNES`, `RIS_CRITICAL_FLAGS`, `RIS_CRITICAL_OBR_FIELD`, `RIS_CRITICAL_OBR_VALUE`,
`CONNECTOR_EDGE`, `CORE_URL`, `TENANT_ID`. Logs com `pii-mask`.
Testes (WireMock, dados fictícios): descriptor/catálogo, ORM → SIGTAP do catálogo (+ SIGTAP direto/LOINC/LOCAL),
ORU sem texto + crítico por OBX-8/OBR-13, metadados DICOM JSON (tags hex, AE padrão, estudo sem UID ignorado),
ACK AA/AE/AR, ponta a ponta ORM+ORU, arquivo CSV DICOM ponta a ponta.
Build: `docker build --build-arg CONNECTOR_MODULE=connector-ris --build-arg PORT=8097 .`
