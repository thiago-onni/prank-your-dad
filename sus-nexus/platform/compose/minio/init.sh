#!/bin/sh
# Inicializa buckets do MinIO local (idempotente). Executado pelo serviço `minio-init` (imagem minio/mc).
#
# Buckets (ver PLANO_IMPLEMENTACAO.md 4.2.3):
#   raw-zone       mensagens brutas dos conectores (versionado; retenção por política)
#   documents      documentos clínicos/administrativos referenciados por data_ref
#   exports        exportações (expiram em 30 dias)
#   backups        backups CNPG/WAL (versionado)
#   audit-archive  arquivo WORM de auditoria — criado com Object Lock; em dev usa GOVERNANCE para permitir limpeza.
#                  Em hml/prod: COMPLIANCE (ver platform/terraform/modules/storage e helm minio Tenant).
#   lakehouse      tabelas Apache Iceberg (bronze/silver/gold) — Kafka Connect Iceberg sink + Trino (Fase 4)
set -eu

MINIO_URL="${MINIO_URL:-http://minio:9000}"
ALIAS="local"
RETENTION_DAYS="${AUDIT_ARCHIVE_RETENTION_DAYS:-30}"
LOCK_MODE="${AUDIT_ARCHIVE_LOCK_MODE:-GOVERNANCE}"

echo ">> conectando em ${MINIO_URL}"
i=0
until mc alias set "$ALIAS" "$MINIO_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1; do
  i=$((i + 1)); [ "$i" -gt 30 ] && { echo "MinIO indisponível" >&2; exit 1; }
  sleep 2
done

mk() { # mk <bucket> [--with-lock]
  if mc ls "$ALIAS/$1" >/dev/null 2>&1; then
    echo ">> bucket $1 já existe"
  else
    echo ">> criando bucket $1 $2"
    # shellcheck disable=SC2086
    mc mb $2 "$ALIAS/$1"
  fi
}

mk raw-zone ""
mk documents ""
mk exports ""
mk backups ""
mk audit-archive "--with-lock"
mk lakehouse ""

mc version enable "$ALIAS/raw-zone" >/dev/null
mc version enable "$ALIAS/backups" >/dev/null
mc version enable "$ALIAS/audit-archive" >/dev/null

# WORM para auditoria
mc retention set --default "$LOCK_MODE" "${RETENTION_DAYS}d" "$ALIAS/audit-archive" >/dev/null
echo ">> audit-archive: object-lock ${LOCK_MODE} ${RETENTION_DAYS}d"

# Expiração de exportações
if ! mc ilm rule ls "$ALIAS/exports" 2>/dev/null | grep -q 'expire-exports'; then
  mc ilm rule add --expire-days 30 --tags "policy=expire-exports" "$ALIAS/exports" >/dev/null 2>&1 \
    || mc ilm rule add --expire-days 30 "$ALIAS/exports" >/dev/null
  echo ">> exports: expiração em 30 dias"
fi

# Usuário de aplicação com política restrita (sem acesso a backups)
cat > /tmp/sus-app-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucket", "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:GetObjectRetention", "s3:PutObjectRetention"],
      "Resource": ["arn:aws:s3:::raw-zone", "arn:aws:s3:::raw-zone/*", "arn:aws:s3:::documents", "arn:aws:s3:::documents/*", "arn:aws:s3:::exports", "arn:aws:s3:::exports/*"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucket", "s3:PutObject", "s3:GetObject", "s3:PutObjectRetention"],
      "Resource": ["arn:aws:s3:::audit-archive", "arn:aws:s3:::audit-archive/*"]
    }
  ]
}
EOF
mc admin policy create "$ALIAS" sus-app /tmp/sus-app-policy.json >/dev/null 2>&1 || true
mc admin user add "$ALIAS" "${SUS_S3_ACCESS_KEY:-sus-app}" "${SUS_S3_SECRET_KEY:-sus-app-secret}" >/dev/null 2>&1 || true
mc admin policy attach "$ALIAS" sus-app --user "${SUS_S3_ACCESS_KEY:-sus-app}" >/dev/null 2>&1 || true

# Usuário do lakehouse (Kafka Connect Iceberg sink + Trino): somente o bucket lakehouse
cat > /tmp/sus-lakehouse-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:ListBucket", "s3:ListBucketMultipartUploads", "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
      "Resource": ["arn:aws:s3:::lakehouse", "arn:aws:s3:::lakehouse/*"]
    }
  ]
}
EOF
mc admin policy create "$ALIAS" sus-lakehouse /tmp/sus-lakehouse-policy.json >/dev/null 2>&1 || true
mc admin user add "$ALIAS" "${LAKEHOUSE_S3_ACCESS_KEY:-sus-lakehouse}" "${LAKEHOUSE_S3_SECRET_KEY:-sus-lakehouse-secret}" >/dev/null 2>&1 || true
mc admin policy attach "$ALIAS" sus-lakehouse --user "${LAKEHOUSE_S3_ACCESS_KEY:-sus-lakehouse}" >/dev/null 2>&1 || true

echo ">> buckets:"
mc ls "$ALIAS"
