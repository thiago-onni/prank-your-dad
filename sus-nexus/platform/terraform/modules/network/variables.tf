variable "provider_kind" {
  description = "Provedor de rede: \"aws\" cria VPC/sub-redes; \"onprem\" apenas valida e exporta os CIDRs informados (VLANs já existentes)."
  type        = string
  default     = "aws"
  validation {
    condition     = contains(["aws", "onprem"], var.provider_kind)
    error_message = "provider_kind deve ser aws ou onprem."
  }
}

variable "name" {
  description = "Prefixo de nomes (ex.: sus-nexus-prod)."
  type        = string
}

variable "environment" {
  description = "dev | hml | prod | dr"
  type        = string
}

variable "cidr" {
  description = "CIDR da VPC / rede do site."
  type        = string
  default     = "10.10.0.0/16"
}

variable "availability_zones" {
  description = "Zonas (AWS AZs ou salas/racks on-prem). Mínimo 2 em prod."
  type        = list(string)
  default     = ["sa-east-1a", "sa-east-1b"]
}

# Segmentação PLANO 4.2.1: borda (DMZ), aplicação, dados, gestão. Uma sub-rede por zona por segmento.
variable "subnets" {
  description = "CIDRs por segmento, um por zona (mesma ordem de availability_zones)."
  type = object({
    dmz  = list(string)
    app  = list(string)
    data = list(string)
    mgmt = list(string)
  })
  default = {
    dmz  = ["10.10.0.0/24", "10.10.1.0/24"]
    app  = ["10.10.16.0/20", "10.10.32.0/20"]
    data = ["10.10.64.0/22", "10.10.68.0/22"]
    mgmt = ["10.10.250.0/24", "10.10.251.0/24"]
  }
}

variable "enable_nat_gateway" {
  description = "NAT por zona para as sub-redes app/data (saída para internet via egress controlado)."
  type        = bool
  default     = true
}

variable "flow_logs_retention_days" {
  type    = number
  default = 90
}

variable "health_units_cidrs" {
  description = "Redes das unidades de saúde (VPN site-to-site / rede municipal) autorizadas a alcançar a DMZ."
  type        = list(string)
  default     = []
}

variable "tags" {
  type    = map(string)
  default = {}
}
