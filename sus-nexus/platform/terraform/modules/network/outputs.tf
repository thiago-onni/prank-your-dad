output "vpc_id" {
  value = local.is_aws ? aws_vpc.this[0].id : null
}

output "cidr" {
  value = var.cidr
}

output "subnet_ids" {
  description = "IDs de sub-rede por segmento (AWS); vazio on-prem."
  value = {
    for seg in keys(local.segments) : seg => [
      for k, s in aws_subnet.this : s.id if local.subnet_map[k].seg == seg
    ]
  }
}

output "subnet_cidrs" {
  description = "CIDRs por segmento (ambos os provedores)."
  value       = var.subnets
}

output "availability_zones" {
  value = var.availability_zones
}

output "dmz_security_group_id" {
  description = "SG base da borda (AWS); null on-prem. Aplicado ao control plane pelo módulo cluster."
  value       = local.is_aws ? aws_security_group.dmz[0].id : null
}

output "nat_public_ips" {
  value = [for e in aws_eip.nat : e.public_ip]
}
