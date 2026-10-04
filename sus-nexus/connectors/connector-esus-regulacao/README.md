# connector-esus-regulacao — e-SUS Regulação (API em homologação) e exportações

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-esus-regulacao` / 0.1.0 |
| source_system | ESUS_REGULACAO |
| supported_source_versions | API e-SUS Regulação (rotas/paginação configuráveis; `esus.source-version`) |
| supported_protocols | https-json (OAuth2 client credentials); file-json; file-csv |
| supported_entities | `regulation_request` → `POST /api/v1/regulation/requests` (upsert); `regulation_status` → `POST /api/v1/regulation/requests/by-source/ESUS_REGULACAO/{id}/status` |
| authentication_method | OIDC_CLIENT_CREDENTIALS (api) / FILE_SYSTEM (file) |
| required_network_access | core-municipal:8080; API e-SUS Regulação (`esus.api.base-url`) |
| data_classification | HIGHLY_RESTRICTED |
| polling_or_event_mode | POLLING (api, marca d'água por `atualizado_em`) / FILE_DROP (file, sempre ativo) |
| retry_policy / rate_limit_policy | 5 tentativas backoff exponencial / 120 req/min, 1 concorrente |
| field_mapping_version | `esus-regulacao-request-1.0.0.yaml`, `esus-regulacao-status-1.0.0.yaml` |
| test_suite_version | 1.0.0 |
| owner / support_sla | equipe-integracao@sus-nexus / gold (2h, 1d) |

## Modo `api`
Cliente JSON genérico (`EsusApiClient`, `java.net.http`) com:
- **OAuth2 client credentials** (`esus.api.token-url`, `client-id`, `client-secret`, `scope`; cache do token até
  `expires_in`−30 s; 401/403 invalidam o cache e viram erro transitório). Alternativas: `auth-mode=static|none`.
- **Listagens paginadas** de solicitações (`requests-path`) e eventos (`events-path`) com filtro incremental
  `updated_since` (nome do parâmetro configurável). Paginação por número (`pagination=page`, `page-param`,
  `page-size-param`, `first-page`) ou por cursor (`pagination=cursor`, `cursor-param`, `next-cursor-path`).
  Lista de itens em `items-path` (caminho `a.b`), id em `request-id-path`/`event-id-path`, instante de
  atualização em `updated-at-path`.
- **Marca d'água** por entidade (`esus.api.watermark-file`): após cada varredura completa avança para o maior
  `atualizado_em` visto; falha no meio da varredura não avança (reprocessamento idempotente pelo core).
- Timer `esus.api.poll-interval-ms` (5 min por padrão).

Cada item vira uma `integration_message` (raw = JSON do item; versão = `atualizado_em`), é **achatado** em
caminhos `a.b[0].c` (`JsonFlattener`) e mapeado pelo YAML — assim toda a "forma" da API fica em configuração.
Listas expõem `lista.length` (ex.: `anexos.length` → `attached_documents_count`).

## Modo `file`
`solicitacoes*.json|csv` e `eventos*|status*.json|csv` em `esus.file.input-dir`. JSON pode ser array, objeto
`{items:[...]}` (`items-path`) ou um único objeto; CSV com cabeçalho usa os mesmos caminhos como nome de coluna
(`paciente.cns`, `solicitacao.id`...). Sempre ativo, inclusive em modo `api` (reprocessamentos manuais).

## Mapeamento (hipótese de homologação — ajuste os caminhos `source` nos YAML)
`id`, `tipo`→`kind`, `situacao`→`status`, `prioridade`→`priority`, `data_solicitacao`, `procedimento.codigo|sistema`,
`especialidade`, `unidade_solicitante.cnes`, `profissional_solicitante.id|cbo`, `justificativa_presente`,
`anexos.length`, `cid`, `unidade_executante.cnes`, `data_agendamento`, `regulador.id`, `motivo`, `atualizado_em`,
`paciente.cns|cpf|id` → `citizen_ref` (CNS → CPF → id municipal → id local `ESUS_REGULACAO`).
Eventos: `solicitacao.id` → `target_ref.source_record_id`, `situacao`, `data`, `agendamento.id`, `retorno_origem`.
Situação/prioridade/tipo com tabelas de lookup (normalizadas: sem acento, maiúsculas, `_`).

## Plano B
Enquanto a API não é homologada: exportações periódicas (modo `file`) ou, para municípios ainda no SISREG,
`connector-sisreg`. O core resolve o mesmo pedido por `(source.system, source_record_id)`, então a troca
de fonte não duplica solicitações.

## Pendências de homologação
- Rotas reais, esquema de autenticação (OAuth2? mTLS? API key), limites de taxa e janela de `updated_since`.
- Shape do JSON (caminhos nos YAML), paginação (página × cursor) e fuso dos timestamps.
- Catálogo de situações/prioridades do e-SUS Regulação (lookups).
- Core: endpoint `POST /regulation/requests/by-source/{system}/{sourceRecordId}/status` (contrato atualizado; implementação em andamento).

## Operação
Porta 8094. `mvn -pl connector-esus-regulacao quarkus:dev`. Variáveis: `ESUS_MODE`, `ESUS_API_URL`, `ESUS_AUTH_MODE`,
`ESUS_TOKEN_URL`, `ESUS_CLIENT_ID`, `ESUS_CLIENT_SECRET`, `ESUS_SCOPE`, `ESUS_REQUESTS_PATH`, `ESUS_EVENTS_PATH`,
`ESUS_PAGINATION`, `ESUS_PAGE_PARAM`, `ESUS_PAGE_SIZE`, `ESUS_ITEMS_PATH`, `ESUS_NEXT_CURSOR_PATH`, `ESUS_UPDATED_AT_PATH`,
`ESUS_POLL_INTERVAL_MS`, `ESUS_WATERMARK_FILE`, `ESUS_INPUT_DIR`, `CORE_URL`, `TENANT_ID`. Métricas padrão do SDK;
logs com `pii-mask`; DLQ para erros permanentes (situação desconhecida em evento, solicitação sem cidadão).
