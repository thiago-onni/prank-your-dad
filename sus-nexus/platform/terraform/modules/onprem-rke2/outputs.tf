output "api_endpoint" {
  value = "https://${var.api_vip}:6443"
}

output "inventory_path" {
  value = local_file.ansible_inventory.filename
}

output "cloud_init_paths" {
  value = { for k, f in local_sensitive_file.cloud_init : k => f.filename }
}

output "servers" {
  value = [for n in local.servers : { name = n.name, ip = n.ip, zone = n.zone }]
}

output "agents" {
  value = [for n in local.agents : { name = n.name, ip = n.ip, zone = n.zone, tier = n.tier }]
}
