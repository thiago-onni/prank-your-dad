#!/usr/bin/env bash
# Gera bundle/bundle.tar.gz com manifesto (roots: sus, data) a partir de
# policies/sus (sem *_test.rego) e policies/data.
#
# Uso: REVISION=1.2.0+abc123 ./bundle/build.sh
set -euo pipefail

OPA="${OPA:-opa}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POLICIES="$(cd "$HERE/.." && pwd)"
STAGE="$HERE/.stage"
OUT="$HERE/bundle.tar.gz"
VERSION="$(cat "$POLICIES/VERSION" 2>/dev/null || echo 0.0.0-dev)"
REVISION="${REVISION:-$VERSION}"

rm -rf "$STAGE"
mkdir -p "$STAGE"

# Copia políticas (sem testes) e dados estáticos.
rsync -a --exclude '*_test.rego' "$POLICIES/sus" "$STAGE/" 2>/dev/null || {
  mkdir -p "$STAGE/sus"
  (cd "$POLICIES/sus" && find . -name '*.rego' ! -name '*_test.rego' -exec cp --parents {} "$STAGE/sus/" \;)
}
cp -r "$POLICIES/data" "$STAGE/data"

cat > "$STAGE/.manifest" <<EOF
{
  "revision": "${REVISION}",
  "roots": ["sus", "data"],
  "metadata": {
    "name": "sus-nexus-policies",
    "version": "${VERSION}",
    "rego_version": 1,
    "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  }
}
EOF

"$OPA" check --strict "$STAGE/sus" "$STAGE/data"
(cd "$STAGE" && "$OPA" build -b . -o "$OUT")

echo "bundle: $OUT"
echo "revision: $REVISION"
tar -tzf "$OUT" | sed 's/^/  /'
"$OPA" inspect "$OUT" | sed 's/^/  /'
rm -rf "$STAGE"
