variable "provider_kind" {
  description = "s3 (AWS) | minio (on-prem, provider aminueza/minio)"
  type        = string
  default     = "s3"
  validation {
    condition     = contains(["s3", "minio"], var.provider_kind)
    error_message = "provider_kind deve ser s3 ou minio."
  }
}

variable "name_prefix" {
  description = "Prefixo dos buckets (globalmente único no S3). Ex.: sus-nexus-prod-3143302"
  type        = string
}

variable "environment" {
  type = string
}

variable "kms_key_arn" {
  description = "KMS para SSE (S3). null = SSE-S3 (AES256)."
  type        = string
  default     = null
}

# PLANO 4.2.3: raw-zone, documents, exports (expira), backups, audit-archive (WORM), lakehouse (F4)
variable "buckets" {
  type = map(object({
    versioning             = optional(bool, true)
    object_lock            = optional(bool, false)
    lock_mode              = optional(string, "COMPLIANCE")
    lock_retention_days    = optional(number, 0)
    expire_days            = optional(number, 0)
    noncurrent_expire_days = optional(number, 0)
    replicate_to_dr        = optional(bool, false)
  }))
  default = {
    raw-zone      = { versioning = true, noncurrent_expire_days = 90, replicate_to_dr = true }
    documents     = { versioning = true, replicate_to_dr = true }
    exports       = { versioning = false, expire_days = 30 }
    backups       = { versioning = true, noncurrent_expire_days = 35, replicate_to_dr = true }
    audit-archive = { versioning = true, object_lock = true, lock_mode = "COMPLIANCE", lock_retention_days = 1825, replicate_to_dr = true }
    loki-chunks   = { versioning = false, expire_days = 45 }
    loki-ruler    = { versioning = false }
    loki-admin    = { versioning = false }
    tempo-traces  = { versioning = false, expire_days = 21 }
  }
}

variable "dr_replication" {
  description = "Replicação para o site DR (S3 CRR ou MinIO site replication). role_arn só para S3."
  type = object({
    enabled            = bool
    target_prefix      = optional(string, "")
    target_region      = optional(string, "")
    role_arn           = optional(string, "")
    target_kms_key_arn = optional(string, "")
  })
  default = { enabled = false }
}

variable "tags" {
  type    = map(string)
  default = {}
}
