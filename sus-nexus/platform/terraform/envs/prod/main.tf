# Ambiente prod — EKS em sa-east-1, 2 zonas, buckets com Object Lock e replicação para DR,
# Route53 com failover por `active_site`. Dimensionamento PLANO 3.5 (Fase 1).
terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws   = { source = "hashicorp/aws", version = ">= 5.60" }
    minio = { source = "aminueza/minio", version = ">= 2.5" }
    local = { source = "hashicorp/local", version = ">= 2.5" }
    tls   = { source = "hashicorp/tls", version = ">= 4.0" }
  }
  backend "s3" {
    bucket         = "sus-nexus-tfstate"
    key            = "prod/terraform.tfstate"
    region         = "sa-east-1"
    dynamodb_table = "sus-nexus-tfstate-lock"
    encrypt        = true
    kms_key_id     = "alias/sus-nexus-tfstate"
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = { Project = "sus-nexus", Environment = "prod", ManagedBy = "terraform", DataClassification = "restricted" }
  }
}

provider "minio" {
  minio_server   = var.minio_endpoint
  minio_user     = var.minio_access_key
  minio_password = var.minio_secret_key
  minio_ssl      = true
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.name
  asg_slr    = "arn:aws:iam::${local.account_id}:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling"
}

# Key policy explícita (CKV2_AWS_64). Em key policy, Resource "*" significa "esta chave". Principais:
# root da conta (delegação a políticas IAM, p.ex. role do EKS e de replicação S3), CloudWatch Logs
# (log groups do EKS e dos flow logs cifrados) e a service-linked role do Auto Scaling (EBS cifrado dos node groups).
resource "aws_kms_key" "platform" {
  description             = "SUS Nexus prod — EKS secrets, EBS, S3, CloudWatch Logs"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "EnableRootAndIamPolicies"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${local.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "AllowCloudWatchLogs"
        Effect    = "Allow"
        Principal = { Service = "logs.${local.region}.amazonaws.com" }
        Action    = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
        Resource  = "*"
        Condition = {
          ArnLike = { "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${local.region}:${local.account_id}:log-group:*" }
        }
      },
      {
        Sid       = "AllowAutoScalingServiceLinkedRoleUse"
        Effect    = "Allow"
        Principal = { AWS = local.asg_slr }
        Action    = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"]
        Resource  = "*"
      },
      {
        Sid       = "AllowAutoScalingServiceLinkedRoleGrants"
        Effect    = "Allow"
        Principal = { AWS = local.asg_slr }
        Action    = "kms:CreateGrant"
        Resource  = "*"
        Condition = { Bool = { "kms:GrantIsForAWSResource" = "true" } }
      }
    ]
  })
}

resource "aws_kms_alias" "platform" {
  name          = "alias/sus-nexus-prod"
  target_key_id = aws_kms_key.platform.key_id
}

module "network" {
  source             = "../../modules/network"
  provider_kind      = "aws"
  name               = "sus-nexus-prod"
  environment        = "prod"
  cidr               = "10.10.0.0/16"
  availability_zones = ["sa-east-1a", "sa-east-1b", "sa-east-1c"]
  subnets = {
    dmz  = ["10.10.0.0/24", "10.10.1.0/24", "10.10.2.0/24"]
    app  = ["10.10.16.0/20", "10.10.32.0/20", "10.10.48.0/20"]
    data = ["10.10.64.0/22", "10.10.68.0/22", "10.10.72.0/22"]
    mgmt = ["10.10.250.0/24", "10.10.251.0/24", "10.10.252.0/24"]
  }
  enable_nat_gateway       = true
  flow_logs_retention_days = 365
  kms_key_arn              = aws_kms_key.platform.arn
  health_units_cidrs       = var.health_units_cidrs
}

module "cluster" {
  source                        = "../../modules/cluster"
  provider_kind                 = "eks"
  name                          = "sus-nexus-prod"
  environment                   = "prod"
  kubernetes_version            = "1.30"
  vpc_id                        = module.network.vpc_id
  subnet_ids                    = module.network.subnet_ids["app"]
  control_plane_subnet_ids      = module.network.subnet_ids["mgmt"]
  api_allowed_cidrs             = [] # API server somente privado (acesso via VPN/bastion na mgmt)
  kms_key_arn                   = aws_kms_key.platform.arn
  additional_security_group_ids = [module.network.dmz_security_group_id]
  node_groups = {
    app = {
      instance_types = ["m6i.2xlarge"] # 8 vCPU / 32 GB
      min_size       = 6
      max_size       = 14
      desired_size   = 6
      labels         = { "sus-nexus.gov.br/tier" = "worker" }
    }
    data = {
      instance_types = ["r6i.2xlarge"]
      min_size       = 3
      max_size       = 6
      desired_size   = 3
      disk_size      = 500
      labels         = { "sus-nexus.gov.br/tier" = "data" }
      taints         = [{ key = "sus-nexus.gov.br/tier", value = "data", effect = "NO_SCHEDULE" }]
    }
    observability = {
      instance_types = ["m6i.xlarge"]
      min_size       = 3
      max_size       = 5
      desired_size   = 3
      labels         = { "sus-nexus.gov.br/tier" = "observability" }
    }
  }
}

module "storage" {
  source        = "../../modules/storage"
  provider_kind = "s3"
  name_prefix   = "sus-nexus-prod-${var.ibge_code}"
  environment   = "prod"
  kms_key_arn   = aws_kms_key.platform.arn
  dr_replication = {
    enabled            = true
    target_prefix      = "sus-nexus-dr-${var.ibge_code}"
    target_region      = var.dr_region
    role_arn           = var.s3_replication_role_arn
    target_kms_key_arn = var.dr_kms_key_arn
  }
}

module "dns" {
  source        = "../../modules/dns"
  provider_kind = "route53"
  zone_name     = "sus-nexus.saude.montesclaros.mg.gov.br"
  create_zone   = false # zona delegada pela prefeitura; já existe
  active_site   = var.active_site
  site_targets = {
    primary = var.apisix_lb_hostname
    dr      = var.apisix_dr_lb_hostname
  }
  extra_records = {
    "_dmarc" = { type = "TXT", values = ["v=DMARC1; p=reject; rua=mailto:dmarc@sus-nexus.local"] }
  }
}

module "registry" {
  source      = "../../modules/registry"
  environment = "prod"
  hostname    = "harbor.sus-nexus.saude.montesclaros.mg.gov.br"
  storage     = { bucket = "sus-nexus-prod-${var.ibge_code}-harbor", endpoint = "s3.sa-east-1.amazonaws.com" }
  database    = { host = "harbor-db-rw.data.svc.cluster.local" }
  oidc        = { endpoint = "https://auth.sus-nexus.saude.montesclaros.mg.gov.br/realms/sus-nexus" }
  replication_targets = [
    { name = "harbor-dr", url = "https://harbor-dr.sus-nexus.saude.montesclaros.mg.gov.br" }
  ]
  replicas   = 3
  output_dir = "${path.root}/generated"
}
