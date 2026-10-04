#!/usr/bin/env bash
# Registra (ou atualiza) o conector "bronze-iceberg-sink" (Apache Iceberg Kafka Connect) no Kafka Connect do
# lakehouse (serviço compose `connect-lakehouse`, perfil `analytics`).
#
# Pré-requisitos (feitos por ../trino/bootstrap-lakehouse.sh, serviço `lakehouse-init`):
#   - banco iceberg_catalog (catálogo JDBC) + bucket MinIO `lakehouse`;
#   - schema/tabelas Iceberg bronze.events_<domínio> criadas (auto-create desligado no conector).
#
# Uso: ./register-iceberg-sink.sh [connect-url]
#   CONNECT_URL                 default http://localhost:8084
#   CATALOG_DB_HOST/PORT/NAME   default postgres/5432/iceberg_catalog
#   CATALOG_DB_USER/PASSWORD    default iceberg/iceberg
#   S3_ENDPOINT                 default http://minio:9000
#   S3_ACCESS_KEY/S3_SECRET_KEY default sus-lakehouse/sus-lakehouse-secret
#   LAKEHOUSE_BUCKET            default lakehouse
#   ICEBERG_SINK_TASKS          default 1 · ICEBERG_COMMIT_INTERVAL_MS default 60000 · DLQ_REPLICATION_FACTOR default 1
#   DRY_RUN=true                apenas imprime a configuração renderizada
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECT_URL="${1:-${CONNECT_URL:-http://localhost:8084}}"
CONNECTOR_NAME="${CONNECTOR_NAME:-bronze-iceberg-sink}"
export CATALOG_DB_HOST="${CATALOG_DB_HOST:-postgres}"
export CATALOG_DB_PORT="${CATALOG_DB_PORT:-5432}"
export CATALOG_DB_NAME="${CATALOG_DB_NAME:-iceberg_catalog}"
export CATALOG_DB_USER="${CATALOG_DB_USER:-iceberg}"
export CATALOG_DB_PASSWORD="${CATALOG_DB_PASSWORD:-iceberg}"
export S3_ENDPOINT="${S3_ENDPOINT:-http://minio:9000}"
export S3_ACCESS_KEY="${S3_ACCESS_KEY:-sus-lakehouse}"
export S3_SECRET_KEY="${S3_SECRET_KEY:-sus-lakehouse-secret}"
export LAKEHOUSE_BUCKET="${LAKEHOUSE_BUCKET:-lakehouse}"
export ICEBERG_SINK_TASKS="${ICEBERG_SINK_TASKS:-1}"
export ICEBERG_COMMIT_INTERVAL_MS="${ICEBERG_COMMIT_INTERVAL_MS:-60000}"
export DLQ_REPLICATION_FACTOR="${DLQ_REPLICATION_FACTOR:-1}"

# Renderiza ${VAR} do template e extrai somente o objeto "config" (endpoint PUT create-or-update).
config="$(python3 - "${SCRIPT_DIR}/iceberg-sink-bronze.json" <<'PY'
import json, os, re, sys
raw = open(sys.argv[1], encoding="utf-8").read()
rendered = re.sub(r"\$\{([A-Z0-9_]+)\}", lambda m: os.environ[m.group(1)], raw)
print(json.dumps(json.loads(rendered)["config"]))
PY
)"

if [[ "${DRY_RUN:-false}" == "true" ]]; then
  printf '%s\n' "$config" | python3 -m json.tool
  exit 0
fi

echo ">> aguardando Kafka Connect em ${CONNECT_URL} ..."
for _ in $(seq 1 90); do
  if curl -fsS "${CONNECT_URL}/connector-plugins" 2>/dev/null | grep -q IcebergSinkConnector; then break; fi
  sleep 2
done

echo ">> registrando conector ${CONNECTOR_NAME}"
resp="$(mktemp)"
http_code="$(curl -sS -o "$resp" -w '%{http_code}' -X PUT -H 'Content-Type: application/json' \
  --data "$config" "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/config")"
if [[ "$http_code" != "200" && "$http_code" != "201" ]]; then
  echo "falha (HTTP ${http_code}):" >&2
  cat "$resp" >&2
  exit 1
fi
sleep 3
echo ">> status:"
curl -fsS "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status" | python3 -m json.tool
echo "Conector registrado. Consulte: trino --server localhost:8088 --execute 'select count(*) from iceberg.bronze.events_task'"
