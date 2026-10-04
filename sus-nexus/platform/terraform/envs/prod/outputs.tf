output "cluster_endpoint" {
  value = module.cluster.endpoint
}

output "oidc_provider_arn" {
  value = module.cluster.oidc_provider_arn
}

output "kms_key_arn" {
  value = aws_kms_key.platform.arn
}

output "buckets" {
  value = module.storage.bucket_names
}

output "audit_archive_bucket" {
  value = module.storage.audit_archive_bucket
}

output "dns_active_target" {
  value = module.dns.active_target
}

output "harbor_values_file" {
  value = module.registry.values_file
}
