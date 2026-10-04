output "cluster_endpoint" {
  value = module.cluster.endpoint
}

output "ansible_inventory" {
  value = module.cluster.rke2_inventory_path
}

output "cloud_init_files" {
  value = module.cluster.rke2_cloud_init_paths
}

output "bind_zone_file" {
  value = module.dns.bind_zone_file
}

output "buckets" {
  value = module.storage.bucket_names
}

output "harbor_values_file" {
  value = module.registry.values_file
}
