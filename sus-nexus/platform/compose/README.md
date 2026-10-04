# SUS Nexus — ambiente local (Docker Compose)

## Pré-requisitos

- Docker 25+ com Compose v2 (`docker compose version`), 16 GB de RAM recomendados para `core + observability`.
- `cp .env.example .env` (ajuste `*_DOCKERFILE` se o componente usar `src/main/docker/Dockerfile.jvm`).

## Subir

```bash
cd sus-nexus/platform/compose
cp .env.example .env

docker compose --profile infra up -d                # só dependências (rodar serviços pelo IDE)
docker compose --profile core up -d --build         # infra + core-municipal, fhir-gateway, web-shell, connector-pec
docker compose --profile core --profile ai up -d    # + ai-service, LiteLLM, Langfuse
docker compose --profile connectors up -d --build   # infra + core-municipal + SISREG, e-SUS Regulação, LIS, HIS, RIS
docker compose --profile core --profile observability up -d   # + OTel, Prometheus, Grafana, Loki, Tempo, Metabase
docker compose --profile analytics up -d --build    # lakehouse: infra + Trino, Kafka Connect (Iceberg sink), Metabase
docker compose --profile catalog up -d --build      # analytics + OpenSearch + OpenMetadata
```

Lakehouse (perfil `analytics`, detalhes em [`../../data/README.md`](../../data/README.md)): `lakehouse-init` cria as
tabelas bronze no Trino (`trino/bootstrap-lakehouse.sh`); depois registre o sink e provisione o Metabase:

```bash
./kafka-connect/register-iceberg-sink.sh           # http://localhost:8084 (Connect do lakehouse)
./metabase/download-trino-driver.sh && docker compose restart metabase
./metabase/provision.sh                            # conexão Trino + painel "Sala de Situação"
```

Em volumes de Postgres já existentes, aplique o init do lakehouse manualmente:
`docker compose exec postgres bash /docker-entrypoint-initdb.d/02-lakehouse.sh`.

Após o core aplicar as migrações Flyway (tabela `platform.event_outbox`), registre o Debezium:

```bash
./debezium/register-outbox-connector.sh            # http://localhost:8083
```

Tópicos Kafka são criados automaticamente pelo serviço `kafka-init` a partir de
`contracts/events/topics.yaml` (idempotente). Para recriar manualmente: `./kafka/create-topics.sh`.

Ao habilitar `--profile observability`, defina `OTEL_SDK_DISABLED=false` no `.env` para os serviços
exportarem telemetria.

## Portas e credenciais de dev

| Serviço | URL | Credencial |
|---|---|---|
| core-municipal | http://localhost:8080 (`/q/health`, `/q/swagger-ui`) | JWT Keycloak |
| fhir-gateway | http://localhost:8081/fhir/r4 | JWT Keycloak |
| connector-pec | http://localhost:8090 | — |
| connector-sisreg / connector-esus-regulacao | http://localhost:8093 / :8094 | client credentials (`connector-sisreg`, `connector-esus-regulacao`) |
| connector-lis | http://localhost:8095 · MLLP `localhost:2575` | client credentials `connector-lis` |
| connector-his (borda) | http://localhost:8096 · MLLP `localhost:2576` | client credentials `connector-his` |
| connector-ris (borda) | http://localhost:8097 · MLLP `localhost:2577` | client credentials `connector-ris` |
| connector-sia | http://localhost:8099 · entrada `/app/data/sia/{producao,retornos}` (volume `connector-sia-data`) → Kafka `sus.ingest.production.v1` | client credentials `connector-sia` |
| web shell | http://localhost:3000 | usuários abaixo |
| ai-service | http://localhost:8000 (`/health`, `/docs`) | client credentials |
| PostgreSQL | localhost:5432 | `postgres`/`postgres`; app `sus_nexus`/`sus_nexus` |
| Kafka | localhost:9092 (host) · `kafka:19092` (rede) | — |
| Kafka UI | http://localhost:8086 | — |
| Kafka Connect | http://localhost:8083 | — |
| Apicurio | http://localhost:8085 | — |
| Keycloak | http://localhost:8180 | `admin`/`admin`; realm `sus-nexus` |
| OPA | http://localhost:8181 | — |
| OpenBao | http://localhost:8200 | token `root` |
| Temporal | localhost:7233 · UI http://localhost:8233 | — |
| MinIO | http://localhost:9000 · console http://localhost:9001 | `minioadmin`/`minioadmin` |
| Redis | localhost:6379 | — |
| LiteLLM | http://localhost:4000 | `sk-dev-litellm` |
| Langfuse | http://localhost:3003 | `dev@sus-nexus.local`/`sus-nexus-dev` |
| Grafana | http://localhost:3001 | `admin`/`admin` |
| Prometheus | http://localhost:9090 | — |
| Loki / Tempo | :3100 / :3200 | — |
| Metabase | http://localhost:3002 | configurar no 1º acesso (ou `metabase/provision.sh`) |
| Trino | http://localhost:8088 | sem senha em dev; usuário define o grupo (`trino/groups.txt`: `admin`, `dbt`, `metabase`, `analista`, `gestor`) |
| Kafka Connect (lakehouse) | http://localhost:8084 | — |
| OpenMetadata | http://localhost:8585 | `admin@sus-nexus.local`/`admin` |

Usuários do realm (senha `sus-nexus-dev`, `municipality_id=ibge_3143302`): `admin.municipal`, `gestor`,
`prof.aps`, `acs`, `regulador`, `agendador`, `prof.hospitalar`, `auditor`, `dpo`, `operador.integracao`,
`cadastro.mestre`. Detalhes em [`keycloak/README.md`](keycloak/README.md).

## Bancos criados

`sus_nexus_core`, `sus_nexus_fhir`, `sus_nexus_ai`, `sus_nexus_test`, `sus_nexus_fhir_test` (owner `sus_nexus`,
extensões `pg_trgm`, `unaccent`, `pgcrypto`, `uuid-ossp`, `pg_stat_statements`, `vector`), `temporal`,
`temporal_visibility`, `keycloak`, `langfuse`, `apicurio`, `metabase`, `litellm`. Postgres roda com
`wal_level=logical` (Debezium) e papel `debezium` (REPLICATION). Lakehouse (`postgres/init/02-lakehouse.sh`):
`iceberg_catalog` (catálogo JDBC do Iceberg, owner `iceberg`), `openmetadata_db` e papel `analytics_ro`
(somente leitura, BYPASSRLS — catálogo Trino `postgresql`).

## Buckets MinIO

`raw-zone` (versionado), `documents`, `exports` (expira em 30d), `backups` (versionado),
`audit-archive` (Object Lock GOVERNANCE 30d em dev; COMPLIANCE em hml/prod).

## Operação

```bash
docker compose --profile core logs -f core-municipal
docker compose --profile core ps
docker compose --profile core --profile ai --profile observability down      # mantém volumes
docker compose --profile core --profile ai --profile observability down -v   # apaga dados
```

Reset só do Postgres (reexecuta `postgres/init`): `docker compose down && docker volume rm sus-nexus_postgres-data`.

## Validação sem Docker

`docker compose config` não está disponível em todo ambiente; o CI valida este arquivo com
`yamllint` + parse YAML, e os scripts com `bash -n`/shellcheck.
