#!/usr/bin/env bash
# Provisiona o Metabase (API REST): conexão com o Trino (lakehouse) e o painel "Sala de Situação"
# definido em sala-de-situacao.json (perguntas SQL sobre iceberg.marts_aggregated). Idempotente: atualiza
# conexão, perguntas e painel existentes pelo nome.
#
# Uso: ./provision.sh
#   MB_URL                 default http://localhost:3002
#   MB_ADMIN_EMAIL/PASSWORD default admin@sus-nexus.local / SusNexus-dev-2026 (setup inicial se necessário)
#   TRINO_HOST/TRINO_PORT   default trino/8080 (visão de dentro da rede compose)
#   TRINO_USER              default metabase (grupo `bi` no Trino: só marts_aggregated + dimensões)
#   DRY_RUN=true            valida o JSON e imprime o plano sem chamar a API
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "${SCRIPT_DIR}/provision.py" "${SCRIPT_DIR}/sala-de-situacao.json"
