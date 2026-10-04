# Módulo onprem-rke2 — gera artefatos de provisionamento (cloud-init por nó, config.yaml do RKE2 por nó
# e inventário Ansible) a partir de uma lista de nós. Não provisiona VMs: o hipervisor (Proxmox/vSphere/
# bare-metal via PXE) consome o cloud-init; o playbook Ansible (sus-nexus-infra/ansible) aplica o RKE2.

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    local = {
      source  = "hashicorp/local"
      version = ">= 2.5"
    }
  }
}

locals {
  servers       = [for n in var.nodes : n if n.role == "server"]
  agents        = [for n in var.nodes : n if n.role == "agent"]
  first_server  = local.servers[0]
  other_servers = slice(local.servers, 1, length(local.servers))
  tls_sans      = distinct(concat([var.api_vip], var.cluster_domain_sans, [for n in local.servers : n.ip]))

  node_labels = {
    for n in var.nodes : n.name => merge(
      {
        "sus-nexus.gov.br/tier"             = n.tier
        "topology.kubernetes.io/zone"       = n.zone
        "node-role.kubernetes.io/${n.tier}" = "true"
      },
      n.labels
    )
  }

  rke2_config = {
    for n in var.nodes : n.name => templatefile("${path.module}/templates/rke2-config.yaml.tftpl", {
      node         = n
      is_server    = n.role == "server"
      is_first     = n.name == local.first_server.name
      server_url   = "https://${var.api_vip}:9345"
      token        = var.cluster_token
      cis_profile  = var.cis_profile
      cni          = var.cni
      tls_sans     = local.tls_sans
      labels       = local.node_labels[n.name]
      taints       = n.taints
      cluster_name = var.cluster_name
    })
  }
}

resource "local_sensitive_file" "rke2_config" {
  for_each        = { for n in var.nodes : n.name => n }
  filename        = "${var.output_dir}/${each.key}/rke2-config.yaml"
  content         = local.rke2_config[each.key]
  file_permission = "0600"
}

resource "local_sensitive_file" "cloud_init" {
  for_each = { for n in var.nodes : n.name => n }
  filename = "${var.output_dir}/${each.key}/user-data.yaml"
  content = templatefile("${path.module}/templates/cloud-init.yaml.tftpl", {
    node                = each.value
    hostname            = each.key
    ssh_user            = var.ssh_user
    ssh_authorized_keys = var.ssh_authorized_keys
    ntp_servers         = var.ntp_servers
    dns_servers         = var.dns_servers
    rke2_version        = var.rke2_version
    is_server           = each.value.role == "server"
    rke2_config         = local.rke2_config[each.key]
    registry_mirror     = var.registry_mirror
  })
  file_permission = "0600"
}

resource "local_file" "ansible_inventory" {
  filename = "${var.output_dir}/inventory.ini"
  content = templatefile("${path.module}/templates/inventory.ini.tftpl", {
    cluster_name  = var.cluster_name
    environment   = var.environment
    first_server  = local.first_server
    other_servers = local.other_servers
    agents        = local.agents
    ssh_user      = var.ssh_user
    api_vip       = var.api_vip
    rke2_version  = var.rke2_version
  })
  file_permission = "0644"
}

resource "local_file" "kube_vip" {
  filename = "${var.output_dir}/kube-vip.yaml"
  content = templatefile("${path.module}/templates/kube-vip.yaml.tftpl", {
    api_vip = var.api_vip
  })
  file_permission = "0644"
}
