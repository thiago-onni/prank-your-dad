#!/usr/bin/env bash
# Sincroniza os artefatos do lakehouse do compose (fonte canônica) com as cópias usadas pelo chart umbrella:
#   compose/trino/rules.json              → sus-nexus/files/trino-rules.json        (regras de acesso do Trino)
#   compose/trino/bronze-ddl.sql          → sus-nexus/files/bronze-ddl.sql          (tabelas bronze Iceberg)
#   compose/trino/bootstrap-lakehouse.sh  → sus-nexus/files/bootstrap-lakehouse.sh  (Job de bootstrap)
# O CI roda com --check.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="${HERE}/../../compose/trino"
DST_DIR="${HERE}/../sus-nexus/files"
PAIRS=("rules.json:trino-rules.json" "bronze-ddl.sql:bronze-ddl.sql" "bootstrap-lakehouse.sh:bootstrap-lakehouse.sh")
status=0
for pair in "${PAIRS[@]}"; do
  src="${SRC_DIR}/${pair%%:*}"; dst="${DST_DIR}/${pair##*:}"
  if [[ "${1:-}" == "--check" ]]; then
    if ! diff -q "$src" "$dst" >/dev/null 2>&1; then
      echo "DIVERGENTE: ${dst} difere de ${src} — rode helm/scripts/sync-lakehouse-files.sh" >&2; status=1
    fi
  else
    mkdir -p "$DST_DIR"; cp "$src" "$dst"; echo "copiado ${pair%%:*} → ${dst}"
  fi
done
[[ "${1:-}" == "--check" && $status -eq 0 ]] && echo "arquivos do lakehouse sincronizados"
exit $status
