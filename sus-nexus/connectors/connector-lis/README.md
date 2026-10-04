# connector-lis — Laboratório (LIS) via HL7 v2.x (MLLP e arquivos)

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-lis` / 0.1.0 |
| source_system | LIS |
| supported_source_versions | HL7 v2.3, 2.3.1, 2.4, 2.5, 2.5.1 (parser tolerante: estruturas canônicas v2.5 do HAPI, sem validação estrita) |
| supported_protocols | mllp (listener, porta 2575); file-hl7 |
| supported_entities | `exam_order` (ORM^O01) → `POST /api/v1/exams/orders` (upsert por nº do pedido); `exam_result` (ORU^R01) → `POST /api/v1/exams/orders/by-source/LIS/{pedido}/results` |
| authentication_method | NONE (MLLP em rede interna/VPN; TLS termina no proxy) |
| required_network_access | core-municipal:8080; LIS → connector-lis:2575 |
| data_classification | HIGHLY_RESTRICTED |
| polling_or_event_mode | EVENT |
| retry_policy / rate_limit_policy | 5 tentativas backoff exponencial / 1200 msg/min, 4 concorrentes |
| field_mapping_version | `lis-exam-order-1.0.0.yaml`, `lis-exam-result-1.0.0.yaml` |
| test_suite_version | 1.0.0 |
| owner / support_sla | equipe-integracao@sus-nexus / gold (1h, 8h) |

## Fluxo
`mllp://0.0.0.0:2575` (Camel MLLP, `autoAck`) e `file:data/lis/in` (`*.hl7`, várias mensagens por arquivo)
→ `direct:lis-hl7-receive`: normaliza (envelope MLLP, `\n`), parse HAPI (`CanonicalModelClassFactory("2.5")`),
identifica `MSH-9` → `RawMessage` (bruto ER7 completo na raw zone; id = nº do pedido, versão = `MSH-10`)
→ pipeline do SDK (síncrono) → **ACK**.

**ACK (store-and-forward)**: `AA` quando a mensagem foi parseada e persistida (raw zone + ledger); falhas
posteriores (core indisponível, validação) seguem para retry/DLQ e **não** geram NAK, evitando reenvio infinito
pelo LIS. `AR` para tipo não suportado (ex.: ADT); `AE` para mensagem malformada (ACK mínimo montado do MSH bruto).
Métrica `connector_lis_ack_total{type}`.

## Mapeamento
- **PID-3 → citizen_ref**: repetições CX com tipo (CX-5) `lis.pid.cns-identifier-type` (CNS) ou
  `cpf-identifier-type` (CPF); sem tipo, inferência por tamanho (15/11 dígitos, `infer-by-length`); último
  recurso: identificador local (`lis.pid.local-identifier-system`, padrão `HIS`). Nome/nascimento do PID ficam
  só na raw zone (o contrato `CitizenRef` não os aceita; a resolução de identidade é do core).
- **ORM → ExamOrderRegistration**: pedido = ORC-2/OBR-2 (placer) ou ORC-3/OBR-3 (`lis.order.id-source=filler`);
  `exam_code`/`exam_description`/`code_system` = OBR-4 (LN→LOINC, SIGTAP, senão LOCAL); `requested_at` =
  OBR-6 → ORC-9 → MSH-7; `requesting_cnes` = ORC-21 (XON-10/3/1) → ORC-17 → `lis.order.default-requesting-cnes`;
  `requesting_professional_id` = ORC-12/OBR-16; `regulation_source_record_id` = ORC-4; `priority` = OBR-5/TQ-6
  (S/A→urgent, P→priority, R→routine); `category` = `lis.order.category` (laboratory).
  **Status**: ORC-1 CA/OC/DC → `cancelled`; senão ORC-5 SC→`scheduled`, IP→`collected`, CM→`performed`;
  senão `requested`. Mudanças de status (ORM com ORC-1 `SC`) são publicadas como **upsert** do pedido com o novo
  status (o core só expõe `/exams/orders/{id}/status` por id interno).
- **ORU → ExamResultRegistration** (by-source pelo nº do pedido): `status` = OBR-25 (F→final, P→preliminary,
  C→amended, X→cancelled; R/A/I/S/O→preliminary); `reported_at` = OBR-22 → OBR-7 → MSH-7; `performer_cnes` =
  MSH-4 (7 dígitos) → `lis.order.default-performer-cnes`.
  **`observations` somente de OBX-2 `NM`/`SN`** (valor numérico, vírgula decimal aceita; SN usa num1), com
  `unit` (OBX-6) e `abnormal` por OBX-8 (A/H/L/HH/LL/AA/>/<). `TX`/`FT`/`ST`/`ED`... **nunca vão ao core**
  (métrica `connector_lis_obx_skipped_total{value_type}`): o resultado recebe `document_ref =
  raw://connector-lis/sha256/<sha256 do bruto>`, `document_sha256` e `document_content_type =
  x-application/hl7-v2+er7`, apontando para a raw zone.
  **`critical=true`** quando algum OBX-8 ∈ `lis.critical.flags` (HH, LL, AA) ou quando o campo MSH configurado
  (`lis.critical.msh-field`, ex. 21) contém `lis.critical.msh-value`.

## Plano B
Arquivos `.hl7` exportados pelo LIS (modo file, sempre ativo) ou, para laboratórios sem HL7, planilhas de
resultados a tratar em conector próprio. Se o LIS não enviar ORM, o ORU ainda é aceito: o core responderá
404 no by-source (pedido inexistente) e a mensagem irá à DLQ para reprocessamento após cadastro do pedido —
alternativa: publicar o pedido a partir do próprio ORU (`lis.order.create-from-oru`, pendência).

## Pendências de homologação
- Versão HL7 e perfil do LIS (campos realmente usados em PID-3, ORC-21, OBR-25, OBX-8), charset (ISO-8859-1?).
- Convenção do CNES solicitante/executante (ORC-21, MSH-4 ou fixo por instalação).
- Catálogo de exames: OBR-4 em SIGTAP ou código local (`code_system=LOCAL`) — mapeamento para SIGTAP/LOINC no core.
- Segurança de transporte do MLLP (VPN/TLS por sidecar) e porta liberada no firewall.
- Core: endpoint `POST /exams/orders/by-source/{system}/{sourceRecordId}/results` (contrato atualizado; implementação em andamento).

## Operação
Porta HTTP 8095, MLLP 2575. `mvn -pl connector-lis quarkus:dev`. Variáveis: `LIS_MLLP_ENABLED`, `LIS_MLLP_PORT`,
`LIS_CHARSET`, `LIS_ZONE`, `LIS_INPUT_DIR`, `LIS_PID_CNS_TYPE`, `LIS_PID_CPF_TYPE`, `LIS_ORDER_ID_SOURCE`,
`LIS_DEFAULT_REQUESTING_CNES`, `LIS_DEFAULT_PERFORMER_CNES`, `LIS_CRITICAL_FLAGS`, `LIS_CRITICAL_MSH_FIELD`, `CORE_URL`, `TENANT_ID`.
Logs com `pii-mask` (CNS/CPF do PID mascarados); DLQ para HL7 inválido, pedido sem identificador de cidadão ou
status fora do domínio. Testes: `src/test/resources/hl7/*.hl7` (CNS fictícios válidos), ACK AA/AE/AR, rejeição de OBX texto, WireMock.
