# connector-cnes — CNES por arquivo oficial de competência (CSV/DBF)

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-cnes` / 0.1.0 |
| source_system | CNES |
| supported_source_versions | BASE_DE_DADOS_CNES_AAAAMM (tbEstabelecimento CSV/DBF) |
| supported_protocols | file |
| supported_entities | `health_unit` → `POST /api/v1/reference/health-units/upsert` |
| authentication_method | FILE_SYSTEM |
| required_network_access | core-municipal:8080; cnes.datasus.gov.br (download) |
| data_classification | PUBLIC |
| polling_or_event_mode | FILE_DROP |
| retry_policy / rate_limit_policy | 5 tentativas backoff exponencial / 60 req/min, 1 concorrente |
| field_mapping_version | `mappings/cnes-health-unit-1.0.0.yaml` (1.0.0) |
| test_suite_version | 1.0.0 |
| owner / support_sla | equipe-integracao@sus-nexus / silver (4h, 2d) |

## Funcionamento
Arquivos `tbEstabelecimento*.csv|dbf` em `cnes.input-dir` (padrão `data/cnes/in`). CSV (`;`, ISO-8859-1, cabeçalho oficial) é lido pelo `DelimitedParser`; DBF pelo `javadbf`. Campos usados: `CO_CNES`, `NO_FANTASIA`, `TP_UNIDADE` (ou `CO_TIPO_UNIDADE`), `CO_MUNICIPIO_GESTOR`, `NO_RAZAO_SOCIAL`, endereço (`NO_LOGRADOURO`, `NU_ENDERECO`, `NO_COMPLEMENTO`, `NO_BAIRRO`, `CO_CEP`), `NU_TELEFONE`, `NU_CNPJ_MANTENEDORA`. `cnes.municipio-gestor` (IBGE 6 dígitos) filtra só as unidades do município. A descrição do tipo vem da tabela `tipo_unidade` do YAML (ajustável). Um arquivo = uma mensagem; publicação em lotes de `connector.core.batch-size`.

## Plano A (pendente) e plano B
- Plano A do PLANO §7.2: web services DATASUS (SOAP) — exige credencial/autorização; implementar como segunda rota de fonte (`cnes.mode=soap`) reaproveitando `transform/validate`.
- Plano B (implementado): carga mensal do arquivo público de competência.

## Execução
Porta 8091. `mvn -pl connector-cnes quarkus:dev`. Variáveis: `CNES_INPUT_DIR`, `CNES_CHARSET`, `CNES_MUNICIPIO_GESTOR`, `CORE_URL`, `TENANT_ID`, `CORE_AUTH_MODE`.
