variable "environment" {
  type = string
}

variable "hostname" {
  description = "FQDN do Harbor. Ex.: harbor.sus-nexus.local"
  type        = string
}

variable "storage" {
  description = "Backend de imagens (S3/MinIO)."
  type = object({
    bucket          = string
    endpoint        = string
    region          = optional(string, "sa-east-1")
    secure          = optional(bool, true)
    existing_secret = optional(string, "harbor-s3-credentials")
  })
}

variable "database" {
  description = "Postgres externo (CNPG) — null = Postgres interno do chart (somente dev)."
  type = object({
    host            = string
    port            = optional(number, 5432)
    existing_secret = optional(string, "harbor-db")
    sslmode         = optional(string, "require")
  })
  default = null
}

variable "oidc" {
  type = object({
    endpoint        = string
    client_id       = optional(string, "harbor")
    existing_secret = optional(string, "harbor-oidc")
    admin_group     = optional(string, "admin_municipal")
  })
}

variable "replication_targets" {
  description = "Registries de destino (DR) para replicação de imagens assinadas."
  type = list(object({
    name     = string
    url      = string
    insecure = optional(bool, false)
  }))
  default = []
}

variable "proxy_cache_projects" {
  description = "Proxy-cache para registries públicos (economia de banda / air-gapped)."
  type        = map(string)
  default = {
    dockerhub-proxy = "https://registry-1.docker.io"
    quay-proxy      = "https://quay.io"
    ghcr-proxy      = "https://ghcr.io"
    k8s-proxy       = "https://registry.k8s.io"
  }
}

variable "trivy_enabled" {
  type    = bool
  default = true
}

variable "replicas" {
  type    = number
  default = 2
}

variable "output_dir" {
  type    = string
  default = "./generated"
}
