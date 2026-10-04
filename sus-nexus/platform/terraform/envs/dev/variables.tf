variable "aws_region" {
  type    = string
  default = "sa-east-1"
}

variable "ibge_code" {
  description = "Código IBGE do município (compõe nomes globais de bucket)."
  type        = string
  default     = "3143302"
}

variable "api_allowed_cidrs" {
  description = "CIDRs com acesso ao endpoint público do API server (VPN/escritório). Vazio = só privado."
  type        = list(string)
  default     = []
}

variable "health_units_cidrs" {
  type    = list(string)
  default = []
}

variable "apisix_lb_hostname" {
  description = "Hostname do LB do APISIX (saída do chart apisix) para os registros DNS."
  type        = string
  default     = "apisix-dev-placeholder.elb.sa-east-1.amazonaws.com"
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
