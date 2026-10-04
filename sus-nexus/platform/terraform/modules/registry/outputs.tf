output "values_file" {
  value = local_file.harbor_values.filename
}

output "config_file" {
  value = local_file.harbor_config.filename
}

output "registry_url" {
  value = var.hostname
}

output "image_prefix" {
  value = "${var.hostname}/sus-nexus"
}
