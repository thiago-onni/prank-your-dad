# Módulo network — provider-agnostic na interface (CIDRs por segmento) com implementação AWS.
# On-prem: as VLANs/sub-redes já existem; o módulo apenas valida e exporta os CIDRs para os demais módulos.

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.60"
    }
  }
}

locals {
  is_aws = var.provider_kind == "aws"
  tags = merge(var.tags, {
    "sus-nexus.gov.br/environment" = var.environment
    "sus-nexus.gov.br/managed-by"  = "terraform"
  })

  segments = {
    dmz  = { cidrs = var.subnets.dmz, public = true, tier = "dmz" }
    app  = { cidrs = var.subnets.app, public = false, tier = "app" }
    data = { cidrs = var.subnets.data, public = false, tier = "data" }
    mgmt = { cidrs = var.subnets.mgmt, public = false, tier = "mgmt" }
  }

  # Lista plana segmento × zona
  subnet_list = flatten([
    for seg, cfg in local.segments : [
      for idx, cidr in cfg.cidrs : {
        key    = "${seg}-${idx}"
        seg    = seg
        az     = var.availability_zones[idx % length(var.availability_zones)]
        cidr   = cidr
        public = cfg.public
      }
    ]
  ])
  subnet_map = { for s in local.subnet_list : s.key => s }
}

# ------------------------------------------------------------------------------------------------
# AWS
# ------------------------------------------------------------------------------------------------
resource "aws_vpc" "this" {
  count                = local.is_aws ? 1 : 0
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(local.tags, { Name = var.name })
}

resource "aws_internet_gateway" "this" {
  count  = local.is_aws ? 1 : 0
  vpc_id = aws_vpc.this[0].id
  tags   = merge(local.tags, { Name = "${var.name}-igw" })
}

resource "aws_subnet" "this" {
  for_each                = local.is_aws ? local.subnet_map : {}
  vpc_id                  = aws_vpc.this[0].id
  cidr_block              = each.value.cidr
  availability_zone       = each.value.az
  map_public_ip_on_launch = false
  tags = merge(local.tags, {
    Name                              = "${var.name}-${each.key}"
    "sus-nexus.gov.br/segment"        = each.value.seg
    "kubernetes.io/role/elb"          = each.value.seg == "dmz" ? "1" : null
    "kubernetes.io/role/internal-elb" = each.value.seg == "app" ? "1" : null
  })
}

resource "aws_eip" "nat" {
  for_each = local.is_aws && var.enable_nat_gateway ? toset(var.availability_zones) : toset([])
  domain   = "vpc"
  tags     = merge(local.tags, { Name = "${var.name}-nat-${each.key}" })
}

# Um NAT por zona, na sub-rede DMZ daquela zona
resource "aws_nat_gateway" "this" {
  for_each      = local.is_aws && var.enable_nat_gateway ? toset(var.availability_zones) : toset([])
  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = [for k, s in aws_subnet.this : s.id if s.availability_zone == each.key && local.subnet_map[k].seg == "dmz"][0]
  tags          = merge(local.tags, { Name = "${var.name}-nat-${each.key}" })
  depends_on    = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  count  = local.is_aws ? 1 : 0
  vpc_id = aws_vpc.this[0].id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this[0].id
  }
  tags = merge(local.tags, { Name = "${var.name}-rt-dmz" })
}

resource "aws_route_table" "private" {
  for_each = local.is_aws ? toset(var.availability_zones) : toset([])
  vpc_id   = aws_vpc.this[0].id
  dynamic "route" {
    for_each = var.enable_nat_gateway ? [1] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = aws_nat_gateway.this[each.key].id
    }
  }
  tags = merge(local.tags, { Name = "${var.name}-rt-private-${each.key}" })
}

resource "aws_route_table_association" "this" {
  for_each       = local.is_aws ? local.subnet_map : {}
  subnet_id      = aws_subnet.this[each.key].id
  route_table_id = each.value.public ? aws_route_table.public[0].id : aws_route_table.private[each.value.az].id
}

# NACL do segmento de dados: só aceita tráfego de app e mgmt (nunca da DMZ nem de fora)
resource "aws_network_acl" "data" {
  count      = local.is_aws ? 1 : 0
  vpc_id     = aws_vpc.this[0].id
  subnet_ids = [for k, s in aws_subnet.this : s.id if local.subnet_map[k].seg == "data"]
  tags       = merge(local.tags, { Name = "${var.name}-nacl-data" })
}

resource "aws_network_acl_rule" "data_in_app" {
  count          = local.is_aws ? length(var.subnets.app) : 0
  network_acl_id = aws_network_acl.data[0].id
  rule_number    = 100 + count.index
  egress         = false
  protocol       = "-1"
  rule_action    = "allow"
  cidr_block     = var.subnets.app[count.index]
}

resource "aws_network_acl_rule" "data_in_mgmt" {
  count          = local.is_aws ? length(var.subnets.mgmt) : 0
  network_acl_id = aws_network_acl.data[0].id
  rule_number    = 200 + count.index
  egress         = false
  protocol       = "-1"
  rule_action    = "allow"
  cidr_block     = var.subnets.mgmt[count.index]
}

resource "aws_network_acl_rule" "data_in_data" {
  count          = local.is_aws ? length(var.subnets.data) : 0
  network_acl_id = aws_network_acl.data[0].id
  rule_number    = 300 + count.index
  egress         = false
  protocol       = "-1"
  rule_action    = "allow"
  cidr_block     = var.subnets.data[count.index]
}

resource "aws_network_acl_rule" "data_in_ephemeral" {
  count          = local.is_aws ? 1 : 0
  network_acl_id = aws_network_acl.data[0].id
  rule_number    = 900
  egress         = false
  protocol       = "tcp"
  rule_action    = "allow"
  cidr_block     = "0.0.0.0/0"
  from_port      = 1024
  to_port        = 65535
}

resource "aws_network_acl_rule" "data_out_all" {
  count          = local.is_aws ? 1 : 0
  network_acl_id = aws_network_acl.data[0].id
  rule_number    = 100
  egress         = true
  protocol       = "-1"
  rule_action    = "allow"
  cidr_block     = "0.0.0.0/0"
}

# Flow logs (auditoria de rede, LGPD)
resource "aws_cloudwatch_log_group" "flow" {
  count             = local.is_aws ? 1 : 0
  name              = "/sus-nexus/${var.environment}/vpc-flow-logs"
  retention_in_days = var.flow_logs_retention_days
  tags              = local.tags
}

data "aws_iam_policy_document" "flow_assume" {
  count = local.is_aws ? 1 : 0
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "flow" {
  count              = local.is_aws ? 1 : 0
  name               = "${var.name}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_assume[0].json
  tags               = local.tags
}

resource "aws_iam_role_policy" "flow" {
  count = local.is_aws ? 1 : 0
  name  = "cloudwatch"
  role  = aws_iam_role.flow[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogGroups", "logs:DescribeLogStreams"]
      Resource = "*"
    }]
  })
}

resource "aws_flow_log" "this" {
  count                = local.is_aws ? 1 : 0
  vpc_id               = aws_vpc.this[0].id
  traffic_type         = "ALL"
  log_destination_type = "cloud-watch-logs"
  log_destination      = aws_cloudwatch_log_group.flow[0].arn
  iam_role_arn         = aws_iam_role.flow[0].arn
  tags                 = local.tags
}

# Security group base para a DMZ (APISIX / LB): HTTPS de qualquer origem + unidades de saúde
resource "aws_security_group" "dmz" {
  count       = local.is_aws ? 1 : 0
  name        = "${var.name}-dmz"
  description = "Borda: APISIX/LB"
  vpc_id      = aws_vpc.this[0].id
  ingress {
    description = "HTTPS publico"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  dynamic "ingress" {
    for_each = var.health_units_cidrs
    content {
      description = "Unidades de saude (mTLS Kafka externo / agentes de borda)"
      from_port   = 9095
      to_port     = 9095
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
    }
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(local.tags, { Name = "${var.name}-dmz" })
}
