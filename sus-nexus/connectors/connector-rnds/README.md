# connector-rnds — envio à Rede Nacional de Dados em Saúde (Fase 4, PLANO §6.5)

Conector de **saída**: consome eventos já normalizados no barramento, lê os recursos FHIR R4 no
`fhir-gateway` municipal, monta o `Bundle` do **modelo de informação habilitado** para o município e o
envia à RNDS com o certificado digital ICP-Brasil (e-CNPJ) via mTLS. Não tem MLLP nem fonte de arquivo.

> **Premissa:** perfis, endpoints, cabeçalhos e a forma do Bundle **dependem da documentação oficial
> vigente da RNDS e da habilitação efetiva do município**. Nada aqui é tratado como oficial: tudo é
> parametrizável (`application.properties` + YAML versionado) e os valores de exemplo estão marcados
> **"A CONFIRMAR"** — ver [Itens a validar na homologação](#itens-a-validar-na-homologação).

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-rnds` / 0.1.0 |
| source_system | `RNDS` (destino) |
| supported_entities | `rnds_resultado_exame`, `rnds_sumario_alta` (um por modelo configurado em `rnds.models.*`) |
| supported_protocols | Kafka (`sus.exam.result.v1`, `sus.hospital.discharge.v1`); HTTPS FHIR R4 no fhir-gateway (OAuth2 client credentials, `system/*.read`); HTTPS FHIR R4 na RNDS (mTLS ICP-Brasil) |
| authentication_method | `MTLS` (certificado e-CNPJ, PKCS#12) |
| required_network_access | Kafka, fhir-gateway, core-municipal, serviço de autenticação e EHR da RNDS (saída internet) |
| data_classification | HIGHLY_RESTRICTED |
| polling_or_event_mode | EVENT |
| retry_policy | `connector.retry.*` (padrão 5 tentativas, 5 s ×2, máx. 10 min) só para 5xx/408/429/401/rede; 4xx nunca repete |
| field_mapping_version | `mappings/rnds-resultado-exame-1.0.0.yaml`, `mappings/rnds-sumario-alta-1.0.0.yaml` |
| porta HTTP | 8098 (`/q/health/ready`, `/q/metrics`) |

## Fluxo

```text
Kafka sus.exam.result.v1 (action=available, result_status final|amended)  ─┐  RndsEventConsumer
Kafka sus.hospital.discharge.v1 (action=completed)                         ─┘  (@Blocking, ack após o pipeline)
  → RndsDispatcher: aplicável? modelo habilitado (rnds.models.<m>.enabled)? event_id já visto (rnds_submission)?
  → pipeline do SDK (ConnectorRuntime), mensagem bruta = envelope do evento (raw zone + ledger: received)
     transform  : ResourceFetcher (GET /fhir/r4/DiagnosticReport|Observation|ServiceRequest|Patient|
                  Organization|Practitioner|Encounter, token client credentials system/*.read)
                  → BundleAssembler + YAML do modelo (perfis meta.profile, CNS/CPF/CNES, remoções)
     validate   : PreValidator (regras `required` do YAML) — falhou → DLQ com motivo, NENHUMA chamada à RNDS
     publish    : RndsAuthClient (token via mTLS, em cache até expirar) → RndsEhrClient POST {ehr-url}{ehr-path}
                  201/200 → aceito (protocolo = header Location, senão `id` do corpo)
                  4xx     → rejeitado: OperationOutcome armazenado (mascarado) → DLQ sem retry
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

- **resultado-exame**: `DiagnosticReport` (id = `exam_result_id` sem o prefixo `exr_`, como projeta o gateway)
  + `Observation` de `result`; `ServiceRequest` (de `basedOn`) só completa o código do exame; `Practitioner`
  de `resultsInterpreter` vira referência lógica por CNS/CPF.
- **sumario-alta**: `Encounter` (id = `hospital_episode_id` sem `hep_`) + `Condition` com o CID-10 principal
  do evento (ou de `Encounter.reasonCode`); `serviceProvider` por CNES.
- Paciente e estabelecimento vão como **referência lógica** (`identifier` CNS — preferencial — ou CPF; CNES);
  o `Patient` não é incluído (minimização; `resources.Patient.mode: entry` inclui um Patient só com CNS/CPF).
- Removidos antes do envio: `meta`/`text`/`contained` de origem, extensões e identificadores
  `sus-nexus.gov.br`, elementos de `strip.elements` (ex.: `presentedForm` com URL do Binary interno) e
  referências literais não resolvíveis (geram aviso na pré-validação).
- `document`: `Composition` primeiro (perfil, tipo, título, autor = `rnds.cnes-solicitante`, seção → recurso
  principal); `transaction`: `entry.request POST <Tipo>`. `fullUrl` = `urn:uuid` determinístico por
  `event_id` (o mesmo evento gera o mesmo Bundle em retries, só muda `timestamp`).
- `Bundle.identifier` = `{bundle.identifier_system}` com `{solicitante}` → `rnds.solicitante-id`
  (padrão: `rnds.cnes-solicitante`); valor = `<id canônico>-v<versionId do recurso no gateway>`.

### Pré-validação declarativa (`required` no YAML)

Checks: `present`, `cnes` (7 dígitos), `cns_or_cpf` (dígito verificador), `datetime`, `one_of`, `min_count`,
aplicados a fatos extraídos (`patient.cns`, `patient.cpf`, `performer.cnes`, `exam.code`, `exam.date`,
`report.status`, `hospital.cnes`, `encounter.start|end|status`, `diagnosis.code`, `observations.count`).
Resultado de exame exige CNS ou CPF do paciente, CNES do executante, código do exame, data e status
final/amended. As mensagens citam só nomes de regras, nunca valores.

### rnds_submission (idempotência e auditoria)

Um registro por `event_id` (`memory` ou `jdbc` — DDL `db/rnds_submission.sql`, criada se ausente).
Estados: `pending` → `retrying` → `accepted` | `rejected` | `invalid` | `failed`. Evento repetido com
qualquer estado ≠ `failed` é ignorado (`connector_rnds_events_ignored_total{reason="duplicate"}`);
`failed` (DLQ por retry esgotado/erro antes do envio) pode ser reprocessado — pelo próprio evento repetido ou
pelo comando do core (ver [Reprocessamento](#reprocessamento-susintegrationcommandv1)). Nunca guarda PII: hash
SHA-256 do Bundle, protocolo, HTTP status, resumo do `OperationOutcome` e o próprio outcome **mascarados**
(`Pii.maskText`).

## Configuração

| Propriedade | Padrão / exemplo | Descrição |
|---|---|---|
| `rnds.auth-url` | EXEMPLO `https://ehr-auth-hmg.saude.gov.br/api/token` | Serviço de autenticação (mTLS) — **a confirmar** |
| `rnds.auth.method` / `.token-field` / `.expires-in-field` / `.default-ttl` / `.refresh-skew` | `GET` / `access_token` / `expires_in` / `PT25M` / `PT60S` | Forma da resposta do token e cache |
| `rnds.ehr-url` | EXEMPLO `https://ehr-services-hmg.saude.gov.br/api` | Base do EHR (varia por ambiente/UF) — **a confirmar** |
| `rnds.models.<m>.ehr-path` | `/fhir/r4/Bundle` | Caminho do POST por modelo — **a confirmar** |
| `rnds.requester-cpf` | — | CPF do profissional responsável (solicitante) |
| `rnds.cnes-solicitante` / `rnds.solicitante-id` | — | Estabelecimento solicitante / identificador no NamingSystem do Bundle |
| `rnds.headers.token` / `.token-scheme` / `.requester` / `.content-type` | `X-Authorization-Server` / `Bearer` / `Authorization` / `application/fhir+json` | Cabeçalhos — **a confirmar** |
| `rnds.certificate.keystore-path` / `-password` / `key-password` / `keystore-type` | — / — / (= senha) / `PKCS12` | Certificado e-CNPJ |
| `rnds.certificate.truststore-path` / `-password` / `-type` | JVM padrão | Cadeia dos servidores da RNDS |
| `rnds.models.<m>.enabled` | `false` | Habilitação do modelo (`resultado-exame`, `sumario-alta`) |
| `rnds.models.<m>.mapping` | `mappings/rnds-<m>-1.0.0.yaml` | Mapeamento versionado (classpath ou `file:`) |
| `rnds.models.<m>.bundle-type` | do YAML | `document` ou `transaction` |
| `rnds.fhir.base-url` / `.auth-mode` / `.token-url` / `.client-id` / `.client-secret` / `.scope` | `…/fhir/r4` / `oauth2` / Keycloak / `connector-rnds` / — / `system/*.read` | Leitura no fhir-gateway |
| `RNDS_CONSUMER_GROUP` | `connector-rnds` | Grupo dos gatilhos e do comando de reprocessamento |
| `rnds.submission-store.type` | `memory` | `jdbc` exige `DB_ACTIVE=true` + `quarkus.datasource.*` (PostgreSQL) |
| `rnds.heartbeat.*` / `rnds.reconciliation.*` | 60 s / 1 h, janela `P1D` | Agendamentos |
| `connector.core.mirror.enabled` (`RNDS_MIRROR_TO_CORE`) | `true` | Ledger espelho/heartbeat/reconciliação no core (`CoreIntegrationMirror` do SDK; antes `rnds.mirror-to-core`) |
| `connector.*` | ver `connectors/README.md` | raw zone, ledger, DLQ, retry, auth do core |

Variáveis de ambiente correspondentes: `RNDS_AUTH_URL`, `RNDS_EHR_URL`, `RNDS_REQUESTER_CPF`,
`RNDS_CNES_SOLICITANTE`, `RNDS_SOLICITANTE_ID`, `RNDS_KEYSTORE_PATH`, `RNDS_KEYSTORE_PASSWORD`,
`RNDS_TRUSTSTORE_PATH`, `RNDS_TRUSTSTORE_PASSWORD`, `RNDS_RESULTADO_EXAME_ENABLED`,
`RNDS_SUMARIO_ALTA_ENABLED`, `RNDS_*_PATH`, `FHIR_GATEWAY_URL`, `FHIR_TOKEN_URL`, `FHIR_CLIENT_ID`,
`FHIR_CLIENT_SECRET`, `KAFKA_BOOTSTRAP_SERVERS`, `RNDS_CONSUMER_GROUP`, `RNDS_SUBMISSION_STORE`, `DB_*`.

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
RNDS **não** aceitou. Atenção: em `failed` por timeout a RNDS pode ter recebido o Bundle; o `Bundle.identifier`
determinístico permite que ela recuse duplicidade (comportamento a confirmar na homologação). Métrica
`connector_rnds_*` + `connector_reprocess_total{result}`.

## Habilitação de um modelo (passo a passo)

1. Adesão/credenciamento do município e dos estabelecimentos junto ao DATASUS; obter a lista de modelos
   habilitados, o identificador do solicitante e os endereços de **homologação**.
2. Conferir o YAML do modelo contra a documentação oficial (itens abaixo). Se algo mudar, **criar nova versão**
   (`rnds-resultado-exame-1.1.0.yaml`) e apontar `rnds.models.resultado-exame.mapping` — nunca editar uma
   versão já usada.
3. Instalar o certificado e o truststore (seção Operação), configurar `rnds.requester-cpf` e
   `rnds.cnes-solicitante`, apontar `rnds.auth-url`/`rnds.ehr-url` para homologação.
4. `RNDS_RESULTADO_EXAME_ENABLED=true`; acompanhar `/q/health/ready`, `connector_rnds_submissions_total` e
   `rnds_submission` (OperationOutcome) até zerar rejeições; só então trocar para produção.

## Itens a validar na homologação

- [ ] **Perfis StructureDefinition por modelo** (`composition.profile`, `resources.*.profile`, `bundle.profile`):
      nomes, versões e canonical URLs vigentes (os do YAML são marcados `A-CONFIRMAR`).
- [ ] **NamingSystems** de CNS, CPF e CNES (`identifier_systems`) — hoje iguais aos do fhir-gateway
      (`http://rnds.saude.gov.br/fhir/r4/NamingSystem/{cns,cpf,cnes}`); confirmar também o do profissional.
- [ ] **CodeSystems**: tipo de documento da Composition (`composition.type`), CID-10 (`code_systems.cid10`),
      tabela de procedimentos/exames (SIGTAP vs LOINC) e interpretação/unidades exigidas.
- [ ] **Formato do Bundle** por modelo: `document` × `transaction`, presença de `Composition`, `Patient`
      incluído ou referência lógica, `Bundle.identifier` (sistema `BRRNDS-{solicitante}` e valor), regras
      para resultado **amended** (substituição/`relatesTo`).
- [ ] **Autenticação**: URL, método (GET/POST), campo e validade do token, exigência de cadeia ICP-Brasil
      específica no truststore.
- [ ] **Headers exigidos** no envio: nome do cabeçalho do token e esquema (`X-Authorization-Server: Bearer`),
      cabeçalho do solicitante (`Authorization` = CPF), `Content-Type`.
- [ ] **Endpoint EHR** por ambiente/UF (`rnds.ehr-url` + `ehr-path`).
- [ ] **Códigos de resposta**: 201 vs 200 no aceite, onde vem o identificador (header `Location` vs corpo),
      quais 4xx são definitivos, se 409/412 indicam duplicidade (hoje 4xx ≠ 401/408/429 → rejeitado sem retry),
      limites de taxa (429).
- [ ] **Campos obrigatórios** por modelo (atualizar `required` no YAML) e elementos que não podem ir
      (`strip.elements`).

## Operação

- **Certificado ICP-Brasil (e-CNPJ A1, PKCS#12)**: guardado no OpenBao; o External Secrets materializa o
  `.p12` em volume `tmpfs` (`/app/secrets/rnds.p12`) e a senha em variável (`RNDS_KEYSTORE_PASSWORD`).
  Nunca entra na imagem nem no repositório. O health (`/q/health/ready`) mostra sujeito e validade
  (`certificate_subject`, `certificate_not_after`): **DOWN** sem certificado com modelo habilitado,
  **DEGRADED** a menos de 30 dias do vencimento ou sem `rnds.requester-cpf`. Renovação: atualizar o segredo
  no OpenBao e reiniciar o pod (o `SSLContext` é criado no primeiro uso).
- **Token**: em cache até `expires_in − refresh-skew`; 401 do EHR descarta o token e reenvia (transitório).
  Falha no serviço de autenticação é transitória (retry → DLQ), para não perder envios enquanto o certificado
  é corrigido.
- **DLQ**: `connector.dlq.*` + ledger do core (`dead_letter`). Motivos: pré-validação (`lote inválido:
  patient_identifier=cns_or_cpf`), rejeição (`RNDS rejeitou o Bundle: HTTP 422: error/business-rule …`),
  retry esgotado. O `OperationOutcome` mascarado fica em `rnds_submission.operation_outcome`.
- **Métricas**: `connector_rnds_submissions_total{model,result=accepted|rejected|invalid|failed|retry}`,
  `connector_rnds_submission_latency_seconds{model}` (POST ao EHR), `connector_rnds_events_ignored_total
  {model,reason=disabled|not_applicable|duplicate|invalid_event}` + métricas padrão do SDK
  (`connector_messages_*`, `integration_reconciliation_gap_total{entity_type}`).
- **Reconciliação**: `source_count` = enviados (≥ 1 POST), `bus_count` = aceitos; gap = rejeitados +
  falhas após envio + em retry.
- **PII**: logs com filtro `pii-mask`; mensagens de erro/resumos passam por `Pii.maskText`; o Bundle (com CNS/CPF)
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
pré-validação negativa (sem CNS/CPF → DLQ sem chamada), token mTLS em cache e recusa sem certificado,
201 com Location, 422 com OperationOutcome → DLQ sem retry, 503 → retry → sucesso, 503 persistente → DLQ,
idempotência por `event_id`, modelo desabilitado, status não final, gatilho Kafka, reconciliação, heartbeat,
reprocessamento por comando Kafka (`RndsReprocessTest`: failed → reenvio aceito na mesma mensagem, aceita/outro
conector → nada reenviado, falha de novo → `failed`),
PII mascarada (logs, ledger, DLQ, OperationOutcome armazenado), sumário de alta e Bundle `transaction`.
