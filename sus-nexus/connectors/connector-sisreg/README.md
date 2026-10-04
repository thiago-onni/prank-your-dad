# connector-sisreg — SISREG III (exportações do gestor municipal)

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-sisreg` / 0.1.0 |
| source_system | SISREG |
| supported_source_versions | SISREG III — relatórios/planilhas exportados pelo gestor (`sisreg.source-version`) |
| supported_protocols | file-csv; file-xlsx (Apache POI) |
| supported_entities | `regulation_request` → `POST /api/v1/regulation/requests` (upsert por código da solicitação); `regulation_status` → `POST /api/v1/regulation/requests/by-source/SISREG/{codigo}/status`; `provider_capacity` → `POST /api/v1/regulation/capacity` |
| authentication_method | FILE_SYSTEM |
| required_network_access | core-municipal:8080 |
| data_classification | HIGHLY_RESTRICTED (CNS/CPF nas planilhas) |
| polling_or_event_mode | FILE_DROP |
| retry_policy / rate_limit_policy | 5 tentativas backoff exponencial / 600 req/min, 2 concorrentes |
| field_mapping_version | `sisreg-regulation-request-1.0.0.yaml`, `sisreg-regulation-status-1.0.0.yaml`, `sisreg-provider-capacity-1.0.0.yaml` + layout `layouts/sisreg-layouts.yaml` 1.0.0 |
| test_suite_version | 1.0.0 |
| owner / support_sla | equipe-integracao@sus-nexus / gold (2h, 1d) |

## Por que exportações (plano B oficial)
O SISREG não expõe API de integração aos municípios; a extração oficial possível é o relatório/planilha
gerado pelo operador/gestor (solicitações ambulatoriais, agendamentos, devoluções, oferta/cotas). O conector
**nunca escreve no SISREG** e **não decide nada**: só reflete o status registrado no sistema oficial (REG-009).

## Fluxo
1. Arquivo `.csv`/`.xlsx` depositado em `sisreg.file.input-dir` (mover atomicamente; `readLock=changed`).
2. SHA-256 do arquivo consultado em `sisreg.file.processed-registry` (**marca d'água por arquivo**): mesmo
   conteúdo, ainda que renomeado, não é reprocessado (fica em `.done/`).
3. Kind do layout escolhido pelo nome do arquivo (`file_pattern`); cabeçalhos normalizados (minúsculas, sem
   acento, `_`) e resolvidos por aliases (coalesce) → colunas canônicas.
4. Cada linha vira uma `integration_message` (raw = JSON `{cabeçalho original → valor}`; ledger, DLQ e
   `Idempotency-Key` por registro, versão = `data_atualizacao` ou hash da linha).
5. Mapeamento YAML versionado → payload do core → validação (domínios, CNES 7 dígitos, CNS 15 dígitos,
   competência `yyyyMM`) → publicação.

## Layout (`layouts/sisreg-layouts.yaml`)
| kind | file_pattern | entidade | id |
|---|---|---|---|
| `solicitacoes` | `*solicita*.csv/xlsx` | `regulation_request` (upsert com status atual) | código da solicitação |
| `agendamentos` | `*agenda*`/`*marca*` | `regulation_status` (by-source; default `AGENDADA`) | código da solicitação |
| `devolucoes` | `*devol*` | `regulation_status` (by-source; default `DEVOLVIDA`, `return_to_origin=true`) | código da solicitação |
| `oferta` | `*oferta*`/`*vaga*`/`*cota*`/`*capacidade*` | `provider_capacity` | CNES executante + procedimento + competência |

Colunas canônicas típicas: `codigo_solicitacao`, `codigo_paciente`, `cns_paciente`, `cpf_paciente`, `tipo`,
`codigo_procedimento` (SIGTAP, só dígitos), `especialidade`, `cnes_solicitante`, `cnes_executante`,
`profissional_solicitante`, `cbo_profissional`, `classificacao_risco`, `situacao`, `data_solicitacao`,
`data_agendamento`, `data_situacao`, `data_atualizacao`, `regulador`, `motivo`, `cid`, `justificativa`;
oferta: `competencia`, `vagas_ofertadas`, `vagas_utilizadas`, `vagas_disponiveis`.
Aponte um layout próprio com `SISREG_LAYOUT=/caminho/layout.yaml` sem recompilar.

## Mapeamentos (versionados em YAML, nunca no código)
- **status** (`lookups.situacao`): PENDENTE/SOLICITADA/EM FILA→`requested`, EM ANALISE→`under_review`,
  PENDENCIA→`pending_documents`, DEVOLVIDA→`returned`, AUTORIZADA→`authorized`, AGENDADA→`scheduled`,
  NEGADA→`denied`, CANCELADA→`cancelled`, FALTA/NAO COMPARECEU→`no_show`, REALIZADA→`performed`, VENCIDA→`expired`.
- **prioridade** (`lookups.prioridade`, classificação de risco): VERMELHA→`emergency`, AMARELA→`urgent`,
  VERDE→`priority`, AZUL→`elective` (também 0–3 e palavras).
- **kind**: coluna `tipo` (CONSULTA/EXAME/PROCEDIMENTO/CIRURGIA/INTERNAÇÃO) ou, na ausência, pelo grupo SIGTAP
  (03.01.01→consultation, 02→exam, 04→surgery, demais→procedure) — `SisregRules`.
- **citizen_ref**: CNS (15 dígitos) → CPF (11) → código do paciente no SISREG (`identifier_system=SISREG`).
- Datas `dd/MM/yyyy[ HH:mm[:ss]]` → ISO-8601 com offset America/Sao_Paulo; competência `MM/yyyy`→`yyyyMM`.

## Decisão: status por upsert × by-source
Relatórios completos (solicitações) são publicados como **upsert** em `POST /regulation/requests` com o status
corrente (o core faz upsert por `source.source_record_id`). Relatórios que só trazem a mudança (agendamentos,
devoluções) usam `POST /regulation/requests/by-source/SISREG/{codigo}/status` (`RegulationStatusChange`).
Se o relatório de agendamentos da sua instalação trouxer todas as colunas da solicitação, troque
`entity: regulation_status` por `regulation_request` no layout.

## Pendências de homologação
- Confirmar com o gestor os relatórios disponíveis, periodicidade e charset (`SISREG_CHARSET`, ISO-8859-1 é comum).
- Validar aliases de cabeçalho da versão do SISREG em uso e a tabela de situações (há variações regionais).
- Oferta: confirmar se a planilha traz vagas por competência ou por semana/escala (ajustar `competencia`).
- Core: endpoint `POST /regulation/requests/by-source/{system}/{sourceRecordId}/status` (contrato atualizado; implementação em andamento).

## Operação
Porta 8093. `mvn -pl connector-sisreg quarkus:dev`. Variáveis: `SISREG_INPUT_DIR`, `SISREG_CHARSET`, `SISREG_DELIMITER`,
`SISREG_SHEET_INDEX`, `SISREG_LAYOUT`, `SISREG_PROCESSED_REGISTRY`, `CORE_URL`, `TENANT_ID`, `CORE_AUTH_MODE`.
Métricas padrão do SDK (`connector_messages_*`, tag `entity_type`) em `/q/metrics`; health em `/q/health/ready`
(diretório de entrada). Logs com `pii-mask` (CNS/CPF nunca em claro). Falhas permanentes (linha sem código,
data inválida, situação desconhecida em arquivos de status) vão para a DLQ com o raw preservado.
