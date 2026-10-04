# OpenMetadata — catálogo, linhagem e dicionário de indicadores (perfil `catalog`)

Opcional (PLANO 10.1/10.2): publica o catálogo do lakehouse (Trino) e a linhagem/documentação do dbt.
Não é necessário para o CI nem para o restante da plataforma.

## Subir local

```bash
cd sus-nexus/platform/compose
docker compose --profile catalog up -d --build    # analytics (Trino, Connect lakehouse) + OpenSearch + OpenMetadata
# UI: http://localhost:8585  (admin@sus-nexus.local / admin — troque no primeiro acesso)
```

O banco `openmetadata_db` (usuário `openmetadata`) é criado por `postgres/init/02-lakehouse.sh`. O serviço
`openmetadata-migrate` aplica as migrações antes do servidor subir. A ingestão roda pela CLI (sem Airflow —
`PIPELINE_SERVICE_CLIENT_ENABLED=false`).

## Ingestão de metadados

1. Gere um token de bot em *Settings → Bots → ingestion-bot* e exporte-o:

   ```bash
   python3 -m venv /tmp/om && . /tmp/om/bin/activate
   pip install "openmetadata-ingestion[trino,dbt]~=1.6.4"
   export OM_SERVER=http://localhost:8585/api OM_JWT_TOKEN=<token> TRINO_HOST_PORT=localhost:8088
   ```

2. Trino (schemas/tabelas/colunas do catálogo `iceberg`):

   ```bash
   metadata ingest -c ingestion/trino.yaml
   ```

3. dbt (linhagem, descrições, testes, tags e `meta.indicador` — fórmula, fonte, periodicidade, dono):

   ```bash
   cd ../../data/dbt && dbt build && dbt docs generate      # alvo Trino (perfil sus_nexus)
   export DBT_TARGET_DIR=$PWD/target
   cd - && metadata ingest -c ingestion/dbt.yaml
   ```

O usuário `openmetadata` pertence ao grupo `pipeline` em `trino/groups.txt` (leitura de metadados de todos os
schemas). Para profiling/amostras, prefira um usuário restrito a `marts_aggregated`.

## Kubernetes

Use o chart oficial `open-metadata/openmetadata` (+ `openmetadata-dependencies` ou OpenSearch/PostgreSQL
gerenciados) com os valores de `platform/helm/openmetadata/values.yaml`. A ingestão pode rodar como CronJob com a
imagem `openmetadata/ingestion` usando os mesmos YAMLs desta pasta (montados por ConfigMap, token via
ExternalSecret).
