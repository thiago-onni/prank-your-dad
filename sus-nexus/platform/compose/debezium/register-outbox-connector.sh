#!/usr/bin/env bash
# Registra (ou atualiza) o conector Debezium "core-outbox" no Kafka Connect.
#
# Outbox Event Router — contrato da tabela platform.event_outbox (criada pela migração Flyway do core):
#   id             uuid/text  -> header Kafka `id` (ce_id)
#   aggregate_type text       -> roteamento: tópico `sus.<aggregate_type>.v1`
#                                (ex.: aggregate_type = "identity.citizen" -> sus.identity.citizen.v1;
#                                      "task" -> sus.task.v1). Ver contracts/events/topics.yaml.
#   aggregate_id   text       -> chave da mensagem (subject.municipal_citizen_id ou ID da entidade)
#   event_type     text       -> header `ce_type` (ex.: sus.identity.citizen.created)
#   payload        jsonb      -> valor da mensagem (envelope completo, contracts/events/envelope.schema.json)
#   headers        jsonb      -> header `headers` (JSON com tenant_id, correlation_id, causation_id,
#                                schema_version, replay). Consumidores também encontram esses campos no
#                                envelope. Um SMT que "explode" esse JSON em headers individuais é
#                                evolução futura (ver platform/README.md).
#
# Uso: ./register-outbox-connector.sh [connect-url]
#   CONNECT_URL   default http://localhost:8083
#   DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD  default postgres/5432/sus_nexus_core/debezium/sus_nexus
#   CONNECTOR_NAME default core-outbox
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONNECT_URL="${1:-${CONNECT_URL:-http://localhost:8083}}"
CONNECTOR_NAME="${CONNECTOR_NAME:-core-outbox}"
export DB_HOST="${DB_HOST:-postgres}"
export DB_PORT="${DB_PORT:-5432}"
export DB_NAME="${DB_NAME:-sus_nexus_core}"
export DB_USER="${DB_USER:-debezium}"
export DB_PASSWORD="${DB_PASSWORD:-${SUS_DB_APP_PASSWORD:-sus_nexus}}"

template="${SCRIPT_DIR}/outbox-connector.json"
body="$(sed -e "s|\${DB_HOST}|${DB_HOST}|g" \
            -e "s|\${DB_PORT}|${DB_PORT}|g" \
            -e "s|\${DB_NAME}|${DB_NAME}|g" \
            -e "s|\${DB_USER}|${DB_USER}|g" \
            -e "s|\${DB_PASSWORD}|${DB_PASSWORD}|g" \
            "$template")"

# Somente o objeto "config" é aceito pelo endpoint PUT (create-or-update).
config="$(printf '%s' "$body" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["config"]))' 2>/dev/null \
  || printf '%s' "$body" | sed -n '/"config": {/,/^  }/p' | sed '1s/.*"config": //')"

echo ">> aguardando Kafka Connect em ${CONNECT_URL} ..."
for _ in $(seq 1 60); do
  if curl -fsS "${CONNECT_URL}/connectors" >/dev/null 2>&1; then break; fi
  sleep 2
done

echo ">> registrando conector ${CONNECTOR_NAME}"
http_code="$(curl -sS -o /tmp/connector-response.json -w '%{http_code}' \
  -X PUT -H 'Content-Type: application/json' \
  --data "$config" "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/config")"

if [[ "$http_code" != "200" && "$http_code" != "201" ]]; then
  echo "falha (HTTP ${http_code}):" >&2
  cat /tmp/connector-response.json >&2
  exit 1
fi

sleep 3
echo ">> status:"
curl -fsS "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status" | python3 -m json.tool 2>/dev/null \
  || curl -fsS "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status"
echo
echo "Conector registrado. Verifique os tópicos sus.* no Kafka UI (http://localhost:8086)."
