# Módulo dns — Route53 (nuvem) ou arquivo de zona BIND (DNS interno municipal). Failover para DR =
# `active_site = "dr"` + terraform apply (TTL 60 s).

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.60"
    }
    local = {
      source  = "hashicorp/local"
      version = ">= 2.5"
    }
  }
}

locals {
  is_r53   = var.provider_kind == "route53"
  is_bind  = var.provider_kind == "bind"
  target   = var.active_site == "dr" ? var.site_targets.dr : var.site_targets.primary
  is_ip    = can(cidrhost("${local.target}/32", 0))
  rec_type = local.is_ip ? "A" : "CNAME"

  service_records = {
    for h in var.service_hosts : h => {
      type   = local.rec_type
      ttl    = var.ttl
      values = [local.target]
    }
  }
  all_records = merge(local.service_records, var.extra_records)
}

# ------------------------------------------------------------------------------------------------
# Route53
# ------------------------------------------------------------------------------------------------
resource "aws_route53_zone" "this" {
  count = local.is_r53 && var.create_zone ? 1 : 0
  name  = var.zone_name
  dynamic "vpc" {
    for_each = var.private_zone && var.vpc_id != null ? [1] : []
    content {
      vpc_id = var.vpc_id
    }
  }
  tags = var.tags
}

data "aws_route53_zone" "existing" {
  count        = local.is_r53 && !var.create_zone ? 1 : 0
  name         = var.zone_name
  private_zone = var.private_zone
}

locals {
  zone_id = local.is_r53 ? (var.create_zone ? aws_route53_zone.this[0].zone_id : data.aws_route53_zone.existing[0].zone_id) : null
}

resource "aws_route53_record" "this" {
  for_each = local.is_r53 ? local.all_records : {}
  zone_id  = local.zone_id
  name     = "${each.key}.${var.zone_name}"
  type     = each.value.type
  ttl      = each.value.ttl
  records  = each.value.values
}

# CAA: só Let's Encrypt e a AC ICP-Brasil autorizada podem emitir para a zona
resource "aws_route53_record" "caa" {
  count   = local.is_r53 ? 1 : 0
  zone_id = local.zone_id
  name    = var.zone_name
  type    = "CAA"
  ttl     = 3600
  records = [
    "0 issue \"letsencrypt.org\"",
    "0 issuewild \"letsencrypt.org\"",
    "0 iodef \"mailto:sre@sus-nexus.local\"",
  ]
}

# ------------------------------------------------------------------------------------------------
# BIND (zona gerada; o DNS municipal faz include)
# ------------------------------------------------------------------------------------------------
resource "local_file" "bind_zone" {
  count    = local.is_bind ? 1 : 0
  filename = "${var.output_dir}/db.${var.zone_name}"
  content = templatefile("${path.module}/templates/zone.tftpl", {
    zone_name   = var.zone_name
    serial      = formatdate("YYYYMMDDhh", timestamp())
    ttl         = var.ttl
    records     = local.all_records
    active_site = var.active_site
  })
  file_permission = "0644"
  lifecycle {
    ignore_changes = [content] # serial muda a cada plan; aplicar só quando registros mudam (taint manual)
  }
}
