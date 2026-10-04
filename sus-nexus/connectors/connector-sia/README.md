# connector-sia — produção ambulatorial/hospitalar e retornos SIA/SIH

Conector de **arquivo** que alimenta o módulo de produção do core (`core.production`) pelo tópico de
ingestão `sus.ingest.production.v1` (consumidor `ingest-production-in`, `ProductionIngestConsumer`):

- **exportações de produção** do sistema de origem (PEC, HIS, sistema próprio) — BPA-C, BPA-I, APAC, AIH →
  `ProductionRecordRegistration` (mesma porta do `POST /api/v1/production/records`);
- **retornos de processamento** SIA/SIH (rejeições/glosas, aceites, pagos, transmitidos) →
  `ProductionOutcomeRegistration` (mesma porta do `POST /api/v1/production/outcomes`).

O conector **não transmite nada ao DATASUS**: a transmissão oficial continua sendo feita pelo faturamento
municipal no sistema oficial; o barramento só exporta lotes (core) e lê de volta os retornos (este conector).

> **Retornos oficiais:** o que o DATASUS disponibiliza de forma oficial e verificável como resultado do
> processamento são os **arquivos de disseminação** por UF e mês de processamento (FTP
> `ftp.datasus.gov.br/dissemin/publicos/{SIASUS,SIHSUS}/200801_/Dados`, `.dbc`; o TabWin expande para `.dbf`):
> SIA `PA` (produção ambulatorial com indicador de aprovação `PA_INDICA` e quantidade aprovada) e SIH `RD`
> (AIH aprovadas), `RJ` (AIH rejeitadas, `ST_SITUAC`/`ST_MOT_BLO`) e `ER` (AIH rejeitadas com erro,
> `CO_ERRO`). Esses layouts (kinds `retorno_sia_pa`, `retorno_sih_rd`, `retorno_sih_rj`, `retorno_sih_er`) estão
> **confirmados** (estrutura física dos próprios arquivos oficiais; códigos nos CNV/DBF oficiais do TabWin) —
> fontes e tabela de campos em [`docs/integracoes/layouts-sia-sih.md`](../../docs/integracoes/layouts-sia-sih.md).
> Seguem **"A CONFIRMAR"**: (1) a **chave de conciliação** — os arquivos oficiais não trazem o id do barramento;
> o alvo é o vínculo de origem com `id_registro_origem` = nº da AIH/APAC, o que exige que o sistema de origem
> exporte a produção com esse id; (2) a alternativa **CSV local** `retorno_sia_csv` (planilha do faturamento, não
> é layout DATASUS); (3) os relatórios de crítica/glosa dos aplicativos SIA/SIHD/SISAIH01 (não localizei layout
> oficial publicado desses relatórios; a documentação do SIA/SIHD não foi acessível pela rede deste ambiente).

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-sia` / 0.1.0 |
| source_system | `SIA` (retornos SIH: `SIH`; produção: `source_system` do layout ou coluna `sistema_origem`) |
| supported_entities | `production_record`, `production_outcome` (`CanonicalBatch.PRODUCTION_*`) |
| supported_protocols | file-csv, file-dbf/dbc (DATASUS), file-txt (largura fixa, configurável) → Kafka `sus.ingest.production.v1`; comandos em `sus.integration.command.v1` |
| authentication_method | FILE_SYSTEM (pasta monitorada / SFTP montado); Kafka mTLS; core por client credentials (`connector-sia`) |
| required_network_access | Kafka (9093), core-municipal:8080 (ledger espelho/heartbeat/reconciliação), MinIO (raw zone) |
| data_classification | HIGHLY_RESTRICTED (CNS/CPF de cidadão e profissional nas exportações) |
| polling_or_event_mode | FILE_DROP |
| retry_policy | `connector.retry.*` (5 tentativas, 2 s ×2, máx. 5 min) — nack/timeout do Kafka é transitório; erro de layout/mapeamento/validação vai direto à DLQ |
| field_mapping_version | `mappings/sia-production-record-1.0.0.yaml`, `mappings/sia-production-outcome-1.1.0.yaml` + `layouts/sia-layouts.yaml` 2.0.0 |
| porta HTTP | 8099 (`/q/health/ready`, `/q/metrics`) |

## Fluxo

```text
sia.file.production-dir (group: producao)  ─┐  readLock=changed; arquivo inteiro lido; SHA-256 no
sia.file.returns-dir    (group: retorno)   ─┘  processed-files.json (mesmo conteúdo renomeado não é relido)
  → kind do layout pelo nome do arquivo (file_pattern dentro do grupo) → linhas de dados
  → 1 RawMessage por linha: JSON {layout_kind, layout_version, file, file_sha256, line, fields{…}}
  → pipeline do SDK: raw zone (SHA-256) → ledger received → transform (layout + YAML) → validate
     → publish: envelope (contracts/events/envelope.schema.json) em sus.ingest.production.v1
        event_type sus.ingest.production.record | sus.ingest.production.outcome
        event_id   evt_<ULID determinístico> = SHA-256(connector, entidade, kind|sha256 do arquivo|linha)
        chave      source.source_record_id;  headers ce_id, ce_type, ce_source, tenant_id,
                   correlation_id, schema_version, replay
        espera o ack do broker (sia.publish.ack-timeout); nack/timeout → retry → DLQ
  → ledger published | dead_lettered, espelhado no core (POST /api/v1/integration/messages)
Timer: heartbeat (contagens 24 h por entidade)  ·  Timer: reconciliação linhas lidas × publicadas
Kafka sus.integration.command.v1 → ReprocessCommandHandler do SDK (reprocessamento de linhas da DLQ)
```

### Idempotência

- **Por arquivo**: SHA-256 do conteúdo no registro `sia.file.processed-registry`; reenvio do mesmo arquivo
  (mesmo com outro nome) é ignorado e movido para `.done/`.
- **Por arquivo + linha**: o `event_id` é derivado do SHA-256 do arquivo e do nº da linha de dados — o mesmo
  registro reenviado (queda no meio do arquivo, reprocessamento) chega ao core com o **mesmo** `event_id` e é
  descartado pelo `event_inbox` (grupo `core-ingest-production`). O core também faz upsert por vínculo de
  origem (`source.system` + `source.source_record_id`).
- `source_record_id` = coluna `id_column` do layout; na ausência, `<sha256 do arquivo[0:16]>:<linha>`.

## Layout (`layouts/sia-layouts.yaml`)

| kind | grupo / pasta | formato | entidade | status |
|---|---|---|---|---|
| `producao` | `producao` (`SIA_PRODUCTION_DIR`) | CSV `;` UTF-8 com cabeçalho (aliases) | `production_record` | homologado (layout do sistema de origem do município) |
| `retorno_sia_pa` | `retorno` (`SIA_RETURNS_DIR`), `PA<UF><AAMM>[a-d].dbc\|.dbf` | dBase III (DATASUS), ISO-8859-1 | `production_outcome` | **oficial** (chave de conciliação A CONFIRMAR) |
| `retorno_sih_rd` | `retorno`, `RD<UF><AAMM>.dbc\|.dbf` | dBase III (DATASUS) | `production_outcome` | **oficial** (idem) |
| `retorno_sih_rj` | `retorno`, `RJ<UF><AAMM>.dbc\|.dbf` | dBase III (DATASUS); só `ST_SITUAC=1` | `production_outcome` | **oficial** (idem) |
| `retorno_sih_er` | `retorno`, `ER<UF><AAMM>.dbc\|.dbf` | dBase III (DATASUS) | `production_outcome` | **oficial** (idem) |
| `retorno_sia_csv` | `retorno`, `*sia*/*sih*/*bpa*/*apac*/*aih*.csv` | CSV `;` ISO-8859-1 (alternativa local) | `production_outcome` | **A CONFIRMAR** |

O antigo `retorno_sih_txt` (largura fixa) foi **removido**: era um layout hipotético, sem fonte oficial — o
DATASUS não publica retorno do SIH em TXT de largura fixa; o formato `fixed-width` continua disponível para
um layout local declarado em YAML.

Campos oficiais usados (nomes físicos do DBF): **PA** — `PA_INDICA` (situação), `PA_AUTORIZ` (nº APAC/autorização
→ `id_registro_origem`), `PA_MVM` (AAAAMM de processamento → `processed_at` = dia 1), `PA_QTDAPR`; **RD** — `N_AIH`,
`ANO_CMPT`+`MES_CMPT`, `REMESSA` (→ `protocol_number`); **RJ** — `ST_SITUAC`, `N_AIH`, `ST_MOT_BLO`
(→ `reason_code`), `ANO_CMPT`+`MES_CMPT`, `REMESSA`; **ER** — `AIH`, `CO_ERRO` (→ `reason_code`), `ANO`+`MES`,
`REMESSA`. O `.dbc` é descomprimido em memória (`DbcDecompressor`, porte do `blast.c` de Mark Adler — conferido
byte a byte contra os DBF expandidos de `PARR2401.dbc`, `RDRR2401.dbc`, `RJRR2401.dbc` e `ERRR2401.dbc`).

Colunas canônicas — produção: `id_registro`, `data_atualizacao`, `sistema_origem`, `instrumento`
(BPA-C/BPA-I/APAC/AIH), `competencia` (`MM/yyyy`, `yyyyMM`), `cnes`, `cns_profissional`, `cbo`,
`procedimento` (SIGTAP, só dígitos), `quantidade`, `id_cidadao` (`cit_…`), `cns_paciente`, `cpf_paciente`, `cid`,
`data_atendimento`, `carater_atendimento` (01–06 ou texto), `numero_apac`, `numero_aih`, `id_atendimento`,
`id_agendamento`, `id_internacao`. Retornos: `id_retorno`, `situacao`, `id_registro_barramento` (`prod_…`),
`sistema_origem_registro` + `id_registro_origem`, `lote`, `data_processamento`, `codigo_motivo`, `motivo`,
`valor_pago`, `quantidade_aprovada`, `protocolo` (+ `valor_casas_implicitas` em largura fixa;
`situacao_prefixo`, `ano_processamento`/`mes_processamento` nos layouts oficiais).

Para trocar o layout sem recompilar: `SIA_LAYOUT=/caminho/sia-layouts-2.1.0.yaml`. Nunca edite uma versão
já usada de layout/mapeamento: crie uma nova e aponte a configuração.

## Mapeamentos (YAML versionado, nunca no código)

- `instrumento` → `kind` (`bpa_c|bpa_i|apac|aih`, lookup estrito); `carater` → `character_of_care`
  (01 eletivo, 02 urgência, 03/04 acidente de trabalho, 05/06 outros).
- `citizen_ref` (código, `SiaRules`): `cit_…` → CNS → CPF; BPA-I/APAC/AIH sem cidadão → DLQ.
- `situacao` do retorno → `outcome`. Oficiais: `SIA-PA:0` → `rejected`, `SIA-PA:5`/`SIA-PA:6` → `accepted`
  (com `approved_quantity` = `PA_QTDAPR`), `SIH-RD` → `accepted`, `SIH-RJ:1` → `rejected`, `SIH-ER` → `rejected`.
  Os arquivos de disseminação **não** informam pagamento (só aprovação): nenhum layout oficial gera `paid`.
  Alternativa CSV (**A CONFIRMAR**): REJEITADO/GLOSADO/RJ/GL → `rejected`; PAGO/PG/GLOSA PARCIAL → `paid`;
  ACEITO/APROVADO/PROCESSADO/AP → `accepted`; RECEBIDO → `received`; TRANSMITIDO → `transmitted`.
- Alvo do retorno (o core exige exatamente um): `production_record_id` (`prod_…`) → `record_source`
  (`sistema_origem_registro` + `id_registro_origem`) → `batch_id`; os demais são descartados.
- Valores: pt-BR (`1.234,56`), ponto decimal simples (`10.50`) e casas implícitas em largura fixa.

## Validação (antes de publicar; falhou → DLQ sem publicação)

Produção: instrumento, competência `yyyyMM`, CNES 7 dígitos, CBO 6, procedimento 10, quantidade 1–999999,
data de atendimento, caráter, nº APAC/AIH obrigatório para APAC/AIH (≤ 13), cidadão obrigatório para BPA-I/APAC/AIH,
dígito verificador de CNS (cidadão e profissional) e CPF. Retorno: situação no domínio do core, `processed_at`,
exatamente um alvo, `prod_<ULID>`, `paid` exige valor e registro (não lote). Mensagens citam só nomes de
campos — **nunca valores** de CNS/CPF.

## Reprocessamento (`sus.integration.command.v1`)

O core publica `sus.integration.reprocess.requested` quando um operador pede o reprocessamento de uma
mensagem na tela de integrações (a mensagem precisa estar no ledger espelho — este conector espelha cada linha,
`connector.core.mirror.pipeline-messages=true`). O `ReprocessCommandHandler` do SDK filtra `connector_id` e
tenant, e o `PipelineReprocessor` relê a linha na raw zone (SHA-256 conferido), reabre a **mesma**
`integration_message` e reexecuta o pipeline; o envelope sai com `replay=true` e o mesmo `event_id`
(o core descarta se já tinha consumido). Mensagem já publicada → nada é reenviado (estado reespelhado).
Correções de conteúdo (linha inválida) devem ser feitas na origem e reexportadas (novo arquivo).

## Configuração

| Propriedade / variável | Padrão | Descrição |
|---|---|---|
| `sia.file.production-dir` / `SIA_PRODUCTION_DIR` | `data/sia/producao` | Exportações de produção (SFTP montado) |
| `sia.file.returns-dir` / `SIA_RETURNS_DIR` | `data/sia/retornos` | Retornos SIA/SIH |
| `sia.file.charset` / `SIA_CHARSET` | `UTF-8` | Charset padrão (kinds podem declarar o seu) |
| `sia.file.processed-registry` / `SIA_PROCESSED_REGISTRY` | `data/sia/processed-files.json` | Marca d'água por arquivo |
| `sia.layout` / `SIA_LAYOUT` | `classpath:layouts/sia-layouts.yaml` | Layout versionado |
| `sia.mapping.record` / `sia.mapping.outcome` | `mappings/sia-production-record-1.0.0.yaml` / `mappings/sia-production-outcome-1.1.0.yaml` | Mapeamentos |
| `sia.publish.topic` / `sia.publish.ack-timeout` | `sus.ingest.production.v1` / `PT30S` | Publicação |
| `sia.heartbeat.*` / `sia.reconciliation.*` | 60 s / 1 h, janela `P1D` | Agendamentos |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SECURITY_PROTOCOL`, `KAFKA_SSL_*` | `localhost:9092`, `PLAINTEXT` | Kafka (mTLS em cluster, KafkaUser `connector-sia`) |
| `SIA_CONSUMER_GROUP` | `connector-sia` | Grupo do consumidor de comandos |
| `connector.core.mirror.enabled` / `CORE_MIRROR_ENABLED` | `true` | Ledger espelho, heartbeat e reconciliação no core |
| `connector.*`, `CORE_URL`, `TENANT_ID`, `CORE_AUTH_MODE`, `RAW_STORE_*`, `LEDGER_TYPE`, `DB_*` | ver `connectors/README.md` | Comuns |

## Operação

- Porta 8099; `mvn -pl connector-sia quarkus:dev` (Kafka local em `localhost:9092`).
- Métricas padrão do SDK (`connector_messages_received_total`, `connector_messages_processed_total{entity_type}`,
  `connector_messages_failed_total{stage}`, `connector_processing_latency_ms`,
  `integration_reconciliation_gap_total{entity_type}`) + `connector_reprocess_total{result}`.
- Health `/q/health/ready`: DEGRADED sem as pastas de entrada; detalhes listam `layouts_a_confirmar`.
- Reconciliação: `source_count` = linhas lidas (registro de arquivos) × `bus_count` = publicadas no ledger;
  gap = linhas na DLQ.
- PII: logs com `pii-mask`; motivos de DLQ e ledger espelho sem CNS/CPF; o conteúdo bruto (com identificadores)
  fica só na raw zone (MinIO) e no `data` do tópico de ingestão (lido só pelo core, KAF-010).

## Itens a validar na homologação

- [x] Arquivos oficiais de retorno: PA (SIA), RD/RJ/ER (SIH) de disseminação do DATASUS — layout físico e
      códigos de situação confirmados (ver `docs/integracoes/layouts-sia-sih.md`).
- [ ] Chave de conciliação: o sistema de origem exporta AIH com `id_registro` = nº da AIH e APAC com
      `id_registro` = nº da APAC? (BPA-C/BPA-I não são conciliáveis individualmente pelos arquivos de disseminação:
      sem CNS do paciente em claro e sem nº de autorização.)
- [ ] Relatórios de crítica/glosa dos aplicativos SIA/SIHD (layout não localizado em fonte oficial acessível);
      se o faturamento os exportar, mapear pela alternativa CSV (`retorno_sia_csv`).
- [ ] Data de processamento: os arquivos oficiais só têm ano/mês (usa-se o dia 1).
- [ ] `source_system` e `id_column` da exportação de produção do sistema de origem de cada município.

## Build e testes

```bash
cd sus-nexus/connectors
mvn -q -pl connector-sia -am verify
docker build --build-arg CONNECTOR_MODULE=connector-sia --build-arg PORT=8099 -t sus-nexus/connector-sia .
docker build -f connector-sia/src/main/docker/Dockerfile -t sus-nexus/connector-sia .
```

Testes sem Docker/Kafka: Kafka em memória (`smallrye-in-memory`), WireMock no lugar do core, fixtures sintéticas
em `src/test/resources/samples` (CSV de produção, retorno SIA CSV ISO-8859-1) e arquivos DATASUS sintéticos gerados
em teste (`DatasusFiles`: `.dbf` dBase III e `.dbc` com fluxo implode) com os nomes/tamanhos físicos dos campos
oficiais. Cobrem: leitura DBF/DBC (registros excluídos, latin-1, arquivos truncados/inválidos),
transformação/validação de BPA-I/BPA-C/APAC, retornos (rejeição por registro de origem, pagamento por `prod_`,
transmissão por lote, PA/RD/RJ/ER oficiais com filtro de linhas), envelope/headers/chave, `event_id` determinístico, DLQ,
marca d'água por arquivo, ledger espelho, reconciliação, heartbeat, reprocessamento por comando Kafka e ausência de
CNS/CPF em logs, DLQ e chamadas ao core.
