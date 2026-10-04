output "cluster_endpoint" {
  value = module.cluster.endpoint
}

output "oidc_provider_arn" {
  value = module.cluster.oidc_provider_arn
}

output "subnet_ids" {
  value = module.network.subnet_ids
}

output "buckets" {
  value = module.storage.bucket_names
}

output "dns_records" {
  value = module.dns.records
}

output "harbor_values_file" {
  value = module.registry.values_file
}
