# connector-pec — e-SUS APS / PEC (somente leitura)

| Metadado | Valor |
|---|---|
| connector_id / version | `connector-pec` / 0.1.0 |
| source_system | PEC |
| supported_source_versions | e-SUS APS PEC 4.x/5.x via réplica ou CSV exportado (`pec.source-version`) |
| supported_protocols | file; jdbc-postgresql-readonly |
| supported_entities | `citizen` → `POST /api/v1/citizens`; `appointment` → `POST /api/v1/appointments` |
| authentication_method | FILE_SYSTEM (file) / DATABASE_CREDENTIALS (jdbc) |
| required_network_access | core-municipal:8080; replica-pec:5432 (só jdbc) |
| data_classification | HIGHLY_RESTRICTED |
| polling_or_event_mode | FILE_DROP (file) / POLLING (jdbc, marca d'água por `dt_atualizado`) |
| retry_policy / rate_limit_policy | 5 tentativas backoff exponencial / 600 req/min, 2 concorrentes |
| field_mapping_version | `pec-citizen-1.0.0.yaml` e `pec-appointment-1.0.0.yaml` |
| test_suite_version | 1.0.0 |
| owner / support_sla | equipe-integracao@sus-nexus / gold (2h, 1d) |

## Modos (`pec.mode`)
- **file** (padrão, testado): CSV exportados em `pec.file.input-dir` — `cidadaos*.csv` e `agendamentos*.csv`, `;`, UTF-8, cabeçalho em minúsculas igual aos aliases das consultas SQL. Cada linha vira uma `integration_message` (raw zone, ledger e DLQ por registro); `source_record_version` = `dt_atualizado`.
- **jdbc** (plano A, requer autorização e réplica): timer a cada `pec.jdbc.poll-interval-ms` executa `sql/pec-citizens.sql` e `sql/pec-appointments.sql` com o parâmetro `:#watermark` (último `dt_atualizado` sincronizado, persistido em `pec.jdbc.watermark-file`). Datasource nomeado `pec` (`quarkus.datasource.pec.*`, inativo por padrão: `PEC_DB_ACTIVE=true` para ligar) com `readOnly=true` e conexões marcadas `setReadOnly(true)`. **As consultas devem ser ajustadas à versão do PEC** (nomes de tabela/coluna de `tb_cidadao`, `tb_agendado`, `tb_sexo`, `tb_situacao_agendado` variam); mantenha os aliases de saída usados pelos YAML. Use usuário de banco com `SELECT` apenas e aponte **sempre para a réplica**, nunca para o banco de produção. **O conector nunca escreve no PEC.**

## Mapeamento
`citizen`: nome, nome social, mãe/pai, nascimento, sexo e raça/cor (lookups ajustáveis), identificadores CPF/CNS (só quando presentes) + `PEC` (co_seq_cidadao), endereço, contato, território (CNES/INE/microárea). `appointment`: situação → `status` (lookup `situacao`), `kind=direct`, `scheduled_start` ISO-8601 com offset de America/Sao_Paulo, `citizen_ref` por prioridade CPF → CNS → id PEC.

## Plano B
Exportações periódicas (modo `file`), já implementado; LEDI/Thrift fica como evolução (nova rota de fonte reaproveitando `transform/validate`).

## Execução
Porta 8092. `mvn -pl connector-pec quarkus:dev`. Variáveis: `PEC_MODE`, `PEC_INPUT_DIR`, `PEC_DB_ACTIVE`, `PEC_DB_URL`, `PEC_DB_USER`, `PEC_DB_PASSWORD`, `PEC_CITIZENS_SQL`, `PEC_APPOINTMENTS_SQL`, `PEC_POLL_INTERVAL_MS`, `CORE_URL`, `TENANT_ID`, `CORE_AUTH_MODE`.
