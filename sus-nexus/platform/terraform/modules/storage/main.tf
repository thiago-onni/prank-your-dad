# Módulo storage — buckets com versionamento, Object Lock (WORM) para audit-archive, ciclo de vida e
# replicação para DR. S3 (AWS) ou MinIO (provider aminueza/minio, apontando para o Tenant on-prem).

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.60"
    }
    minio = {
      source  = "aminueza/minio"
      version = ">= 2.5"
    }
  }
}

locals {
  is_s3    = var.provider_kind == "s3"
  is_minio = var.provider_kind == "minio"
  tags = merge(var.tags, {
    "sus-nexus.gov.br/environment" = var.environment
    "sus-nexus.gov.br/managed-by"  = "terraform"
  })
  s3_buckets    = local.is_s3 ? var.buckets : {}
  minio_buckets = local.is_minio ? var.buckets : {}
  locked        = { for k, b in var.buckets : k => b if b.object_lock }
  replicated    = { for k, b in var.buckets : k => b if b.replicate_to_dr && var.dr_replication.enabled }
}

# ------------------------------------------------------------------------------------------------
# AWS S3
# ------------------------------------------------------------------------------------------------
data "aws_caller_identity" "current" {
  count = local.is_s3 ? 1 : 0
}

resource "aws_s3_bucket" "this" {
  for_each            = local.s3_buckets
  bucket              = "${var.name_prefix}-${each.key}"
  object_lock_enabled = each.value.object_lock
  force_destroy       = false
  tags                = merge(local.tags, { Name = "${var.name_prefix}-${each.key}" })
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = local.s3_buckets
  bucket                  = aws_s3_bucket.this[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = local.s3_buckets
  bucket   = aws_s3_bucket.this[each.key].id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Versionamento sempre habilitado no S3 (CKV_AWS_21; Object Lock também o exige). Para buckets declarados com
# `versioning = false` as versões não-correntes são purgadas em 1 dia pelo ciclo de vida (regra purge-noncurrent),
# preservando o comportamento "sem histórico" sem abrir mão da proteção contra sobrescrita acidental.
resource "aws_s3_bucket_versioning" "this" {
  for_each = local.s3_buckets
  bucket   = aws_s3_bucket.this[each.key].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = local.s3_buckets
  bucket   = aws_s3_bucket.this[each.key].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key_arn != null ? "aws:kms" : "AES256"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = var.kms_key_arn != null
  }
}

resource "aws_s3_bucket_object_lock_configuration" "this" {
  for_each = local.is_s3 ? local.locked : {}
  bucket   = aws_s3_bucket.this[each.key].id
  rule {
    default_retention {
      mode = each.value.lock_mode
      days = each.value.lock_retention_days
    }
  }
  depends_on = [aws_s3_bucket_versioning.this]
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  for_each = local.s3_buckets
  bucket   = aws_s3_bucket.this[each.key].id

  # Uploads multipart abandonados são descartados em 7 dias (CKV_AWS_300). A ação é repetida nas demais regras
  # (S3 aceita a mesma ação em regras sobrepostas) para que a verificação estática a enxergue em qualquer caminho.
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  dynamic "rule" {
    for_each = each.value.expire_days > 0 ? [1] : []
    content {
      id     = "expire-current"
      status = "Enabled"
      filter {}
      expiration {
        days = each.value.expire_days
      }
      abort_incomplete_multipart_upload {
        days_after_initiation = 7
      }
    }
  }

  dynamic "rule" {
    for_each = each.value.noncurrent_expire_days > 0 ? [1] : []
    content {
      id     = "expire-noncurrent"
      status = "Enabled"
      filter {}
      noncurrent_version_expiration {
        noncurrent_days = each.value.noncurrent_expire_days
      }
      abort_incomplete_multipart_upload {
        days_after_initiation = 7
      }
    }
  }

  # Buckets "sem versionamento" (versioning = false): purga versões antigas em 1 dia e os delete markers
  # órfãos (quando não há expiração por dias, que já os remove).
  dynamic "rule" {
    for_each = (!each.value.versioning && !each.value.object_lock && each.value.noncurrent_expire_days == 0) ? [1] : []
    content {
      id     = "purge-noncurrent"
      status = "Enabled"
      filter {}
      noncurrent_version_expiration {
        noncurrent_days = 1
      }
      dynamic "expiration" {
        for_each = each.value.expire_days == 0 ? [1] : []
        content {
          expired_object_delete_marker = true
        }
      }
      abort_incomplete_multipart_upload {
        days_after_initiation = 7
      }
    }
  }
  depends_on = [aws_s3_bucket_versioning.this]
}

# TLS obrigatório
resource "aws_s3_bucket_policy" "tls_only" {
  for_each = local.s3_buckets
  bucket   = aws_s3_bucket.this[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.this[each.key].arn, "${aws_s3_bucket.this[each.key].arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.this]
}

# Eventos para o EventBridge (CKV2_AWS_62): auditoria de acesso/alteração consumida pela trilha LGPD.
resource "aws_s3_bucket_notification" "this" {
  for_each    = local.s3_buckets
  bucket      = aws_s3_bucket.this[each.key].id
  eventbridge = true
}

# Server access logging (CKV_AWS_18): todos os buckets logam no bucket <prefixo>-access-logs, um prefixo por bucket.
resource "aws_s3_bucket_logging" "this" {
  for_each      = local.s3_buckets
  bucket        = aws_s3_bucket.this[each.key].id
  target_bucket = aws_s3_bucket.access_logs[0].id
  target_prefix = "${each.key}/"
  depends_on    = [aws_s3_bucket_policy.access_logs]
}

resource "aws_s3_bucket_replication_configuration" "dr" {
  for_each = local.is_s3 ? local.replicated : {}
  bucket   = aws_s3_bucket.this[each.key].id
  role     = var.dr_replication.role_arn
  rule {
    id     = "to-dr"
    status = "Enabled"
    filter {}
    delete_marker_replication {
      status = "Disabled"
    }
    destination {
      bucket        = "arn:aws:s3:::${var.dr_replication.target_prefix}-${each.key}"
      storage_class = "STANDARD"
      dynamic "encryption_configuration" {
        for_each = var.dr_replication.target_kms_key_arn != "" ? [1] : []
        content {
          replica_kms_key_id = var.dr_replication.target_kms_key_arn
        }
      }
      replication_time {
        status = "Enabled"
        time {
          minutes = 15 # RPO ≤ 15 min (PLANO 4.2.6)
        }
      }
      metrics {
        status = "Enabled"
        event_threshold {
          minutes = 15
        }
      }
    }
    dynamic "source_selection_criteria" {
      for_each = var.dr_replication.target_kms_key_arn != "" ? [1] : []
      content {
        sse_kms_encrypted_objects {
          status = "Enabled"
        }
      }
    }
  }
  depends_on = [aws_s3_bucket_versioning.this]
}

# ------------------------------------------------------------------------------------------------
# AWS S3 — bucket de server access logs (destino do logging de todos os buckets acima)
# ------------------------------------------------------------------------------------------------
resource "aws_s3_bucket" "access_logs" {
  #checkov:skip=CKV_AWS_18:Bucket destino do server access logging; logar nele mesmo gera laço (recomendação AWS).
  #checkov:skip=CKV_AWS_144:Logs de acesso S3 ficam só no site primário; não fazem parte do escopo de RPO do DR.
  count         = local.is_s3 ? 1 : 0
  bucket        = "${var.name_prefix}-access-logs"
  force_destroy = false
  tags          = merge(local.tags, { Name = "${var.name_prefix}-access-logs" })
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  count                   = local.is_s3 ? 1 : 0
  bucket                  = aws_s3_bucket.access_logs[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "access_logs" {
  count  = local.is_s3 ? 1 : 0
  bucket = aws_s3_bucket.access_logs[0].id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "access_logs" {
  count  = local.is_s3 ? 1 : 0
  bucket = aws_s3_bucket.access_logs[0].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  count  = local.is_s3 ? 1 : 0
  bucket = aws_s3_bucket.access_logs[0].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = var.kms_key_arn != null ? "aws:kms" : "AES256"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = var.kms_key_arn != null
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  count  = local.is_s3 ? 1 : 0
  bucket = aws_s3_bucket.access_logs[0].id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "expire-access-logs"
    status = "Enabled"
    filter {}
    expiration {
      days = var.access_logs_retention_days
    }
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
  depends_on = [aws_s3_bucket_versioning.access_logs]
}

resource "aws_s3_bucket_notification" "access_logs" {
  count       = local.is_s3 ? 1 : 0
  bucket      = aws_s3_bucket.access_logs[0].id
  eventbridge = true
}

# TLS obrigatório + permissão para o serviço de logging do S3 gravar (necessária com BucketOwnerEnforced).
resource "aws_s3_bucket_policy" "access_logs" {
  count  = local.is_s3 ? 1 : 0
  bucket = aws_s3_bucket.access_logs[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.access_logs[0].arn, "${aws_s3_bucket.access_logs[0].arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      },
      {
        Sid       = "S3ServerAccessLogsPolicy"
        Effect    = "Allow"
        Principal = { Service = "logging.s3.amazonaws.com" }
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.access_logs[0].arn}/*"
        Condition = {
          StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current[0].account_id }
          ArnLike      = { "aws:SourceArn" = [for b in aws_s3_bucket.this : b.arn] }
        }
      }
    ]
  })
  depends_on = [aws_s3_bucket_public_access_block.access_logs]
}

# ------------------------------------------------------------------------------------------------
# MinIO (on-prem) — Tenant criado pelo MinIO Operator (helm umbrella); aqui só buckets/políticas.
# ------------------------------------------------------------------------------------------------
resource "minio_s3_bucket" "this" {
  for_each       = local.minio_buckets
  bucket         = each.key
  acl            = "private"
  object_locking = each.value.object_lock
  force_destroy  = false
}

resource "minio_s3_bucket_versioning" "this" {
  for_each = { for k, b in local.minio_buckets : k => b if b.versioning || b.object_lock }
  bucket   = minio_s3_bucket.this[each.key].bucket
  versioning_configuration {
    status = "Enabled"
  }
}

# NOTA: o provider aminueza/minio não expõe retenção padrão de Object Lock. O bucket é criado com
# object_locking=true aqui e a retenção padrão (COMPLIANCE, lock_retention_days) é aplicada pelo Job
# `minio-buckets` do chart umbrella (`mc retention set --default ...`) — ver helm/sus-nexus/templates/minio-tenant.yaml.

resource "minio_ilm_policy" "this" {
  for_each = { for k, b in local.minio_buckets : k => b if b.expire_days > 0 || b.noncurrent_expire_days > 0 }
  bucket   = minio_s3_bucket.this[each.key].bucket

  dynamic "rule" {
    for_each = each.value.expire_days > 0 ? [1] : []
    content {
      id         = "expire-current"
      expiration = "${each.value.expire_days}d"
    }
  }
  dynamic "rule" {
    for_each = each.value.noncurrent_expire_days > 0 ? [1] : []
    content {
      id                    = "expire-noncurrent"
      noncurrent_expiration = "${each.value.noncurrent_expire_days}d"
    }
  }
}
