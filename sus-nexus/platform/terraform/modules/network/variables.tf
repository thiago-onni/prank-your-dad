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
  description = "Retenção (dias) do log group dos flow logs. Mínimo 365 (0 = nunca expira)."
  type        = number
  default     = 365
  validation {
    condition     = var.flow_logs_retention_days == 0 || var.flow_logs_retention_days >= 365
    error_message = "flow_logs_retention_days deve ser 0 (sem expiração) ou >= 365."
  }
}

variable "kms_key_arn" {
  description = "KMS para cifrar o log group dos flow logs (AWS). A key policy precisa autorizar logs.<região>.amazonaws.com."
  type        = string
  default     = null
}

variable "data_ingress_ports" {
  description = "Portas aceitas pelo NACL do segmento de dados a partir dos segmentos app/mgmt/data (serviços de dados + kubelet/NodePort)."
  type = list(object({
    protocol  = optional(string, "tcp")
    from_port = number
    to_port   = number
  }))
  default = [
    { from_port = 5432, to_port = 5432 },   # PostgreSQL (CloudNativePG)
    { from_port = 6379, to_port = 6379 },   # Valkey/Redis
    { from_port = 9092, to_port = 9094 },   # Kafka (plaintext interno / TLS / controller)
    { from_port = 9000, to_port = 9001 },   # MinIO API / console
    { from_port = 9200, to_port = 9200 },   # OpenSearch
    { from_port = 10250, to_port = 10250 }, # kubelet (control plane -> nós de dados)
    { from_port = 30000, to_port = 32767 }, # NodePort (LBs internos)
  ]
  validation {
    condition     = length(var.data_ingress_ports) <= 20 && alltrue([for p in var.data_ingress_ports : p.from_port >= 1 && p.to_port <= 65535 && p.from_port <= p.to_port])
    error_message = "data_ingress_ports: no máximo 20 entradas, com 1 <= from_port <= to_port <= 65535."
  }
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
