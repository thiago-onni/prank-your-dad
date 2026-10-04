output "cluster_name" {
  value = var.name
}

output "endpoint" {
  value = local.is_eks ? aws_eks_cluster.this[0].endpoint : (local.is_rke2 ? module.rke2[0].api_endpoint : null)
}

output "certificate_authority" {
  value     = local.is_eks ? aws_eks_cluster.this[0].certificate_authority[0].data : null
  sensitive = true
}

output "oidc_provider_arn" {
  value = local.is_eks ? aws_iam_openid_connect_provider.this[0].arn : null
}

output "oidc_issuer" {
  value = local.is_eks ? aws_eks_cluster.this[0].identity[0].oidc[0].issuer : null
}

output "node_role_arn" {
  value = local.is_eks ? aws_iam_role.node[0].arn : null
}

output "rke2_inventory_path" {
  value = local.is_rke2 ? module.rke2[0].inventory_path : null
}

output "rke2_cloud_init_paths" {
  value = local.is_rke2 ? module.rke2[0].cloud_init_paths : {}
}
