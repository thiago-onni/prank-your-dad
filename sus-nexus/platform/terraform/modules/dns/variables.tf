variable "provider_kind" {
  description = "route53 | bind (gera arquivo de zona para o DNS municipal/interno)"
  type        = string
  default     = "route53"
  validation {
    condition     = contains(["route53", "bind"], var.provider_kind)
    error_message = "provider_kind deve ser route53 ou bind."
  }
}

variable "zone_name" {
  description = "Zona DNS. Ex.: sus-nexus.saude.montesclaros.mg.gov.br"
  type        = string
}

variable "create_zone" {
  type    = bool
  default = true
}

variable "private_zone" {
  description = "Zona privada (Route53 associada à VPC) para nomes internos."
  type        = bool
  default     = false
}

variable "vpc_id" {
  type    = string
  default = null
}

variable "ttl" {
  description = "TTL baixo para facilitar failover (RUNBOOK-DR)."
  type        = number
  default     = 60
}

variable "active_site" {
  description = "primary | dr — qual site recebe os registros de serviço (failover por terraform apply)."
  type        = string
  default     = "primary"
  validation {
    condition     = contains(["primary", "dr"], var.active_site)
    error_message = "active_site deve ser primary ou dr."
  }
}

variable "site_targets" {
  description = "Alvo (IP ou hostname do LB do APISIX) por site."
  type = object({
    primary = string
    dr      = string
  })
}

variable "service_hosts" {
  description = "Hostnames de serviço que seguem o site ativo (registro A/CNAME → site_targets[active_site])."
  type        = list(string)
  default     = ["api", "app", "fhir", "auth", "grafana", "argocd", "harbor", "minio-console", "kafka-external"]
}

variable "extra_records" {
  description = "Registros adicionais estáticos."
  type = map(object({
    type   = string
    ttl    = optional(number, 300)
    values = list(string)
  }))
  default = {}
}

variable "output_dir" {
  type    = string
  default = "./generated"
}

variable "tags" {
  type    = map(string)
  default = {}
}
