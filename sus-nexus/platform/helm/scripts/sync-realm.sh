#!/usr/bin/env bash
# Sincroniza o realm Keycloak de dev (platform/compose/keycloak/realm-sus-nexus.json) com a cópia usada
# pelo chart umbrella (platform/helm/sus-nexus/files/realm-sus-nexus.json). O CI roda com --check.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${HERE}/../../compose/keycloak/realm-sus-nexus.json"
DST="${HERE}/../sus-nexus/files/realm-sus-nexus.json"
if [[ "${1:-}" == "--check" ]]; then
  if diff -q "$SRC" "$DST" >/dev/null; then echo "realm sincronizado"; exit 0; fi
  echo "DIVERGENTE: ${DST} difere de ${SRC} — rode scripts/sync-realm.sh" >&2; exit 1
fi
mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
echo "realm copiado para ${DST}"
