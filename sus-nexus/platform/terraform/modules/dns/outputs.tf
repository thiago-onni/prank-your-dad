output "zone_id" {
  value = local.zone_id
}

output "name_servers" {
  value = local.is_r53 && var.create_zone ? aws_route53_zone.this[0].name_servers : []
}

output "active_target" {
  value = local.target
}

output "records" {
  value = { for k, r in local.all_records : k => "${k}.${var.zone_name} ${r.type} ${join(",", r.values)}" }
}

output "bind_zone_file" {
  value = local.is_bind ? local_file.bind_zone[0].filename : null
}
