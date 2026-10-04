# Ambiente hml — on-prem (datacenter da SMS): RKE2 perfil CIS, MinIO, DNS interno (BIND).
# Espelho reduzido de produção: 3 servers + 4 agents (2 app, 2 data), 1 zona.
terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws   = { source = "hashicorp/aws", version = ">= 5.60" }
    minio = { source = "aminueza/minio", version = ">= 2.5" }
    local = { source = "hashicorp/local", version = ">= 2.5" }
    tls   = { source = "hashicorp/tls", version = ">= 4.0" }
  }
  # State remoto no MinIO (S3 compatível). Locking: Terraform >= 1.10 suporta use_lockfile = true;
  # com 1.9 use um único runner (CI) para apply.
  backend "s3" {
    bucket                      = "tfstate"
    key                         = "hml/terraform.tfstate"
    region                      = "sa-east-1"
    endpoints                   = { s3 = "https://minio.hml.sus-nexus.local" }
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    use_path_style              = true
    encrypt                     = true
  }
}

# Provider AWS não é usado on-prem; região fixa para satisfazer os módulos.
provider "aws" {
  region                      = "sa-east-1"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
  access_key                  = "unused"
  secret_key                  = "unused"
}

provider "minio" {
  minio_server   = var.minio_endpoint
  minio_user     = var.minio_access_key
  minio_password = var.minio_secret_key
  minio_ssl      = true
}

module "network" {
  source             = "../../modules/network"
  provider_kind      = "onprem"
  name               = "sus-nexus-hml"
  environment        = "hml"
  cidr               = "10.30.0.0/16"
  availability_zones = ["sala-a"]
  subnets = {
    dmz  = ["10.30.0.0/24"]
    app  = ["10.30.16.0/20"]
    data = ["10.30.64.0/22"]
    mgmt = ["10.30.250.0/24"]
  }
  enable_nat_gateway = false
}

module "cluster" {
  source             = "../../modules/cluster"
  provider_kind      = "rke2"
  name               = "sus-nexus-hml"
  environment        = "hml"
  kubernetes_version = "1.30"
  rke2 = {
    api_vip             = "10.30.250.10"
    rke2_version        = "v1.30.6+rke2r1"
    cluster_token       = var.rke2_cluster_token
    ssh_authorized_keys = var.ssh_authorized_keys
    registry_mirror     = "harbor.hml.sus-nexus.local"
    cluster_domain_sans = ["k8s.hml.sus-nexus.local"]
    output_dir          = "${path.root}/generated"
    nodes = [
      { name = "hml-cp-01", ip = "10.30.250.11", role = "server", tier = "control", zone = "sala-a" },
      { name = "hml-cp-02", ip = "10.30.250.12", role = "server", tier = "control", zone = "sala-a" },
      { name = "hml-cp-03", ip = "10.30.250.13", role = "server", tier = "control", zone = "sala-a" },
      { name = "hml-app-01", ip = "10.30.16.11", role = "agent", tier = "worker", zone = "sala-a" },
      { name = "hml-app-02", ip = "10.30.16.12", role = "agent", tier = "worker", zone = "sala-a" },
      { name = "hml-data-01", ip = "10.30.64.11", role = "agent", tier = "data", zone = "sala-a", taints = ["sus-nexus.gov.br/tier=data:NoSchedule"], disks = { fast_nvme = "/dev/nvme1n1", standard = "/dev/sdb" } },
      { name = "hml-data-02", ip = "10.30.64.12", role = "agent", tier = "data", zone = "sala-a", taints = ["sus-nexus.gov.br/tier=data:NoSchedule"], disks = { fast_nvme = "/dev/nvme1n1", standard = "/dev/sdb" } },
    ]
  }
}

module "storage" {
  source        = "../../modules/storage"
  provider_kind = "minio"
  name_prefix   = "sus-nexus-hml"
  environment   = "hml"
  buckets = {
    raw-zone      = { versioning = true, noncurrent_expire_days = 30 }
    documents     = { versioning = true }
    exports       = { versioning = false, expire_days = 14 }
    backups       = { versioning = true, noncurrent_expire_days = 14 }
    audit-archive = { versioning = true, object_lock = true, lock_mode = "COMPLIANCE", lock_retention_days = 365 }
    loki-chunks   = { versioning = false, expire_days = 14 }
    loki-ruler    = { versioning = false }
    loki-admin    = { versioning = false }
    tempo-traces  = { versioning = false, expire_days = 7 }
    tfstate       = { versioning = true }
  }
}

module "dns" {
  source        = "../../modules/dns"
  provider_kind = "bind"
  zone_name     = "hml.sus-nexus.local"
  active_site   = "primary"
  site_targets  = { primary = "10.30.0.10", dr = "10.30.0.10" } # VIP MetalLB do APISIX na DMZ
  extra_records = {
    ns1 = { type = "A", values = ["10.30.250.53"] }
    ns2 = { type = "A", values = ["10.30.250.54"] }
    k8s = { type = "A", values = ["10.30.250.10"] }
  }
  output_dir = "${path.root}/generated"
}

module "registry" {
  source      = "../../modules/registry"
  environment = "hml"
  hostname    = "harbor.hml.sus-nexus.local"
  storage     = { bucket = "harbor", endpoint = "minio.data.svc.cluster.local", secure = true }
  database    = { host = "harbor-db-rw.data.svc.cluster.local" }
  oidc        = { endpoint = "https://auth.hml.sus-nexus.local/realms/sus-nexus" }
  replicas    = 2
  output_dir  = "${path.root}/generated"
}
