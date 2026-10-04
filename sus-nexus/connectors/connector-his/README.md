# connector-his — Hospital (HIS) via HL7 v2.x ADT (MLLP e arquivos) — conector de borda

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-his` / 0.1.0 |
| source_system | HIS |
| supported_source_versions | HL7 v2.3, 2.3.1, 2.4, 2.5, 2.5.1 (parser tolerante do SDK: estruturas canônicas v2.5 do HAPI); perfis `generic` (completo), `tasy`, `mv`, `aghuse` (esqueletos a homologar) |
| supported_protocols | mllp (listener, porta 2576); file-hl7 |
| supported_entities | `hospital_movement` (ADT A01/A02/A04/A06/A07/A08/A11/A13) → `POST /api/v1/hospital/episodes` (upsert do episódio por nº do atendimento); `hospital_discharge` (ADT A03) → `POST /api/v1/hospital/episodes/by-source/HIS/{atendimento}/discharge` |
| authentication_method | MTLS para o barramento quando `connector.edge=true` (padrão); NONE no MLLP (rede do hospital) |
| required_network_access | saída única: core-municipal:8080 (mTLS); entrada: HIS → connector-his:2576 (MLLP) |
| data_classification | HIGHLY_RESTRICTED |
| polling_or_event_mode | EVENT |
| retry_policy / rate_limit_policy | 5 tentativas backoff exponencial / 1200 msg/min, 4 concorrentes |
| field_mapping_version | `mappings/<vendor>/his-adt-1.0.0.yaml`, `mappings/<vendor>/his-adt-discharge-1.0.0.yaml` |
| test_suite_version | 1.0.0 |
| owner / support_sla | equipe-integracao@sus-nexus / gold (1h, 8h) |

## Conector de borda
Roda **dentro da rede do hospital** (VM/contêiner ao lado do HIS). Só recebe MLLP do HIS e só sai para o
barramento municipal (`CORE_URL`, mTLS + OIDC client credentials em `%prod`); não expõe nada para a internet.
`connector.edge=true` muda o descriptor (MTLS), o healthcheck (`edge`, `outbound`) e documenta a topologia; a
raw zone e a DLQ ficam no volume local `/app/data` (ou S3/MinIO do barramento, `RAW_STORE_TYPE=s3`). O HL7 bruto
(com nome, nascimento, sumário de alta) **nunca sai do hospital em claro**: só o payload canônico, sem texto livre.

## Fluxo
`mllp://0.0.0.0:2576` (Camel MLLP, `autoAck`) e `file:data/his/in` (`*.hl7`, várias mensagens por arquivo)
→ `direct:his-adt-receive` (`Hl7Receiver` do SDK): normaliza, parse HAPI, `MSH-9` → `entity_type`, `PV1-19` → id
do registro → `RawMessage` (ER7 completo na raw zone; versão = `MSH-10`) → pipeline do SDK (síncrono) → **ACK**.

**ACK (store-and-forward)**: `AA` quando a mensagem foi parseada e persistida (raw zone + ledger); falhas
posteriores (core indisponível, validação) seguem para retry/DLQ e **não** geram NAK, para o HIS não reenviar
indefinidamente. `AR` para gatilho não suportado (ex.: A05, A28); `AE` para mensagem malformada.
Métricas: `connector_his_adt_total{trigger}` (mensagens aceitas, AA) e `connector_his_ack_total{type}`.

## Mapeamento (perfil `generic`)
Modelo plano do SDK (`msh.*`, `pid.*`, `evn.*`, `pv1.*`, DG1/PR1, segmentos `z*.N`) + campos `adt.*` derivados
pelo `HisConnector.flat`; as regras de código vivem nos YAML.

| Gatilho | movement | Observações |
|---|---|---|
| A01 | `admit` | `episode_class` por PV1-2 (I→inpatient, E→emergency, O→observation, B→inpatient, R/D→day_hospital) |
| A02 | `transfer` / `bed_change` | `bed_change` quando PV1-6-1 (origem) == PV1-3-1 (mesma enfermaria) |
| A03 | — (`hospital_discharge`) | by-source; ver abaixo |
| A04 | `admit` | registro de urgência: classe `E` salvo PV1-2 = O (`his.hospital.a04-patient-class`) |
| A06 / A07 | `admit` | mudança de classe (ambulatorial↔internação); `reason` informativo |
| A08 | `admit` | reaplica os dados do atendimento (upsert) |
| A11 | `cancel` | cancela a admissão |
| A13 | `admit` | cancela a alta: paciente readmitido no mesmo atendimento |

- **PID-3 → citizen_ref**: CX-5 `CNS`/`CPF` (`his.pid.*-identifier-type`); sem tipo, inferência por tamanho
  (15/11 dígitos); último recurso: identificador local do HIS (`his.pid.local-identifier-system`). Nome e
  nascimento ficam só na raw zone.
- **A01 → HospitalMovementRegistration**: `source.source_record_id` = PV1-19 → PID-18 → MSH-10; `hospital_cnes`
  = `his.hospital.cnes` → MSH-4 (7 dígitos) → PV1-3-4; `occurred_at` = EVN-6 → EVN-2 → PV1-44 → MSH-7;
  `ward`/`bed` = PV1-3-1 / PV1-3-3 (→ PV1-3-2); `attending_professional_id` = PV1-7-1; `admission_source` =
  PV1-14 (tabela 0023: 7→emergency, 4/5/6→transfer, 1/2/3→elective, R/REG→regulation, senão other);
  `regulation_source_record_id` = PV1-5 (`his.regulation.source`); `principal_diagnosis_cid` = DG1 com DG1-6 =
  `A` (senão o primeiro), CID-10 normalizado sem ponto (`F20.0`→`F200`) — a classificação de sensibilidade é do
  core; `aih_number` opcional: `his.aih.source` = `none` | `pv1-50` | `zai` (campo `his.aih.zai-field`).
- **A03 → DischargeRegistration** (by-source pelo nº do atendimento): `discharged_at` = PV1-45 → EVN-6 → EVN-2 →
  MSH-7; `disposition` = PV1-36 (tabela 0112: 01→home, 06→home_with_care, 02/03/04/05→transfer,
  07→against_advice, 20/40/41/42→deceased, senão other; variantes sem zero à esquerda aceitas);
  `principal_diagnosis_cid` = DG1 tipo `F` (senão o primeiro); `procedures_count` = nº de PR1;
  quando o A03 traz OBX/NTE (sumário), `summary_document_ref = raw://connector-his/sha256/<sha>` +
  `summary_document_sha256` — **o texto do sumário nunca vai ao core**. `followup_plan_present`,
  `followup_due_days` e `care_lines` não existem no ADT padrão: exemplos de segmento Z comentados no YAML.

## Perfis de fornecedor
`his.vendor=generic|tasy|mv|aghuse` troca apenas o diretório `mappings/<vendor>/`. Só `generic` está completo;
`tasy`, `mv` e `aghuse` são cópias com as diferenças esperadas anotadas no cabeçalho (PV1-19, PV1-3, PID-3,
PV1-36, AIH) para ajustar na homologação sem alterar Java.

## Plano B
Arquivos `.hl7` exportados pelo HIS (modo file, sempre ativo). Para hospitais sem HL7, extração de
internações/altas da base do HIS (CSV/JDBC) em conector próprio reutilizando os mesmos YAML (`adt.*`).
A03 sem A01 anterior: o core responde 404 no by-source → DLQ; reprocessar após o A01/A08.

## Pendências de homologação
- Versão HL7 e perfil do HIS (campos reais em PV1-2/3/14/19/36/45, DG1-6, PR1) por hospital; charset.
- CNES do hospital (MSH-4 ou fixo por instalação); nº da AIH (PV1-50, ZAI ou inexistente no ADT).
- Plano de acompanhamento pós-alta (segmento Z ou outra fonte) e linhas de cuidado.
- PKI/mTLS do barramento (keystore/truststore em `%prod.quarkus.rest-client.core.*`), VPN e porta MLLP no firewall.
- Core: endpoints `POST /hospital/episodes` e `.../by-source/{system}/{id}/discharge` (contrato publicado; implementação em andamento).

## Operação
Porta HTTP 8096, MLLP 2576. `mvn -pl connector-his quarkus:dev`. Variáveis: `HIS_VENDOR`, `HIS_MLLP_ENABLED`,
`HIS_MLLP_PORT`, `HIS_CHARSET`, `HIS_ZONE`, `HIS_INPUT_DIR`, `HIS_HOSPITAL_CNES`, `HIS_A04_PATIENT_CLASS`,
`HIS_AIH_SOURCE`, `HIS_AIH_ZAI_FIELD`, `HIS_REGULATION_SOURCE`, `HIS_DG1_ADMIT_TYPE`, `HIS_DG1_DISCHARGE_TYPE`,
`HIS_PID_CNS_TYPE`, `HIS_PID_CPF_TYPE`, `CONNECTOR_EDGE`, `CORE_URL`, `TENANT_ID`, `CORE_MTLS_*`.
Logs com `pii-mask`; DLQ para HL7 inválido, PID sem identificador, código fora do domínio ou CNES inválido.
Testes (`src/test/resources/hl7/*.hl7`, dados fictícios): A01/A02/A03/A04/A06/A07/A08/A11/A13, lookup de
disposition, validação, ACK AA/AE/AR + métrica por gatilho, ponta a ponta A01+A03 via WireMock, arquivo com
várias mensagens. Build: `docker build --build-arg CONNECTOR_MODULE=connector-his --build-arg PORT=8096 .`
