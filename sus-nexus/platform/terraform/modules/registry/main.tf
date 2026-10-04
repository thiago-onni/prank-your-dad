# Módulo registry — gera os values do chart Harbor (goharbor/harbor) consumidos pelo Argo CD
# (00-operators.yaml, entrada `harbor`) e o manifesto de projetos/proxy-cache/replicação aplicado via API.

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    local = {
      source  = "hashicorp/local"
      version = ">= 2.5"
    }
  }
}

locals {
  harbor_values = {
    expose = {
      type = "clusterIP" # exposto pelo APISIX (ApisixRoute em platform/helm... gateway)
      tls = {
        enabled    = true
        certSource = "secret"
        secret     = { secretName = "harbor-tls" }
      }
    }
    externalURL = "https://${var.hostname}"
    persistence = {
      enabled = true
      imageChartStorage = {
        type = "s3"
        s3 = {
          region         = var.storage.region
          bucket         = var.storage.bucket
          regionendpoint = var.storage.endpoint
          secure         = var.storage.secure
          existingSecret = var.storage.existing_secret
          v4auth         = true
          skipverify     = false
        }
      }
      persistentVolumeClaim = {
        registry   = { size = "5Gi" }
        jobservice = { jobLog = { size = "2Gi" } }
        trivy      = { size = "10Gi" }
      }
    }
    database = {
      type = var.database == null ? "internal" : "external"
      # null é omitido pelo chart (Helm remove chaves nulas); evita tipos inconsistentes no condicional
      external = var.database == null ? null : {
        host           = var.database.host
        port           = tostring(var.database.port)
        username       = "harbor"
        coreDatabase   = "registry"
        existingSecret = var.database.existing_secret
        sslmode        = var.database.sslmode
      }
    }
    redis                          = { type = "internal" }
    existingSecretAdminPassword    = "harbor-admin"
    existingSecretAdminPasswordKey = "password"
    core = {
      replicas           = var.replicas
      secretName         = "harbor-core-secret"
      existingXsrfSecret = "harbor-core-xsrf"
    }
    jobservice = { replicas = var.replicas }
    registry   = { replicas = var.replicas }
    portal     = { replicas = var.replicas }
    trivy = {
      enabled    = var.trivy_enabled
      replicas   = 1
      skipUpdate = false
    }
    metrics = {
      enabled        = true
      serviceMonitor = { enabled = true, additionalLabels = { release = "kube-prometheus-stack" } }
    }
    containerSecurityContext = {
      privileged               = false
      allowPrivilegeEscalation = false
      runAsNonRoot             = true
      capabilities             = { drop = ["ALL"] }
      seccompProfile           = { type = "RuntimeDefault" }
    }
  }

  # Configuração pós-instalação (aplicada por `scripts/harbor-configure.sh` via API): OIDC, projetos,
  # proxy-cache, replicação para DR, retenção.
  harbor_config = {
    auth_mode                    = "oidc_auth"
    oidc_name                    = "Keycloak"
    oidc_endpoint                = var.oidc.endpoint
    oidc_client_id               = var.oidc.client_id
    oidc_client_secret_ref       = var.oidc.existing_secret
    oidc_scope                   = "openid,profile,email,roles"
    oidc_groups_claim            = "roles"
    oidc_admin_group             = var.oidc.admin_group
    oidc_auto_onboard            = true
    project_creation_restriction = "adminonly"
    projects = {
      sus-nexus = {
        public = false
        metadata = {
          auto_scan                   = "true"
          prevent_vul                 = "true"
          severity                    = "critical"
          enable_content_trust_cosign = "true"
        }
        retention = {
          rules = [
            { template = "latestPushedK", params = { latestPushedK = 20 }, tag_selectors = [{ kind = "doublestar", decoration = "matches", pattern = "**" }] },
            { template = "always", tag_selectors = [{ kind = "doublestar", decoration = "matches", pattern = "v*" }] },
          ]
        }
      }
      policies = { public = false, metadata = { auto_scan = "false" } }
      charts   = { public = false, metadata = { auto_scan = "false" } }
    }
    proxy_cache = {
      for name, url in var.proxy_cache_projects : name => {
        registry_url = url
        public       = false
      }
    }
    replication = [
      for t in var.replication_targets : {
        name          = "to-${t.name}"
        dest_registry = { name = t.name, url = t.url, insecure = t.insecure, type = "harbor" }
        filters       = [{ type = "name", value = "sus-nexus/**" }, { type = "tag", value = "v*" }]
        trigger       = { type = "event_based" }
        override      = true
        enabled       = true
      }
    ]
    system = {
      scan_all_policy            = { type = "daily", parameter = { daily_time = 3600 } }
      robot_token_duration       = 30
      audit_log_forward_endpoint = "syslog://audit-forwarder.observability.svc.cluster.local:514"
    }
  }
}

resource "local_file" "harbor_values" {
  filename        = "${var.output_dir}/harbor-values.yaml"
  content         = "# GERADO pelo módulo terraform/modules/registry (${var.environment}) — copie para platform/observability/harbor-values.yaml ou referencie no Argo.\n${yamlencode(local.harbor_values)}"
  file_permission = "0644"
}

resource "local_file" "harbor_config" {
  filename        = "${var.output_dir}/harbor-config.json"
  content         = jsonencode(local.harbor_config)
  file_permission = "0644"
}
