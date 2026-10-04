# connector-terminology — SIGTAP / CID-10 / CBO / CIAP-2 (arquivo)

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-terminology` / 0.1.0 |
| source_system | SIGTAP (também CID10, CBO, CIAP2 como `system` do lote) |
| supported_source_versions | SIGTAP por competência AAAAMM; CID-10 (2008+); CBO 2002; CIAP-2 |
| supported_protocols | file (diretório observado) |
| supported_entities | `code` → `POST /api/v1/terminology/{system}/codes/upsert` |
| authentication_method | FILE_SYSTEM (core: OIDC client credentials em prod) |
| required_network_access | core-municipal:8080; download manual/FTP do DATASUS |
| data_classification | PUBLIC |
| polling_or_event_mode | FILE_DROP |
| retry_policy | 5 tentativas, backoff 1s ×2 até 5 min |
| rate_limit_policy | 60 req/min, 1 concorrente |
| field_mapping_version | versão de `layouts/terminology-layouts.yaml` (1.0.0) |
| test_suite_version | 1.0.0 |
| owner / support_sla | equipe-integracao@sus-nexus / silver (4h resposta, 2d resolução) |

## Funcionamento
Arquivos colocados em `terminology.input-dir` (padrão `data/terminology/in`) são lidos pela rota Camel `file:`, gravados na raw zone, parseados conforme o layout cujo `file_pattern` casa com o nome, e publicados em lotes de `connector.core.batch-size` códigos. Processados vão para `.done/<data>/`, com erro para `.error/`.

- **SIGTAP**: `tb_procedimento*.txt` em largura fixa (ISO-8859-1). As posições em `terminology-layouts.yaml` seguem o `tb_procedimento_layout.txt` oficial (CO_PROCEDIMENTO 1-10, NO_PROCEDIMENTO 11-260, TP_COMPLEXIDADE 261, TP_SEXO 262, QT_MAXIMA_EXECUCAO 263-266, …, VL_SH 283-292, VL_SA 293-302, VL_SP 303-312, CO_FINANCIAMENTO 313-314, CO_RUBRICA 315-320, QT_TEMPO_PERMANENCIA 321-324, DT_COMPETENCIA 325-330). **Se a competência baixada trouxer layout diferente, ajuste `start/end` no YAML (1-based, inclusivo) e incremente `version`** — o parser é genérico. Para outras tabelas SIGTAP (tb_cid, tb_ocupacao, relacionamentos) basta adicionar entradas no YAML.
- **CID-10**: `cid10*.csv` com cabeçalho `codigo;descricao` **ou** o oficial `CID-10-SUBCATEGORIAS.CSV` (ISO-8859-1, `SUBCAT;...;DESCRICAO`).
- **CBO**: `cbo*.csv` (`codigo;descricao`; renomeie o cabeçalho de `CBO2002 - Ocupacao.csv` ou ajuste `fields`).
- **CIAP-2**: `ciap2*.csv` (`codigo;descricao`).
- A competência (AAAAMM) vem da coluna `DT_COMPETENCIA` ou do nome do arquivo.

## Plano B
Não há plano B automático: são tabelas oficiais publicadas por competência. Operação: baixar a competência, descompactar na pasta de entrada; versionar por competência no core (`competence_from`).

## Execução
`mvn -pl connector-terminology quarkus:dev` · Docker: `docker build -f connector-terminology/src/main/docker/Dockerfile -t sus-nexus/connector-terminology .` (contexto = `connectors/`). Porta 8090; health `/q/health/ready`; métricas `/q/metrics`.
