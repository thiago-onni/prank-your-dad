# Ambiente dev — EKS pequeno em sa-east-1 (cluster compartilhado, namespaces por equipe).
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
    key            = "dev/terraform.tfstate"
    region         = "sa-east-1"
    dynamodb_table = "sus-nexus-tfstate-lock"
    encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = { Project = "sus-nexus", Environment = "dev", ManagedBy = "terraform" }
  }
}

# Provider MinIO não é usado em dev (storage via S3); configuração mínima para o módulo storage.
provider "minio" {
  minio_server   = var.minio_endpoint
  minio_user     = var.minio_access_key
  minio_password = var.minio_secret_key
  minio_ssl      = true
}

module "network" {
  source             = "../../modules/network"
  provider_kind      = "aws"
  name               = "sus-nexus-dev"
  environment        = "dev"
  cidr               = "10.20.0.0/16"
  availability_zones = ["sa-east-1a", "sa-east-1b"]
  subnets = {
    dmz  = ["10.20.0.0/24", "10.20.1.0/24"]
    app  = ["10.20.16.0/20", "10.20.32.0/20"]
    data = ["10.20.64.0/22", "10.20.68.0/22"]
    mgmt = ["10.20.250.0/24", "10.20.251.0/24"]
  }
  enable_nat_gateway = true
  health_units_cidrs = var.health_units_cidrs
}

module "cluster" {
  source                   = "../../modules/cluster"
  provider_kind            = "eks"
  name                     = "sus-nexus-dev"
  environment              = "dev"
  kubernetes_version       = "1.30"
  vpc_id                   = module.network.vpc_id
  subnet_ids               = module.network.subnet_ids["app"]
  control_plane_subnet_ids = module.network.subnet_ids["mgmt"]
  api_allowed_cidrs        = var.api_allowed_cidrs
  node_groups = {
    app = {
      instance_types = ["m6i.xlarge"]
      min_size       = 2
      max_size       = 5
      desired_size   = 3
      labels         = { "sus-nexus.gov.br/tier" = "worker" }
    }
    data = {
      instance_types = ["r6i.xlarge"]
      min_size       = 1
      max_size       = 3
      desired_size   = 2
      disk_size      = 200
      labels         = { "sus-nexus.gov.br/tier" = "data" }
    }
  }
}

module "storage" {
  source        = "../../modules/storage"
  provider_kind = "s3"
  name_prefix   = "sus-nexus-dev-${var.ibge_code}"
  environment   = "dev"
  buckets = {
    raw-zone      = { versioning = true, noncurrent_expire_days = 15 }
    documents     = { versioning = true }
    exports       = { versioning = false, expire_days = 7 }
    backups       = { versioning = true, noncurrent_expire_days = 7 }
    audit-archive = { versioning = true, object_lock = true, lock_mode = "GOVERNANCE", lock_retention_days = 30 }
    loki-chunks   = { versioning = false, expire_days = 7 }
    loki-ruler    = { versioning = false }
    loki-admin    = { versioning = false }
    tempo-traces  = { versioning = false, expire_days = 3 }
  }
}

module "dns" {
  source        = "../../modules/dns"
  provider_kind = "route53"
  zone_name     = "dev.sus-nexus.local"
  create_zone   = true
  private_zone  = true
  vpc_id        = module.network.vpc_id
  active_site   = "primary"
  site_targets  = { primary = var.apisix_lb_hostname, dr = var.apisix_lb_hostname }
}

module "registry" {
  source      = "../../modules/registry"
  environment = "dev"
  hostname    = "harbor.dev.sus-nexus.local"
  storage     = { bucket = "sus-nexus-dev-${var.ibge_code}-harbor", endpoint = "s3.sa-east-1.amazonaws.com" }
  oidc        = { endpoint = "https://auth.dev.sus-nexus.local/realms/sus-nexus" }
  replicas    = 1
  output_dir  = "${path.root}/generated"
}
