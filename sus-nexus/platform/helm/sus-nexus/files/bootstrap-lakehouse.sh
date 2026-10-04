#!/usr/bin/env bash
# Inicializa o lakehouse local: schemas e tabelas bronze no Trino (catálogo iceberg / JDBC no PostgreSQL).
# Executado pelo serviço compose `lakehouse-init` (imagem do Trino, que traz a CLI `trino`). Idempotente.
#   TRINO_SERVER   default http://trino:8080
#   BRONZE_DOMAINS default: domínios de contracts/events/topics.yaml exceto ingest/dlq/audit
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRINO_SERVER="${TRINO_SERVER:-http://trino:8080}"
TRINO_USER="${TRINO_USER:-admin}"
BRONZE_DOMAINS="${BRONZE_DOMAINS:-identity aps schedule regulation exam hospital careplan caregap task production communication consent integration}"
DDL="${SCRIPT_DIR}/bronze-ddl.sql"

echo ">> aguardando Trino em ${TRINO_SERVER} ..."
for _ in $(seq 1 90); do
  if trino --server "$TRINO_SERVER" --user "$TRINO_USER" --execute 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 2
done

# Cabeçalho (schemas) + uma cópia do bloco CREATE TABLE por domínio.
header="$(sed -n '1,/^-- {{TABLES}}/p' "$DDL" | grep -v '^-- {{TABLES}}')"
block="$(sed -n '/^-- {{TABLES}}/,$p' "$DDL" | tail -n +2)"
sql="$(mktemp)"
printf '%s\n' "$header" > "$sql"
for d in $BRONZE_DOMAINS; do
  printf '%s\n' "${block//__DOMAIN__/$d}" >> "$sql"
done

echo ">> aplicando DDL bronze (${BRONZE_DOMAINS})"
trino --server "$TRINO_SERVER" --user "$TRINO_USER" --file "$sql"
trino --server "$TRINO_SERVER" --user "$TRINO_USER" --execute 'SHOW TABLES FROM iceberg.bronze'
echo ">> lakehouse pronto. Registre o sink: ../kafka-connect/register-iceberg-sink.sh"
