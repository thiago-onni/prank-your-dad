#!/usr/bin/env bash
# Gera dashboards-configmap.yaml (um ConfigMap por dashboard, label grafana_dashboard=1) a partir de
# dashboards/*.json para o sidecar do Grafana (kube-prometheus-stack). Rode após alterar um JSON.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${HERE}/../dashboards-configmap.yaml"
{
  echo "# GERADO por observability/scripts/gen-dashboards-configmap.sh — não edite manualmente."
  for f in "${HERE}"/../dashboards/*.json; do
    name="$(basename "$f" .json)"
    echo "---"
    echo "apiVersion: v1"
    echo "kind: ConfigMap"
    echo "metadata:"
    echo "  name: sus-dashboard-${name}"
    echo "  namespace: observability"
    echo "  labels:"
    echo "    grafana_dashboard: \"1\""
    echo "    app.kubernetes.io/part-of: sus-nexus"
    echo "  annotations:"
    echo "    grafana_folder: SUS Nexus"
    echo "data:"
    echo "  sus-${name}.json: |-"
    sed 's/^/    /' "$f"
    echo   # garante quebra de linha ao final do JSON (senão o próximo "---" cola no "}")
  done
} > "$OUT"
echo "gerado ${OUT}"
