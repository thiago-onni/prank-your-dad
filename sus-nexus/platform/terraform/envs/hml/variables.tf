variable "rke2_cluster_token" {
  description = "Token do cluster RKE2 (TF_VAR_rke2_cluster_token, vindo do OpenBao)."
  type        = string
  sensitive   = true
}

variable "ssh_authorized_keys" {
  type    = list(string)
  default = []
}

variable "minio_endpoint" {
  type    = string
  default = "minio.hml.sus-nexus.local"
}

variable "minio_access_key" {
  type      = string
  sensitive = true
}

variable "minio_secret_key" {
  type      = string
  sensitive = true
}
