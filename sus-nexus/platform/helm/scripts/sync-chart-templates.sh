#!/usr/bin/env bash
# Replica os templates do chart de referência (sus-nexus-core) para os demais charts de componente.
# Os cinco charts compartilham templates idênticos; só Chart.yaml e values.yaml diferem.
# Uso: ./sync-chart-templates.sh [--check]   (--check: falha se houver divergência; usado no CI)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHARTS="${HERE}/../charts"
SRC="${CHARTS}/sus-nexus-core/templates"
TARGETS=(sus-nexus-fhir sus-nexus-web sus-nexus-ai sus-nexus-connector)
check="${1:-}"
rc=0
for t in "${TARGETS[@]}"; do
  mkdir -p "${CHARTS}/${t}/templates"
  for f in "${SRC}"/*; do
    dst="${CHARTS}/${t}/templates/$(basename "$f")"
    if [[ "$check" == "--check" ]]; then
      if ! diff -q "$f" "$dst" >/dev/null 2>&1; then
        echo "DIVERGENTE: ${dst}" >&2; rc=1
      fi
    else
      cp "$f" "$dst"
    fi
  done
done
[[ "$check" == "--check" ]] && { [[ $rc -eq 0 ]] && echo "templates sincronizados"; exit $rc; }
echo "templates copiados para: ${TARGETS[*]}"
