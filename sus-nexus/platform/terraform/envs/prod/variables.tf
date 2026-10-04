variable "aws_region" {
  type    = string
  default = "sa-east-1"
}

variable "dr_region" {
  description = "Região/site DR (buckets replicados). Dados de saúde devem permanecer no Brasil."
  type        = string
  default     = "sa-east-1"
}

variable "ibge_code" {
  type    = string
  default = "3143302"
}

variable "health_units_cidrs" {
  description = "Redes das unidades de saúde (VPN site-to-site)."
  type        = list(string)
  default     = []
}

variable "active_site" {
  description = "primary | dr — troque para dr no failover (RUNBOOK-DR 3.2 passo 8)."
  type        = string
  default     = "primary"
}

variable "apisix_lb_hostname" {
  type = string
}

variable "apisix_dr_lb_hostname" {
  type = string
}

variable "s3_replication_role_arn" {
  description = "Role IAM com permissão de replicação S3 para os buckets DR."
  type        = string
}

variable "dr_kms_key_arn" {
  type    = string
  default = ""
}

variable "minio_endpoint" {
  type    = string
  default = "localhost:9000"
}

variable "minio_access_key" {
  type      = string
  default   = "unused"
  sensitive = true
}

variable "minio_secret_key" {
  type      = string
  default   = "unused"
  sensitive = true
}
