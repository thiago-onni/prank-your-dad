# connector-rnds — envio à Rede Nacional de Dados em Saúde (Fase 4, PLANO §6.5)

Conector de **saída**: consome eventos já normalizados no barramento, lê os recursos FHIR R4 no
`fhir-gateway` municipal, monta o `Bundle` do **modelo de informação habilitado** para o município e o
envia à RNDS com o certificado digital ICP-Brasil (e-CNPJ/e-CPF) via mTLS. Não tem MLLP nem fonte de arquivo.

> **Situação (2026-10-04):** endereços, cabeçalhos, token, formato do Bundle do **Resultado de Exame
> Laboratorial (REL)**, substituição e códigos de resposta foram **conferidos com as fontes oficiais**
> (ver [Fontes](#fontes-oficiais-consultadas) e [checklist](#itens-a-validar-na-homologação)). Continuam
> pendentes: versão vigente dos perfis do REL (1.x × 2.0), REL para exames que não sejam COVID-19, todo o
> **Sumário de Alta** (modelo computacional não publicado) e o que depende do credenciamento do município.
> Tudo segue parametrizável (`application.properties` + YAML versionado).

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-rnds` / 0.1.0 |
| source_system | `RNDS` (destino) |
| supported_entities | `rnds_resultado_exame`, `rnds_sumario_alta` (um por modelo configurado em `rnds.models.*`) |
| supported_protocols | Kafka (`sus.exam.result.v1`, `sus.hospital.discharge.v1`); HTTPS FHIR R4 no fhir-gateway (OAuth2 client credentials, `system/*.read`); HTTPS FHIR R4 na RNDS (token via mTLS ICP-Brasil) |
| authentication_method | `MTLS` (certificado e-CNPJ/e-CPF A1, PKCS#12) → token JWT de 30 min |
| required_network_access | Kafka, fhir-gateway, core-municipal, `ehr-auth[-hmg].saude.gov.br` e `ehr-services.hmg.saude.gov.br` / `<uf>-ehr-services.saude.gov.br` (saída internet) |
| data_classification | HIGHLY_RESTRICTED |
| polling_or_event_mode | EVENT |
| retry_policy | `connector.retry.*` (padrão 5 tentativas, 5 s ×2, máx. 10 min) só para 5xx/408/429/401/rede; demais 4xx nunca repetem |
| field_mapping_version | `mappings/rnds-resultado-exame-1.1.0.yaml`, `mappings/rnds-sumario-alta-1.1.0.yaml` |
| porta HTTP | 8098 (`/q/health/ready`, `/q/metrics`) |

## Fluxo

```text
Kafka sus.exam.result.v1 (action=available, result_status final|amended)  ─┐  RndsEventConsumer
Kafka sus.hospital.discharge.v1 (action=completed)                         ─┘  (@Blocking, ack após o pipeline)
  → RndsDispatcher: aplicável? modelo habilitado (rnds.models.<m>.enabled)? event_id já visto (rnds_submission)?
  → pipeline do SDK (ConnectorRuntime), mensagem bruta = envelope do evento (raw zone + ledger: received)
     transform  : ResourceFetcher (GET /fhir/r4/DiagnosticReport|Observation|Specimen|ServiceRequest|Patient|
                  Organization|Practitioner|Encounter, token client credentials system/*.read)
                  → registro já aceito antes (outro evento)? → substituição (relatesTo replaces)
                  → BundleAssembler + YAML do modelo (perfis meta.profile, CNS/CNES, remoções)
     validate   : PreValidator (regras `required` do YAML) — falhou → DLQ com motivo, NENHUMA chamada à RNDS
     publish    : RndsAuthClient (GET /api/token via mTLS, cache 30 min) → RndsEhrClient POST <ehr>/fhir/r4/Bundle
                  201 → aceito (protocolo = id RNDS após a última "/" do Location/Content-Location)
                  401 (EHR-ERR882 token expirado) → descarta token → retry
                  422 EHR-ERR866 numa retentativa → já aceito antes (resposta perdida) → accepted sem protocolo
                  demais 4xx → rejeitado: OperationOutcome armazenado (mascarado) → DLQ sem retry
                  5xx/408/429/timeout → retry exponencial (connector.retry.*) → esgotou → DLQ
  → ledger espelho no core: POST /api/v1/integration/messages (status + dead_letter quando houver)
  → rnds_submission (event_id, modelo, sha256 do Bundle, status, protocolo, resumo do outcome, tentativas)
Kafka sus.integration.command.v1 (sus.integration.reprocess.requested, connector_id=connector-rnds)
  → ReprocessCommandHandler (SDK) → RndsReprocessor: só submissões failed → envelope relido da raw zone
  → RndsDispatcher.reprocess (mesma integration_message reaberta) → mesmo fluxo acima
Timer: heartbeat POST /api/v1/integration/connectors/connector-rnds/heartbeat (contagens 24 h por modelo)
Timer: ReconciliationJob (enviados × aceitos por modelo/período) → POST /api/v1/integration/reconciliation
```

### Montagem do Bundle (biblioteca `org.hl7.fhir.r4` 6.10.4, a mesma do fhir-gateway)

**Resultado de Exame Laboratorial** — conforme o Modelo Computacional do guia (`rel/mc-rel`) e os perfis
oficiais (validados no `RndsProfileValidationTest`):

```text
Bundle  type=document, identifier { system: http://www.saude.gov.br/fhir/r4/NamingSystem/BRRNDS-<solicitante-id>,
                                    value: <exam_result_id> }   (mesmo valor no envio e na substituição)
 ├─ Composition  BRResultadoExameLaboratorial-1.1: status final, type BRTipoDocumento#REL (sem display),
 │               subject.identifier {system .../StructureDefinition/BRIndividuo-1.0, value CNS},
 │               author.identifier  {system .../StructureDefinition/BREstabelecimentoSaude-1.0, value CNES},
 │               title "Resultado de Exame Laboratorial", 1 section → todas as Observations,
 │               relatesTo {code replaces, target Composition/<id RNDS>} só na substituição
 ├─ Observation  BRDiagnosticoLaboratorioClinico-1.0 (uma por resultado): status final, category
 │               BRSubgrupoTabelaSUS (4 primeiros dígitos do SIGTAP: 0202, 0214…), code BRNomeExameLOINC|GAL,
 │               subject (CNS), issued, performer (CNES), valueQuantity (valor+unidade) ou valueCodeableConcept
 │               BRResultadoQualitativoExame, interpretation (só BRResultadoQualitativoExame), note, method.text,
 │               referenceRange.text, specimen → urn do Specimen; SEM effective (0..0 na v1.0)
 └─ Specimen     BRAmostraBiologica-1.0: só type (v2-0487 ou BRTipoAmostraGAL)
```

- O `DiagnosticReport` do gateway não vai no Bundle: fornece status, `issued`, executante (CNES), código
  SIGTAP (categoria) e `specimen`. As Observations são **construídas** só com os elementos permitidos pelo
  perfil (o que não cabe — extensões, identificadores, `basedOn`, `encounter`, interpretação v3, `system`/`code`
  UCUM da quantidade — não é enviado).
- `fullUrl` = `urn:uuid` determinístico por `event_id` (o mesmo evento gera o mesmo Bundle em retries; só muda
  `timestamp`).
- Paciente e estabelecimento só como **identificador** (o perfil proíbe `reference`/`type`): o `Patient` nunca
  é enviado (minimização).
- **Resultado retificado** (`result_status=amended` ou qualquer novo evento do mesmo `exam_result_id` depois de um
  aceito): mesmo `Bundle.identifier` + `Composition.relatesTo` `replaces` → `Composition/<id RNDS>` (o protocolo
  guardado em `rnds_submission`), sempre com `status=final` (o EHR rejeita outro status — EHR-ERR924). Se o envio
  anterior foi aceito sem protocolo conhecido, a substituição vai para a DLQ.

**Sumário de Alta** (desabilitado; **sem modelo computacional oficial**): `Encounter` (id =
`hospital_episode_id` sem `hep_`) + `Condition` com o CID-10 principal (CodeSystem `BRCID10`) e `Composition`
`BRTipoDocumento#SA`; perfis e sistemas de identificador seguem marcados `A-CONFIRMAR` no YAML.

### Pré-validação declarativa (`required` no YAML)

Checks: `present`, `cnes` (7 dígitos), `cns`, `cpf`, `cns_or_cpf` (dígito verificador), `datetime`, `one_of`,
`min_count`, aplicados a fatos extraídos (`requester.solicitante_id`, `patient.cns`, `performer.cnes`,
`exam.code`, `exam.code_system`, `exam.issued`, `observation.category|value|method|reference_range|specimen_type`
— presentes só se **todas** as Observations os têm —, `observations.count`, `report.status`, `hospital.cnes`,
`encounter.start|end|status`, `diagnosis.code`). O REL exige identificador do solicitante, **CNS** do paciente
(o modelo de informação não prevê CPF), CNES do executante, código BRNomeExameLOINC/GAL, categoria, resultado,
método, faixa de referência, tipo de amostra, data de liberação e status final/amended. As mensagens citam só
nomes de regras, nunca valores.

### rnds_submission (idempotência e auditoria)

Um registro por `event_id` (`memory` ou `jdbc` — DDL `db/rnds_submission.sql`, criada se ausente).
Estados: `pending` → `retrying` → `accepted` | `rejected` | `invalid` | `failed`. Evento repetido com
qualquer estado ≠ `failed` é ignorado (`connector_rnds_events_ignored_total{reason="duplicate"}`);
`failed` (DLQ por retry esgotado/erro antes do envio) pode ser reprocessado — pelo próprio evento repetido ou
pelo comando do core (ver [Reprocessamento](#reprocessamento-susintegrationcommandv1)). Nunca guarda PII: hash
SHA-256 do Bundle, protocolo (id RNDS do header `Location`, usado em `relatesTo` numa substituição), HTTP status, resumo do `OperationOutcome` e o próprio outcome **mascarados**
(`Pii.maskText`).

## Configuração

| Propriedade | Padrão | Descrição |
|---|---|---|
| `rnds.environment` (`RNDS_ENVIRONMENT`) | `homologacao` | `homologacao` (único para o Brasil) ou `producao` |
| `rnds.uf` (`RNDS_UF`) | — | UF da credencial (ex.: `mg`); obrigatória em produção — acesso a outra UF é bloqueado |
| `rnds.endpoints.homologacao.auth-url` / `.ehr-url` | `https://ehr-auth-hmg.saude.gov.br/api/token` / `https://ehr-services.hmg.saude.gov.br/api` | **Confirmado** (guia "Ambientes"; Manual v1.2 cap. 5.1; Postman v4) |
| `rnds.endpoints.producao.auth-url` / `.ehr-url` | `https://ehr-auth.saude.gov.br/api/token` / `https://{uf}-ehr-services.saude.gov.br/api` | **Confirmado** (guia; Manual cap. 5.2 — 27 UFs) |
| `rnds.auth-url` / `rnds.ehr-url` (`RNDS_AUTH_URL`/`RNDS_EHR_URL`) | vazio | Sobrescrita explícita (proxy de saída/testes) |
| `rnds.auth.method` / `.token-field` / `.expires-in-field` / `.expires-in-unit` | `GET` / `access_token` / `expires_in` / `MILLIS` | **Confirmado**: `GET /api/token`; `expires_in: 1800000` = 30 min em ms (Manual v1.2 cap. 7) |
| `rnds.auth.default-ttl` / `.max-ttl` / `.refresh-skew` | `PT30M` / `PT30M` / `PT60S` | Cache do token (teto de 30 min) |
| `rnds.models.<m>.ehr-path` | `/fhir/r4/Bundle` | **Confirmado** (envio e substituição: `POST /api/fhir/r4/Bundle`) |
| `rnds.requester-cns` (`RNDS_REQUESTER_CNS`) | — | CNS do profissional lotado no estabelecimento → header `Authorization` |
| `rnds.solicitante-id` (`RNDS_SOLICITANTE_ID`) | — | Identificador do solicitante (Portal de Serviços) → `BRRNDS-<id>`; **não é o CNES** |
| `rnds.cnes-solicitante` (`RNDS_CNES_SOLICITANTE`) | — | CNES do estabelecimento credenciado → `Composition.author` |
| `rnds.headers.token` / `.token-scheme` / `.requester` / `.content-type` | `X-Authorization-Server` / `Bearer` / `Authorization` / `application/fhir+json` | **Confirmado** (guia "Conheça os serviços"; Postman v4) |
| `rnds.certificate.keystore-path` / `-password` / `key-password` / `keystore-type` | — / — / (= senha) / `PKCS12` | Certificado ICP-Brasil e-CNPJ/e-CPF A1 cadastrado na solicitação de acesso |
| `rnds.certificate.truststore-path` / `-password` / `-type` | JVM padrão | Cadeia dos servidores `*.saude.gov.br` |
| `rnds.models.<m>.enabled` | `false` | Habilitação do modelo (`resultado-exame`, `sumario-alta`) |
| `rnds.models.<m>.mapping` | `mappings/rnds-<m>-1.1.0.yaml` | Mapeamento versionado (classpath ou `file:`) |
| `rnds.models.<m>.bundle-type` | do YAML (`document`) | `document` (oficial) ou `transaction` |
| `rnds.fhir.base-url` / `.auth-mode` / `.token-url` / `.client-id` / `.client-secret` / `.scope` | `…/fhir/r4` / `oauth2` / Keycloak / `connector-rnds` / — / `system/*.read` | Leitura no fhir-gateway |
| `RNDS_CONSUMER_GROUP` | `connector-rnds` | Grupo dos gatilhos e do comando de reprocessamento |
| `rnds.submission-store.type` | `memory` | `jdbc` exige `DB_ACTIVE=true` + `quarkus.datasource.*` (PostgreSQL) |
| `rnds.heartbeat.*` / `rnds.reconciliation.*` | 60 s / 1 h, janela `P1D` | Agendamentos |
| `connector.core.mirror.enabled` (`RNDS_MIRROR_TO_CORE`) | `true` | Ledger espelho/heartbeat/reconciliação no core (`CoreIntegrationMirror` do SDK) |
| `connector.*` | ver `connectors/README.md` | raw zone, ledger, DLQ, retry, auth do core |

Variáveis de ambiente: `RNDS_ENVIRONMENT`, `RNDS_UF`, `RNDS_AUTH_URL`, `RNDS_EHR_URL`, `RNDS_REQUESTER_CNS`,
`RNDS_SOLICITANTE_ID`, `RNDS_CNES_SOLICITANTE`, `RNDS_KEYSTORE_PATH`, `RNDS_KEYSTORE_PASSWORD`,
`RNDS_TRUSTSTORE_PATH`, `RNDS_TRUSTSTORE_PASSWORD`, `RNDS_RESULTADO_EXAME_ENABLED`, `RNDS_SUMARIO_ALTA_ENABLED`,
`FHIR_GATEWAY_URL`, `FHIR_TOKEN_URL`, `FHIR_CLIENT_ID`, `FHIR_CLIENT_SECRET`, `KAFKA_BOOTSTRAP_SERVERS`,
`RNDS_CONSUMER_GROUP`, `RNDS_SUBMISSION_STORE`, `DB_*`. (`RNDS_REQUESTER_CPF` foi substituída por
`RNDS_REQUESTER_CNS`: o header `Authorization` leva o **CNS** do profissional.)

## Reprocessamento (`sus.integration.command.v1`)

O operador de integração pede o reprocessamento de uma mensagem na tela de integrações (`POST
/api/v1/integration/messages/{id}/reprocess`); o core publica `sus.integration.reprocess.requested` (canal
`rnds-integration-command`). O `ReprocessCommandHandler` do SDK valida o contrato, filtra `connector_id` e tenant e
deduplica o comando; o `RndsReprocessor` decide pelo estado de `rnds_submission`:

| Estado | Ação |
|---|---|
| `failed` | envelope original relido da raw zone (SHA-256 conferido com o ledger) → `RndsDispatcher.reprocess` reabre a **mesma** `integration_message` e reenvia (token, Bundle determinístico, retry/DLQ) → `accepted` ou `failed` de novo; ledger espelhado no core |
| `accepted` | nada é reenviado (`already_done`; protocolo já registrado) |
| `rejected` / `invalid` | não reprocessável — a correção na origem gera novo evento |
| `pending` / `retrying` | em andamento — ignorado |

`suppress_external_effects=true` (KAF-012) é respeitado no sentido de não duplicar efeitos: só é reenviado o que a
RNDS **não** aceitou. Em `failed` por timeout a RNDS pode ter recebido o Bundle: como o `Bundle.identifier` é o
mesmo, o EHR responde **422 EHR-ERR866** ("identifier já utilizado", guia "Erros") e o conector registra a
submissão como `accepted` **sem protocolo** (o id RNDS fica desconhecido; uma substituição posterior desse resultado
vai para a DLQ e exige conciliação manual com o DATASUS). Métrica
`connector_rnds_*` + `connector_reprocess_total{result}`.

## Habilitação de um modelo (passo a passo)

Depende do **credenciamento do município/estabelecimentos** (não automatizável aqui):

1. **Certificado digital ICP-Brasil** e-CNPJ (ou e-CPF) **A1** do estabelecimento/secretaria (`.pfx`/PKCS#12).
2. **Solicitação de acesso no Portal de Serviços do DATASUS** (https://servicos-datasus.saude.gov.br/) com upload
   do certificado e a lista de CNES — **todos da mesma UF** (Manual v1.2, cap. 6). A aprovação libera a
   homologação e informa o **identificador do solicitante** (`RNDS_SOLICITANTE_ID`).
3. Definir o **profissional requisitante**: CNS de profissional com vínculo CBO autorizado num dos CNES da
   credencial (senão EHR-ERR906) → `RNDS_REQUESTER_CNS`; `RNDS_CNES_SOLICITANTE`; `RNDS_UF=mg`.
4. Instalar certificado/truststore (seção Operação), `RNDS_ENVIRONMENT=homologacao`,
   `RNDS_RESULTADO_EXAME_ENABLED=true`; acompanhar `/q/health/ready`, `connector_rnds_submissions_total` e
   `rnds_submission` (OperationOutcome) até zerar rejeições.
5. **Homologação** (guia "Homologar"): montar PDF de evidências — Bundle aceito em homologação, captura do
   validador sem falhas e o id atribuído pela RNDS (header `Location`) — e pedir acesso à **produção** no Portal
   de Serviços. Só então `RNDS_ENVIRONMENT=producao`.
6. Se algo oficial mudar, **criar nova versão** do YAML (`rnds-resultado-exame-1.2.0.yaml`) e apontar
   `rnds.models.<m>.mapping` — nunca editar uma versão já usada em produção.

## Fontes oficiais consultadas

Consultadas em 2026-10-04. **`rnds-guia.saude.gov.br`, `simplifier.net`, `servicos-datasus.saude.gov.br` e
`gov.br` estão bloqueados pela rede deste ambiente** (proxy de saída); o conteúdo foi lido nas cópias abaixo e
cruzado com o índice de busca, que mostra as mesmas páginas no site oficial.

| Id | Fonte | Versão/data | Uso |
|---|---|---|---|
| [G] | Guia de Integração da RNDS — https://rnds-guia.saude.gov.br (DATASUS), lido no repositório-fonte https://github.com/kyriosdata/rnds (`portal/docs`; transferido ao DATASUS em 2021-11) | commit `32737e3` (2024-08-28) | ambientes, serviços, headers, token, REL (mi/mc), substituição, erros, homologação |
| [M] | DATASUS, *Manual de Integração – RNDS* (Portal de Serviços; cópia `kyriosdata/rnds/datasus/documento-integracao-v1.2.pdf`) | v1.2, 29/07/2020 | endereços por UF, token (two-way SSL, 30 min), credenciamento |
| [P] | Coleção Postman oficial `SOA-RNDS_ConsumoServicos_ExemplosPostman_v4` (Portal de Serviços; cópia em `kyriosdata/rnds/datasus`) | v4, 2020-05 | `GET /api/token`, headers, 201 + `Location`, Bundle de envio e de substituição |
| [SD] | StructureDefinitions/CodeSystems/ValueSets do MS (Simplifier `redenacionaldedadosemsaude`; cópia `kyriosdata/rnds/clientes/validar/definicoes`) → `src/test/resources/rnds-definicoes` (ORIGEM.txt) | REL 1.1 (2020-06-09), Diagnóstico 1.0, Amostra 1.0 | perfis, bindings, códigos |

## Itens a validar na homologação

Confirmados com as fontes oficiais:

- [x] **Endpoints**: homologação `https://ehr-auth-hmg.saude.gov.br/api/token` e `https://ehr-services.hmg.saude.gov.br/api`
      (o valor antigo `ehr-services-hmg…` estava errado); produção `https://ehr-auth.saude.gov.br/api/token` e
      `https://<uf>-ehr-services.saude.gov.br/api` [G, M].
- [x] **Autenticação**: `GET /api/token` com certificado de cliente (two-way SSL); resposta `access_token`,
      `token_type: jwt`, `expires_in: 1800000` (**milissegundos**, 30 min — antes era lido como segundos) [M, G, P].
- [x] **Headers**: `X-Authorization-Server: Bearer <token>`; `Authorization: <CNS do profissional>` (**CNS, não CPF**) [G, P].
- [x] **Formato do Bundle REL**: `document`; `identifier` = `BRRNDS-<identificador do solicitante>` + valor único
      do laboratório; Composition primeiro; Composition → Observation(s) → Specimen; perfis
      `BRResultadoExameLaboratorial-1.1`, `BRDiagnosticoLaboratorioClinico-1.0`, `BRAmostraBiologica-1.0` [G, P, SD].
- [x] **Sistemas de identificador**: no Bundle REL, `.../StructureDefinition/BRIndividuo-1.0` (CNS) e
      `.../BREstabelecimentoSaude-1.0` (CNES); nas consultas, NamingSystems `http://rnds.saude.gov.br/fhir/r4/NamingSystem/{cns,cpf,cnes}` [SD, P].
- [x] **CodeSystems**: `BRTipoDocumento` (`REL`, `SA`), `BRSubgrupoTabelaSUS`, `BRNomeExameLOINC`/`BRNomeExameGAL`,
      `BRResultadoQualitativoExame`, `BRTipoAmostraGAL`/v2-0487, `BRCID10` [SD].
- [x] **Retificação**: mesmo `Bundle.identifier` + `Composition.relatesTo` `replaces` → `Composition/<id RNDS>`;
      status sempre `final` [G, P].
- [x] **Respostas**: 201 + `Location`/`Content-Location` (`…/r4/Bundle/<id>`); 401 EHR-ERR882 (token expirado);
      422 EHR-ERR866 (identifier repetido), EHR-ERR924 (status ≠ final); OperationOutcome no corpo [G, P].
- [x] **Validação offline** dos Bundles REL contra os perfis oficiais (`RndsProfileValidationTest`).

Pendentes (sem fonte oficial acessível ou dependentes do DATASUS):

- [ ] **Versão vigente dos perfis REL**: o exemplo do guia de jun/2021 usa `BRResultadoExameLaboratorial-2.0` e
      `BRDiagnosticoLaboratorioClinico-2.0` (permite `effectiveDateTime`), cujas definições não estão acessíveis
      aqui. Se o EHR exigir 2.0: nova versão do YAML trocando os perfis e retirando `Observation.effective` de
      `strip.elements`, e atualizar `rnds-definicoes`.
- [ ] **REL para exames não-COVID**: o guia restringe o REL a COVID-19; `BRNomeExameLOINC` é "fragment" (o
      validador só adverte). Confirmar com o DATASUS se glicose/HbA1c etc. são aceitos antes de habilitar.
- [ ] **CPF do paciente** no REL (o modelo de informação exige CNS; hoje só CNS é enviado).
- [ ] **Sumário de Alta**: modelo computacional "em desenvolvimento" no guia; modelo de informação instituído por
      Portaria SAES/MS 701/2022 (e, segundo o índice de busca, Portaria GM/MS 8.026/2025) — perfis, seções e
      sistemas de identificador do Bundle **não confirmados**; manter desabilitado.
- [ ] **Códigos não documentados**: 400/403/404/409/412 (tratados como rejeição definitiva) e 429/408/5xx
      (retry). Os erros de segurança EHR-ERR881/906 aparecem no guia sem o status HTTP. Limites de taxa não
      publicados.
- [ ] **Achado nos perfis oficiais**: `BRResultadoExameLaboratorial-1.1` vincula `Composition.status` ao ValueSet
      inexistente `BRDocumentoEstado-1.0` (só advertência no validador).
- [ ] **Content-Type**: guia usa `application/fhir+json`, coleção do DATASUS `application/json` — confirmar na homologação.
- [ ] **Cadeia TLS** dos servidores `*.saude.gov.br` no truststore (o guia cita Let's Encrypt/GeoTrust; muda com o tempo).

Dependem do **credenciamento do município** (preencher no deploy, sem padrão no código):

- [ ] Certificado ICP-Brasil e-CNPJ/e-CPF A1 (OpenBao → `RNDS_KEYSTORE_*`).
- [ ] Solicitação de acesso aprovada no Portal de Serviços do DATASUS (homologação, depois produção).
- [ ] Identificador do solicitante (`RNDS_SOLICITANTE_ID`), CNES (`RNDS_CNES_SOLICITANTE`), CNS do profissional
      requisitante com vínculo CBO (`RNDS_REQUESTER_CNS`), UF (`RNDS_UF=mg`).
- [ ] Habilitação formal de cada modelo (`RNDS_*_ENABLED`) e PDF de evidências de homologação.

## Operação

- **Certificado ICP-Brasil (e-CNPJ/e-CPF A1, PKCS#12)**: guardado no OpenBao; o External Secrets materializa o
  `.p12` em volume `tmpfs` (`/app/secrets/rnds.p12`) e a senha em variável (`RNDS_KEYSTORE_PASSWORD`).
  Nunca entra na imagem nem no repositório. O health (`/q/health/ready`) mostra sujeito e validade
  (`certificate_subject`, `certificate_not_after`): **DOWN** sem certificado com modelo habilitado,
  **DOWN** em produção sem `rnds.uf` válida, **DEGRADED** a menos de 30 dias do vencimento ou sem
  `rnds.requester-cns`/`rnds.solicitante-id`. Renovação: atualizar o segredo
  no OpenBao e reiniciar o pod (o `SSLContext` é criado no primeiro uso).
- **Token**: válido 30 min (`expires_in` em ms, teto `max-ttl`), em cache até `expires_in − refresh-skew`; 401 do
  EHR (EHR-ERR882) descarta o token e reenvia (transitório). O certificado só é usado no serviço de token.
  Falha no serviço de autenticação é transitória (retry → DLQ), para não perder envios enquanto o certificado
  é corrigido.
- **DLQ**: `connector.dlq.*` + ledger do core (`dead_letter`). Motivos: pré-validação (`lote inválido:
  patient_cns=cns`), substituição sem id RNDS do documento anterior, rejeição (`RNDS rejeitou o Bundle: HTTP 422: error/business-rule …`),
  retry esgotado. O `OperationOutcome` mascarado fica em `rnds_submission.operation_outcome`.
- **Métricas**: `connector_rnds_submissions_total{model,result=accepted|rejected|invalid|failed|retry}`,
  `connector_rnds_submission_latency_seconds{model}` (POST ao EHR), `connector_rnds_events_ignored_total
  {model,reason=disabled|not_applicable|duplicate|invalid_event}` + métricas padrão do SDK
  (`connector_messages_*`, `integration_reconciliation_gap_total{entity_type}`).
- **Reconciliação**: `source_count` = enviados (≥ 1 POST), `bus_count` = aceitos; gap = rejeitados +
  falhas após envio + em retry.
- **PII**: logs com filtro `pii-mask`; mensagens de erro/resumos passam por `Pii.maskText`; o Bundle (com CNS)
  só existe em memória e no corpo do POST; a raw zone guarda o envelope do evento (sem CPF/CNS em claro, KAF-009).

## Build, execução e testes

```bash
cd sus-nexus/connectors
mvn -q -pl connector-rnds -am verify
mvn -pl connector-rnds quarkus:dev           # fhir-gateway local sem OIDC (headers X-Test-*)
docker build --build-arg CONNECTOR_MODULE=connector-rnds --build-arg PORT=8098 -t sus-nexus/connector-rnds .
```

Testes (sem Docker/Kafka/RNDS): WireMock com porta HTTP (core, fhir-gateway, Keycloak, EHR) e porta HTTPS
com **certificado de cliente obrigatório** (serviço de autenticação); keystores autoassinados em
`src/test/resources/certs` (senha `changeit`, 100 anos, gerados com `keytool`); Kafka em memória
(`smallrye-in-memory`); H2 para o store JDBC. Cobrem: montagem do Bundle a partir das fixtures FHIR,
pré-validação negativa (sem CNS → DLQ sem chamada), token mTLS em cache (expires_in em ms) e recusa sem
certificado, 201 com Location (id RNDS), 401 → novo token, 422 com OperationOutcome → DLQ sem retry, 422
EHR-ERR866 em retentativa → aceito, substituição de resultado retificado (relatesTo), 503 → retry → sucesso, 503
persistente → DLQ, endereços por ambiente/UF (`RndsEndpointsTest`), validação dos Bundles REL contra os perfis
oficiais da RNDS com o validador HL7 6.10.4 offline (`RndsProfileValidationTest`, definições em
`src/test/resources/rnds-definicoes`),
idempotência por `event_id`, modelo desabilitado, status não final, gatilho Kafka, reconciliação, heartbeat,
reprocessamento por comando Kafka (`RndsReprocessTest`: failed → reenvio aceito na mesma mensagem, aceita/outro
conector → nada reenviado, falha de novo → `failed`),
PII mascarada (logs, ledger, DLQ, OperationOutcome armazenado), sumário de alta e Bundle `transaction`.
