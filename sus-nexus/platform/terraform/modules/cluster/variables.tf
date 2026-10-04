variable "provider_kind" {
  description = "eks | rke2"
  type        = string
  validation {
    condition     = contains(["eks", "rke2"], var.provider_kind)
    error_message = "provider_kind deve ser eks ou rke2."
  }
}

variable "name" {
  type = string
}

variable "environment" {
  type = string
}

variable "kubernetes_version" {
  type    = string
  default = "1.30"
}

variable "tags" {
  type    = map(string)
  default = {}
}

# ---- EKS ----
variable "vpc_id" {
  type    = string
  default = null
}

variable "subnet_ids" {
  description = "Sub-redes do segmento app (nós) — saída do módulo network."
  type        = list(string)
  default     = []
}

variable "control_plane_subnet_ids" {
  description = "Sub-redes do segmento mgmt para as ENIs do control plane."
  type        = list(string)
  default     = []
}

variable "api_allowed_cidrs" {
  description = "CIDRs com acesso ao endpoint público do API (vazio = endpoint só privado)."
  type        = list(string)
  default     = []
}

variable "node_groups" {
  description = "Node groups EKS. Dimensionamento PLANO 3.5."
  type = map(object({
    instance_types = list(string)
    min_size       = number
    max_size       = number
    desired_size   = number
    disk_size      = optional(number, 100)
    capacity_type  = optional(string, "ON_DEMAND")
    labels         = optional(map(string), {})
    taints = optional(list(object({
      key    = string
      value  = string
      effect = string
    })), [])
  }))
  default = {
    app = {
      instance_types = ["m6i.2xlarge"]
      min_size       = 3
      max_size       = 9
      desired_size   = 6
      labels         = { "sus-nexus.gov.br/tier" = "worker" }
    }
    data = {
      instance_types = ["r6i.2xlarge"]
      min_size       = 3
      max_size       = 6
      desired_size   = 3
      disk_size      = 200
      labels         = { "sus-nexus.gov.br/tier" = "data" }
      taints         = [{ key = "sus-nexus.gov.br/tier", value = "data", effect = "NO_SCHEDULE" }]
    }
    observability = {
      instance_types = ["m6i.xlarge"]
      min_size       = 2
      max_size       = 5
      desired_size   = 3
      labels         = { "sus-nexus.gov.br/tier" = "observability" }
    }
  }
}

variable "kms_key_arn" {
  description = "KMS para criptografia de secrets do etcd (EKS)."
  type        = string
  default     = null
}

# ---- RKE2 ----
variable "rke2" {
  description = "Parâmetros do módulo onprem-rke2 (usado quando provider_kind = rke2)."
  type = object({
    api_vip             = optional(string, "")
    rke2_version        = optional(string, "v1.30.6+rke2r1")
    cluster_token       = optional(string, "")
    ssh_authorized_keys = optional(list(string), [])
    registry_mirror     = optional(string, "")
    cluster_domain_sans = optional(list(string), [])
    output_dir          = optional(string, "./generated")
    nodes = optional(list(object({
      name   = string
      ip     = string
      role   = string
      tier   = optional(string, "worker")
      zone   = optional(string, "zone-a")
      labels = optional(map(string), {})
      taints = optional(list(string), [])
      disks = optional(object({
        fast_nvme = optional(string, "")
        standard  = optional(string, "")
      }), {})
    })), [])
  })
  default   = {}
  sensitive = true
}
