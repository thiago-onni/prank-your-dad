#!/usr/bin/env bash
# Baixa o driver Trino (Starburst) do Metabase para ./plugins (montado em /plugins no serviço metabase).
# Compatibilidade: https://github.com/starburstdata/metabase-driver#compatibility (Metabase 0.52 → driver 5.x/6.x).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="${STARBURST_DRIVER_VERSION:-6.1.0}"
mkdir -p "${SCRIPT_DIR}/plugins"
url="https://github.com/starburstdata/metabase-driver/releases/download/${VERSION}/starburst-${VERSION}.metabase-driver.jar"
echo ">> baixando ${url}"
curl -fsSL -o "${SCRIPT_DIR}/plugins/starburst-${VERSION}.metabase-driver.jar" "$url"
echo ">> ok — reinicie o metabase: docker compose restart metabase"
