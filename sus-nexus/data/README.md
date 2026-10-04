# SUS Nexus — Camada analítica (Fase 4)

Lakehouse e indicadores de gestão do SUS Nexus (PLANO_IMPLEMENTACAO.md seções 4.9 e 10; ADR-015). Até a Fase 3 o
BI lia a réplica do PostgreSQL; a partir da Fase 4 os **eventos de domínio** do barramento alimentam um lakehouse
Apache Iceberg, transformado com **dbt** e consultado pelo **Trino** (Metabase/Superset, OpenMetadata).

```text
core (outbox) ──Debezium──▶ Kafka sus.<domínio>.*.v1 ──Kafka Connect Iceberg sink──▶ MinIO s3://lakehouse (Iceberg)
                                                                                      │ catálogo JDBC (PostgreSQL)
réplica do core (analytics_ro) ─────────────────────────────── Trino ◀────────────────┘
                                                                 │  dbt (sus-nexus/data/dbt)
                     bronze ─▶ staging ─▶ intermediate (silver) ─▶ marts / marts_aggregated / marts_identified (gold)
                                                                 │
                                       Metabase "Sala de Situação" · Superset · OpenMetadata · agente de BI
```

| Diretório | Conteúdo |
|---|---|
| `dbt/` | Projeto dbt `sus_nexus` (adapter `dbt-trino`; perfil alternativo `duckdb` para CI/local) |
| `dbt/ci/` | Gerador de eventos sintéticos (`generate_fixtures.py`), carga do bronze no DuckDB (`load_bronze_duckdb.py`), plugin de UDFs do DuckDB |
| `INDICADORES.md` | Dicionário de indicadores (fórmula, fonte, periodicidade, dono, meta) |
| `requirements.txt` | dbt-duckdb, sqlfluff, jsonschema (CI) |

Infraestrutura (em `sus-nexus/platform/`): Kafka Connect Iceberg sink (`compose/kafka-connect/`,
`helm/sus-nexus/templates/lakehouse.yaml`), Trino (`compose/trino/`, dependência `trino` do umbrella),
Metabase (`compose/metabase/`), OpenMetadata (`compose/openmetadata/`, `helm/openmetadata/`).

## Camadas

| Camada | Schema (catálogo `iceberg`) | Materialização | Conteúdo | Acesso (Trino `rules.json`) |
|---|---|---|---|---|
| **bronze** | `bronze.events_<domínio>` | tabela Iceberg (sink) | 1 linha por mensagem Kafka: envelope + `data` (JSON texto) + `_kafka_metadata_*`. Partição `tenant.municipality_id` + `day(occurred_at)` | só `pipeline`/`admins` |
| **staging** | `staging.stg_<domínio>__<entidade>` | view | envelope tipado, `replay = false`, **dedupe por `event_id`** (reentregas at-least-once), payload extraído e tipado, horário local (America/Sao_Paulo) | só pipeline |
| **silver** | `intermediate.int_*` | tabela | **estado corrente por entidade** (último evento por id) + marcos da linha do tempo: agendamentos, tarefas, regulação, exames, episódios hospitalares, planos, lacunas, produção, cidadão (território) | só pipeline |
| **gold pseudonimizada** | `marts.fct_*`, `marts.dim_*` | tabela | fatos por entidade com `citizen_key` (HMAC) — nunca nome/CNS/CPF/nascimento | analistas, gestores (filtro de município); BI só `dim_*` |
| **gold agregada** | `marts_aggregated.agg_*` | tabela | indicadores mensais por unidade/território com **supressão de células pequenas** | analistas, gestores, **BI** |
| **gold identificada** | `marts_identified.rpt_*` | tabela | listas operacionais com `municipal_citizen_id` (ex.: busca ativa) | só `analytics_identified` (RBAC/ABAC, finalidade `care_coordination`) |
| referência | `reference.seed_*` | seed | calendário de competências, metas de indicadores | todos |

Domínios do bronze: `identity, aps, schedule, regulation, exam, hospital, careplan, caregap, task, production,
communication, consent, integration` (todos os tópicos `sus.*` exceto `sus.ingest.*`, `sus.dlq.v1`, `sus.audit.v1`;
roteamento por `event_type`). **Produção** segue os contratos `contracts/events/production/`
(`record`, `validation`, `submission`, `outcome`): instrumento em `kind` (bpa_c/bpa_i/apac/aih), `cnes`,
pendências por `issue_id`/`rule_id`/`rule_version`/`severity`, retorno oficial com `outcome`/`record_status`,
`reason_code`/`reason`, `approved_quantity`, `paid_amount` e `processed_at`, e lotes (`batch_generated →
batch_approved → exported → transmitted`) em `int_production_batches`. Identificadores de cidadão/profissional
presentes no `record` não são projetados. Se `bronze.events_production` ainda não existir num ambiente,
`stg_production__events` → `fct_production` → `agg_production_monthly` materializam vazios com o mesmo schema.

### Modelos

- `staging/`: `stg_identity__citizen`, `stg_aps__appointment`, `stg_schedule__appointment`, `stg_regulation__request`,
  `stg_regulation__status`, `stg_exam__order`, `stg_exam__result`, `stg_hospital__adt`, `stg_hospital__discharge`,
  `stg_careplan__careplan`, `stg_caregap__caregap`, `stg_task__task`, `stg_production__events`.
- `intermediate/`: `int_citizen_current`, `int_appointments`, `int_tasks`, `int_regulation_requests`, `int_exam_orders`,
  `int_hospital_episodes`, `int_care_plans`, `int_care_gaps`, `int_production_records`, `int_production_batches`.
- `marts/`: `fct_appointments` (AGE-010), `fct_regulation_requests` (4.9), `fct_exam_cycle` (EXA-010),
  `fct_hospital_episodes`, `fct_tasks`, `fct_care_gaps`, `fct_production`, `dim_health_unit`, `dim_date`, `dim_territory`.
- `marts_aggregated/`: `agg_appointments_monthly`, `agg_regulation_monthly`, `agg_regulation_queue_current`,
  `agg_exam_cycle_monthly`, `agg_hospital_monthly`, `agg_tasks_monthly`, `agg_care_gaps_monthly`,
  `agg_production_monthly` e `agg_indicadores_mensais` (formato longo, unidade + município, com meta).
- `marts_identified/`: `rpt_care_gap_worklist_identified`.

## Pseudonimização

- `citizen_key = HMAC-SHA256(municipal_citizen_id, salt)` (macro `citizen_key`). Determinístico: permite contar
  pessoas distintas e cruzar fatos sem expor o identificador. O salt vem de `var('citizen_key_salt')` ←
  `DBT_CITIZEN_KEY_SALT` (OpenBao em hml/prod; o valor padrão só serve para dev/CI). Trocar o salt rompe o vínculo
  com cargas antigas (rotação = recarga completa).
- Os eventos de domínio já não trazem CPF/CNS em claro (CONVENTIONS.md); o `subject.identifiers` (mascarado + hash)
  fica só no bronze, acessível apenas ao pipeline.
- Defesa em profundidade: teste genérico `no_pii_columns` em todo modelo gold, teste singular
  `assert_no_pii_columns_in_gold_schemas` (varre o `information_schema`) e regras de coluna no Trino negando
  `municipal_citizen_id`, `cns`, `cpf`, `nome`, `birthdate`, `subject`, `data`, ... fora de `marts_identified`.
- Paridade Trino ↔ DuckDB: no Trino `lower(to_hex(hmac_sha256(to_utf8(id), to_utf8(salt))))`; no DuckDB a UDF
  Python `hmac_sha256_hex` (plugin `dbt/ci/duckdb_plugins/sus_nexus_udfs.py`) gera o mesmo valor.

## Supressão de células pequenas

Macro `suppress_small_cells(expr, n=None, keep_zero=true)`: publica nulo quando `0 < n < var('small_cell_threshold')`
(padrão **5**). Contagens usam o próprio valor como `n`; taxas, médias e percentis usam o denominador da célula.
Zero é publicado (não identifica ninguém). Em `agg_indicadores_mensais`, numerador, denominador e valor são nulos
quando o denominador é pequeno (`is_suppressed = true`). Testes: `small_cells_suppressed` (genérico, colunas de
contagem), `assert_suppressed_cells_have_no_value`. Observação: é supressão **primária**; painéis públicos devem
usar apenas `marts_aggregated` (supressão complementar entre células vizinhas é evolução futura).

## Qualidade (testes dbt)

`unique`/`not_null` nas chaves, `relationships` (fatos → `dim_health_unit`, `dim_date`, `dim_territory`,
`seed_metas_indicadores`), `accepted_values` (status/ações dos contratos), `dbt_utils.accepted_range` (taxas em
[0,1], tempos ≥ 0), `unique_combination_of_columns` no grão dos agregados, **frescor** das fontes bronze
(`dbt source freshness`: alerta 24 h, erro 72 h; cadastro/cuidado 7/30 dias) e testes singulares
(`assert_no_show_rate_between_0_and_1`, `assert_proportion_indicators_between_0_and_1`,
`assert_regulation_wait_consistent`, `assert_citizen_key_is_hmac`, ...). 355 nós no `dbt build` do CI.

## Como rodar

### CI/local sem infraestrutura (DuckDB)

```bash
cd sus-nexus/data
python3.11 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
cd dbt && export DBT_PROFILES_DIR=$PWD
python ci/generate_fixtures.py --anchor now      # ~13 mil eventos sintéticos, validados contra contracts/events
python ci/load_bronze_duckdb.py                  # → target/sus_nexus_ci.duckdb (schema bronze)
dbt deps
dbt build --profile duckdb --target ci
dbt source freshness --profile duckdb --target ci
sqlfluff lint models tests                       # dialeto trino, regras básicas (.sqlfluff)
```

`generate_fixtures.py --no-production` exercita o caminho tolerante (sem `bronze.events_production`). As fixtures
simulam dois municípios (um pequeno, para exercitar a supressão), reentregas (mesmo `event_id`) e eventos
`replay=true`, e reaproveitam listas de `tools/synthetic-data/generate.py`.

### Lakehouse local (Trino)

```bash
cd sus-nexus/platform/compose && cp .env.example .env
docker compose --profile analytics up -d --build     # infra + Trino (:8088) + Connect lakehouse (:8084) + Metabase
./kafka-connect/register-iceberg-sink.sh             # após lakehouse-init criar as tabelas bronze
cd ../../data/dbt && pip install "dbt-trino>=1.9,<1.11"
dbt deps && dbt build                                # perfil sus_nexus (Trino localhost:8088, usuário dbt)
../../platform/compose/metabase/download-trino-driver.sh && docker compose restart metabase
../../platform/compose/metabase/provision.sh         # conexão Trino + painel "Sala de Situação"
```

O usuário `dbt` (grupo `pipeline`) lê `postgresql.reference.*` (réplica do core, `analytics_ro`) para as
dimensões de unidade/equipe (`use_core_replica()` = verdadeiro no alvo Trino) e escreve no catálogo `iceberg`.
Em CI as dimensões são derivadas dos CNES observados nos eventos.

### Kubernetes

`lakehouse.enabled` (Iceberg sink no Kafka Connect `sus-connect`, tópico de controle, ACLs, segredos, bucket) e
`trino.enabled` (chart oficial) no umbrella — ligados em `values-dev.yaml`; no Argo CD o campo `lakehouse` do
ambiente liga o sink (componente `data`) e o Trino (componente `analytics`). O dbt roda como job agendado
(CronJob/Temporal — evolução) com `DBT_CITIZEN_KEY_SALT` e `TRINO_PASSWORD` do OpenBao.

## Pendências conhecidas

- **Execução real ainda não validada contra Trino/Iceberg**: o SQL dos modelos foi compilado com o adapter
  `dbt-trino` e validado sintaticamente (sqlglot, dialeto trino), mas só o caminho DuckDB foi executado.
- Imagem `kafka-connect-debezium` precisa ser reconstruída/publicada (agora inclui o Iceberg sink) e a tag
  `kafkaConnect.image` atualizada; S3FileIO do sink precisa confiar na CA interna do MinIO (truststore do Connect).
- Produção: indicadores de prazo de competência (`deadline_at`) e de valor (estimado × pago) estão nos fatos, mas
  ainda sem meta pactuada em `seed_metas_indicadores`; `apac`/`aih` (SIH) não aparecem nas fixtures sintéticas.
- Supressão complementar, agendamento do dbt no cluster, incremental (merge) para staging/silver em volume alto.
