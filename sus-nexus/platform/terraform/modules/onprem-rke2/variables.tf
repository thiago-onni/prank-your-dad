variable "cluster_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "rke2_version" {
  description = "Versão do RKE2 (canal estável). Ex.: v1.30.6+rke2r1"
  type        = string
  default     = "v1.30.6+rke2r1"
}

variable "cis_profile" {
  description = "Perfil CIS do RKE2 (\"cis\" aplica hardening de kubelet/etcd/PSA)."
  type        = string
  default     = "cis"
}

variable "cni" {
  type    = string
  default = "cilium"
}

variable "api_vip" {
  description = "VIP (kube-vip) do API server na sub-rede de gestão."
  type        = string
}

variable "cluster_domain_sans" {
  description = "SANs extras do certificado do API server (DNS do VIP, LB)."
  type        = list(string)
  default     = []
}

variable "cluster_token" {
  description = "Token compartilhado do cluster (gere com `openssl rand -hex 32`; vem do OpenBao/CI, nunca do tfvars em Git)."
  type        = string
  sensitive   = true
}

variable "ssh_user" {
  type    = string
  default = "susadmin"
}

variable "ssh_authorized_keys" {
  type    = list(string)
  default = []
}

variable "ntp_servers" {
  description = "NTP sincronizado é crítico para ordenação de eventos/auditoria (PLANO 4.2.1)."
  type        = list(string)
  default     = ["a.ntp.br", "b.ntp.br", "c.ntp.br"]
}

variable "dns_servers" {
  type    = list(string)
  default = []
}

variable "registry_mirror" {
  description = "Harbor proxy-cache para imagens (air-gapped/economia de banda). Vazio = sem mirror."
  type        = string
  default     = ""
}

variable "nodes" {
  description = "Inventário de nós. role: server (control plane) | agent (worker). tier: control|worker|data|observability."
  type = list(object({
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
  }))
  validation {
    condition     = length([for n in var.nodes : n if n.role == "server"]) % 2 == 1
    error_message = "Número de nós server (control plane/etcd) deve ser ímpar (1, 3, 5)."
  }
}

variable "output_dir" {
  description = "Diretório onde cloud-init, config RKE2 e inventário Ansible são gerados."
  type        = string
  default     = "./generated"
}
