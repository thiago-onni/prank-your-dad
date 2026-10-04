"""Gera Chart.yaml + values.yaml dos charts fhir/web/ai/connector a partir do core (mantendo estrutura)."""
import copy, sys, yaml

ROOT = sys.argv[1]
base = yaml.safe_load(open(f"{ROOT}/sus-nexus-core/values.yaml"))

def deep_set(d, path, value):
    cur = d
    for k in path[:-1]:
        cur = cur.setdefault(k, {})
    cur[path[-1]] = value

CHARTS = {
    "sus-nexus-fhir": {
        "chart": dict(description="SUS Nexus — FHIR R4 Gateway (Quarkus)", component="fhir-gateway", keywords=["fhir", "r4", "quarkus"]),
        "header": "# Valores padrão — sus-nexus-fhir (Quarkus, porta 8081). Estrutura idêntica ao sus-nexus-core.",
        "set": {
            ("fullnameOverride",): "fhir-gateway",
            ("component",): "fhir-gateway",
            ("image", "repository"): "sus-nexus/fhir-gateway",
            ("containerPort",): 8081,
            ("service", "port"): 8081,
            ("resources",): {"requests": {"cpu": "300m", "memory": "512Mi"}, "limits": {"cpu": "1500m", "memory": "1Gi"}},
            ("config",): {
                "QUARKUS_PROFILE": "prod", "QUARKUS_HTTP_PORT": "8081",
                "QUARKUS_DATASOURCE_JDBC_URL": "jdbc:postgresql://fhir-db-rw.data.svc.cluster.local:5432/sus_nexus_fhir",
                "QUARKUS_DATASOURCE_JDBC_MAX_SIZE": "20", "QUARKUS_FLYWAY_MIGRATE_AT_START": "false",
                "QUARKUS_OIDC_AUTH_SERVER_URL": "{{ .Values.global.keycloakUrl }}/realms/{{ .Values.global.keycloakRealm }}",
                "QUARKUS_OIDC_CLIENT_ID": "fhir-gateway", "QUARKUS_OIDC_TOKEN_AUDIENCE": "fhir-gateway",
                "CORE_API_URL": "http://core-municipal.core.svc.cluster.local:8080/api/v1",
                "KAFKA_BOOTSTRAP_SERVERS": "sus-kafka-kafka-bootstrap.data.svc.cluster.local:9093",
                "KAFKA_SECURITY_PROTOCOL": "SSL",
                "APICURIO_REGISTRY_URL": "http://apicurio.data.svc.cluster.local:8080/apis/registry/v3",
                "OPA_URL": "http://opa.security.svc.cluster.local:8181",
                "S3_ENDPOINT": "https://minio.data.svc.cluster.local",
                "FHIR_BASE_URL": "https://fhir.{{ .Values.global.domain }}/fhir/r4",
                "FHIR_VALIDATION_PROFILES": "br-core,sus-nexus",
                "QUARKUS_LOG_CONSOLE_JSON": "true", "QUARKUS_LOG_LEVEL": "INFO",
                "QUARKUS_OTEL_SDK_DISABLED": "false",
                "JAVA_OPTS_APPEND": "-XX:MaxRAMPercentage=70 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom",
            },
            ("externalSecret", "data"): [
                {"secretKey": "QUARKUS_DATASOURCE_USERNAME", "remoteRef": {"key": "{{ .Values.global.environment }}/fhir-gateway/db", "property": "username"}},
                {"secretKey": "QUARKUS_DATASOURCE_PASSWORD", "remoteRef": {"key": "{{ .Values.global.environment }}/fhir-gateway/db", "property": "password"}},
                {"secretKey": "QUARKUS_OIDC_CREDENTIALS_SECRET", "remoteRef": {"key": "{{ .Values.global.environment }}/fhir-gateway/oidc", "property": "client_secret"}},
                {"secretKey": "S3_ACCESS_KEY", "remoteRef": {"key": "{{ .Values.global.environment }}/fhir-gateway/s3", "property": "access_key"}},
                {"secretKey": "S3_SECRET_KEY", "remoteRef": {"key": "{{ .Values.global.environment }}/fhir-gateway/s3", "property": "secret_key"}},
                {"secretKey": "KAFKA_SSL_KEYSTORE_PASSWORD", "remoteRef": {"key": "{{ .Values.global.environment }}/fhir-gateway/kafka", "property": "keystore_password"}},
            ],
            ("networkPolicy", "ingress"): [{"from": [{"namespace": "ai"}, {"namespace": "web"}, {"namespace": "core"}], "ports": [{"port": 8081}]}],
            ("networkPolicy", "egress"): [
                {"to": [{"namespace": "data"}], "ports": [{"port": 5432}, {"port": 9093}, {"port": 8080}, {"port": 9000}, {"port": 443}]},
                {"to": [{"namespace": "security"}], "ports": [{"port": 8181}, {"port": 8080}, {"port": 8443}]},
                {"to": [{"namespace": "core"}], "ports": [{"port": 8080}]},
            ],
            ("apisix", "hosts"): ["fhir.{{ .Values.global.domain }}"],
            ("apisix", "paths"): ["/fhir/r4/*"],
            ("apisix", "oidc", "clientId"): "fhir-gateway",
            ("apisix", "oidc", "secretRef"): "fhir-gateway-apisix-oidc",
            ("apisix", "rateLimit", "count"): 300,
            ("apisix", "waf", "enabled"): False,
            ("rollout", "canary", "analysis", "maxP99Seconds"): 1.5,
        },
    },
    "sus-nexus-web": {
        "chart": dict(description="SUS Nexus — Web Shell (Next.js 15, BFF)", component="web-shell", keywords=["web", "nextjs", "bff"]),
        "header": "# Valores padrão — sus-nexus-web (Next.js shell + BFF, porta 3000). Estrutura idêntica ao sus-nexus-core.",
        "set": {
            ("fullnameOverride",): "web-shell",
            ("component",): "web-shell",
            ("tier",): "frontend",
            ("image", "repository"): "sus-nexus/web-shell",
            ("containerPort",): 3000,
            ("service", "port"): 3000,
            ("probes",): {
                "startup": {"enabled": True, "path": "/api/health", "periodSeconds": 5, "failureThreshold": 30, "timeoutSeconds": 3},
                "liveness": {"path": "/api/health", "initialDelaySeconds": 0, "periodSeconds": 10, "failureThreshold": 3, "timeoutSeconds": 3},
                "readiness": {"path": "/api/health", "initialDelaySeconds": 0, "periodSeconds": 10, "failureThreshold": 3, "timeoutSeconds": 3},
            },
            ("resources",): {"requests": {"cpu": "200m", "memory": "256Mi"}, "limits": {"cpu": "1", "memory": "512Mi"}},
            ("podSecurityContext",): {"runAsNonRoot": True, "runAsUser": 1000, "runAsGroup": 1000, "fsGroup": 1000, "seccompProfile": {"type": "RuntimeDefault"}},
            ("writableDirs",): [{"name": "next-cache", "mountPath": "/app/.next/cache", "sizeLimit": "256Mi"}],
            ("config",): {
                "NODE_ENV": "production", "PORT": "3000", "HOSTNAME": "0.0.0.0",
                "AUTH_URL": "https://app.{{ .Values.global.domain }}",
                "NEXT_PUBLIC_APP_URL": "https://app.{{ .Values.global.domain }}",
                "AUTH_KEYCLOAK_ISSUER": "{{ .Values.global.keycloakUrl }}/realms/{{ .Values.global.keycloakRealm }}",
                "AUTH_KEYCLOAK_ID": "web-shell",
                "CORE_API_URL": "http://core-municipal.core.svc.cluster.local:8080/api/v1",
                "FHIR_API_URL": "http://fhir-gateway.fhir.svc.cluster.local:8081/fhir/r4",
                "AI_SERVICE_URL": "http://ai-service.ai.svc.cluster.local:8000",
                "NEXT_TELEMETRY_DISABLED": "1",
                "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel-collector.observability.svc.cluster.local:4318",
            },
            ("otel", "endpoint"): "http://otel-collector.observability.svc.cluster.local:4318",
            ("otel", "protocol"): "http/protobuf",
            ("externalSecret", "data"): [
                {"secretKey": "AUTH_KEYCLOAK_SECRET", "remoteRef": {"key": "{{ .Values.global.environment }}/web-shell/oidc", "property": "client_secret"}},
                {"secretKey": "AUTH_SECRET", "remoteRef": {"key": "{{ .Values.global.environment }}/web-shell/session", "property": "auth_secret"}},
            ],
            ("serviceMonitor", "path"): "/api/metrics",
            ("networkPolicy", "ingress"): [],
            ("networkPolicy", "egress"): [
                {"to": [{"namespace": "core"}], "ports": [{"port": 8080}]},
                {"to": [{"namespace": "fhir"}], "ports": [{"port": 8081}]},
                {"to": [{"namespace": "ai"}], "ports": [{"port": 8000}]},
                {"to": [{"namespace": "security"}], "ports": [{"port": 8080}, {"port": 8443}]},
            ],
            ("apisix", "hosts"): ["app.{{ .Values.global.domain }}"],
            ("apisix", "paths"): ["/*"],
            ("apisix", "oidc", "enabled"): False,   # o BFF faz o login (Authorization Code + PKCE)
            ("apisix", "rateLimit", "count"): 1200,
            ("apisix", "rateLimit", "key"): "remote_addr",
            ("apisix", "waf", "enabled"): True,
            ("migration", "enabled"): False,
            ("rollout", "canary", "analysis", "prometheusAddress"): "http://kube-prometheus-stack-prometheus.observability.svc.cluster.local:9090",
        },
    },
    "sus-nexus-ai": {
        "chart": dict(description="SUS Nexus — AI Service (Python/FastAPI, agentes LangGraph)", component="ai-service", keywords=["ai", "agents", "fastapi"]),
        "header": "# Valores padrão — sus-nexus-ai (FastAPI, porta 8000). Estrutura idêntica ao sus-nexus-core.",
        "set": {
            ("fullnameOverride",): "ai-service",
            ("component",): "ai-service",
            ("image", "repository"): "sus-nexus/ai-service",
            ("containerPort",): 8000,
            ("service", "port"): 8000,
            ("probes",): {
                "startup": {"enabled": True, "path": "/health", "periodSeconds": 5, "failureThreshold": 30, "timeoutSeconds": 3},
                "liveness": {"path": "/health", "initialDelaySeconds": 0, "periodSeconds": 10, "failureThreshold": 3, "timeoutSeconds": 5},
                "readiness": {"path": "/health/ready", "initialDelaySeconds": 0, "periodSeconds": 10, "failureThreshold": 3, "timeoutSeconds": 5},
            },
            ("resources",): {"requests": {"cpu": "500m", "memory": "1Gi"}, "limits": {"cpu": "2", "memory": "2Gi"}},
            ("podSecurityContext",): {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001, "fsGroup": 10001, "seccompProfile": {"type": "RuntimeDefault"}},
            ("hpa", "maxReplicas"): 4,
            ("config",): {
                "APP_ENV": "{{ .Values.global.environment }}", "PORT": "8000", "LOG_FORMAT": "json", "LOG_LEVEL": "INFO",
                "OIDC_ISSUER": "{{ .Values.global.keycloakUrl }}/realms/{{ .Values.global.keycloakRealm }}",
                "OIDC_CLIENT_ID": "ai-service", "OIDC_AUDIENCE": "core-municipal",
                "CORE_API_URL": "http://core-municipal.core.svc.cluster.local:8080/api/v1",
                "FHIR_API_URL": "http://fhir-gateway.fhir.svc.cluster.local:8081/fhir/r4",
                "OPA_URL": "http://opa.security.svc.cluster.local:8181",
                "KAFKA_BOOTSTRAP_SERVERS": "sus-kafka-kafka-bootstrap.data.svc.cluster.local:9093",
                "KAFKA_SECURITY_PROTOCOL": "SSL",
                "REDIS_URL": "redis://redis.data.svc.cluster.local:6379/1",
                "LITELLM_BASE_URL": "http://litellm.ai.svc.cluster.local:4000",
                "LANGFUSE_HOST": "http://langfuse.ai.svc.cluster.local:3000",
                "PII_EGRESS_POLICY": "deny",
                "OTEL_SDK_DISABLED": "false",
                "OTEL_TRACES_SAMPLER": "parentbased_traceidratio", "OTEL_TRACES_SAMPLER_ARG": "0.2",
            },
            ("externalSecret", "data"): [
                {"secretKey": "DATABASE_URL", "remoteRef": {"key": "{{ .Values.global.environment }}/ai-service/db", "property": "url"}},
                {"secretKey": "OIDC_CLIENT_SECRET", "remoteRef": {"key": "{{ .Values.global.environment }}/ai-service/oidc", "property": "client_secret"}},
                {"secretKey": "LITELLM_API_KEY", "remoteRef": {"key": "{{ .Values.global.environment }}/ai-service/litellm", "property": "api_key"}},
                {"secretKey": "LANGFUSE_PUBLIC_KEY", "remoteRef": {"key": "{{ .Values.global.environment }}/ai-service/langfuse", "property": "public_key"}},
                {"secretKey": "LANGFUSE_SECRET_KEY", "remoteRef": {"key": "{{ .Values.global.environment }}/ai-service/langfuse", "property": "secret_key"}},
            ],
            ("serviceMonitor", "path"): "/metrics",
            ("networkPolicy", "ingress"): [{"from": [{"namespace": "web"}, {"namespace": "core"}], "ports": [{"port": 8000}]}],
            ("networkPolicy", "egress"): [
                {"to": [{"namespace": "data"}], "ports": [{"port": 5432}, {"port": 9093}, {"port": 6379}]},
                {"to": [{"namespace": "security"}], "ports": [{"port": 8181}, {"port": 8080}, {"port": 8443}]},
                {"to": [{"namespace": "core"}], "ports": [{"port": 8080}]},
                {"to": [{"namespace": "fhir"}], "ports": [{"port": 8081}]},
                {"to": [{"podLabels": {"app.kubernetes.io/name": "litellm"}}, {"podLabels": {"app.kubernetes.io/name": "langfuse"}}], "ports": [{"port": 4000}, {"port": 3000}]},
            ],
            ("apisix", "hosts"): ["ai.{{ .Values.global.domain }}"],
            ("apisix", "paths"): ["/api/v1/*"],
            ("apisix", "oidc", "clientId"): "ai-service",
            ("apisix", "oidc", "secretRef"): "ai-service-apisix-oidc",
            ("apisix", "rateLimit", "count"): 120,
            ("migration", "enabled"): True,
            ("migration", "command"): ["alembic"],
            ("migration", "args"): ["upgrade", "head"],
            ("migration", "env"): [],
        },
    },
    "sus-nexus-connector": {
        "chart": dict(description="SUS Nexus — Connector runtime (Quarkus + Camel), genérico; instanciado por conector via alias", component="connector", keywords=["connector", "camel", "quarkus"]),
        "header": "# Valores padrão — sus-nexus-connector (chart genérico; um release por conector, porta 8090).\n# Instanciado no umbrella via alias (connector-pec, connector-agenda, ...). Defina `connector.id`.",
        "set": {
            ("fullnameOverride",): "",
            ("nameOverride",): "",
            ("component",): "connector",
            ("image", "repository"): "sus-nexus/connector-pec",
            ("containerPort",): 8090,
            ("service", "port"): 8090,
            ("replicaCount",): 1,
            ("hpa", "enabled"): False,
            ("hpa", "minReplicas"): 1,
            ("hpa", "maxReplicas"): 2,
            ("pdb", "enabled"): False,
            ("resources",): {"requests": {"cpu": "250m", "memory": "512Mi"}, "limits": {"cpu": "1", "memory": "1Gi"}},
            ("config",): {
                "QUARKUS_PROFILE": "prod", "QUARKUS_HTTP_PORT": "8090",
                "CONNECTOR_ID": "{{ .Values.connector.id }}",
                "CONNECTOR_TENANT_ID": "{{ .Values.connector.tenantId }}",
                "CONNECTOR_SOURCE_SYSTEM": "{{ .Values.connector.sourceSystem }}",
                "CONNECTOR_INGEST_TOPIC": "{{ .Values.connector.ingestTopic }}",
                "CONNECTOR_POLL_INTERVAL": "{{ .Values.connector.pollInterval }}",
                "CONNECTOR_RECONCILIATION_CRON": "{{ .Values.connector.reconciliationCron }}",
                "QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL": "{{ .Values.global.keycloakUrl }}/realms/{{ .Values.global.keycloakRealm }}",
                "QUARKUS_OIDC_CLIENT_CLIENT_ID": "connector-{{ .Values.connector.id }}",
                "CORE_API_URL": "http://core-municipal.core.svc.cluster.local:8080/api/v1",
                "KAFKA_BOOTSTRAP_SERVERS": "sus-kafka-kafka-bootstrap.data.svc.cluster.local:9093",
                "KAFKA_SECURITY_PROTOCOL": "SSL",
                "APICURIO_REGISTRY_URL": "http://apicurio.data.svc.cluster.local:8080/apis/registry/v3",
                "S3_ENDPOINT": "https://minio.data.svc.cluster.local",
                "S3_BUCKET_RAW_ZONE": "raw-zone",
                "QUARKUS_LOG_CONSOLE_JSON": "true", "QUARKUS_LOG_LEVEL": "INFO",
                "QUARKUS_OTEL_SDK_DISABLED": "false",
                "JAVA_OPTS_APPEND": "-XX:MaxRAMPercentage=70 -Djava.security.egd=file:/dev/./urandom",
            },
            ("externalSecret", "data"): [
                {"secretKey": "QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET", "remoteRef": {"key": "{{ .Values.global.environment }}/connector-{{ .Values.connector.id }}/oidc", "property": "client_secret"}},
                {"secretKey": "S3_ACCESS_KEY", "remoteRef": {"key": "{{ .Values.global.environment }}/connector-{{ .Values.connector.id }}/s3", "property": "access_key"}},
                {"secretKey": "S3_SECRET_KEY", "remoteRef": {"key": "{{ .Values.global.environment }}/connector-{{ .Values.connector.id }}/s3", "property": "secret_key"}},
                {"secretKey": "KAFKA_SSL_KEYSTORE_PASSWORD", "remoteRef": {"key": "{{ .Values.global.environment }}/connector-{{ .Values.connector.id }}/kafka", "property": "keystore_password"}},
            ],
            ("externalSecret", "dataFrom"): [{"key": "{{ .Values.global.environment }}/connector-{{ .Values.connector.id }}/source"}],
            ("networkPolicy", "ingress"): [],
            ("networkPolicy", "egress"): [
                {"to": [{"namespace": "data"}], "ports": [{"port": 9093}, {"port": 8080}, {"port": 9000}, {"port": 443}]},
                {"to": [{"namespace": "security"}], "ports": [{"port": 8080}, {"port": 8443}]},
                {"to": [{"namespace": "core"}], "ports": [{"port": 8080}]},
                # Sistema de origem (ex.: PEC na rede municipal) — ajuste por conector
                {"to": [{"ipBlock": {"cidr": "10.0.0.0/8"}}], "ports": [{"port": 5432}, {"port": 443}, {"port": 1433}, {"port": 1521}]},
            ],
            ("apisix", "enabled"): False,
            ("migration", "enabled"): False,
            ("rollout", "enabled"): False,
        },
        "extra": {
            "connector": {
                "id": "pec",
                "tenantId": "ibge_3143302",
                "sourceSystem": "PEC",
                "ingestTopic": "sus.ingest.pec.v1",
                "pollInterval": "PT5M",
                "reconciliationCron": "0 0 3 * * ?",
                "edgeAgent": False,   # true → conector roda como agente de borda (fora do cluster); chart só gera KafkaUser/ACL
            }
        },
    },
}

for name, spec in CHARTS.items():
    v = copy.deepcopy(base)
    for path, value in spec["set"].items():
        deep_set(v, list(path), value)
    for k, val in spec.get("extra", {}).items():
        v[k] = val
    with open(f"{ROOT}/{name}/values.yaml", "w") as f:
        f.write(spec["header"] + "\n# Gerado a partir de sus-nexus-core/values.yaml (scripts/gen-component-values.py); edite com cuidado.\n\n")
        yaml.safe_dump(v, f, sort_keys=False, allow_unicode=True, width=120)
    c = spec["chart"]
    with open(f"{ROOT}/{name}/Chart.yaml", "w") as f:
        f.write(f"""apiVersion: v2
name: {name}
description: {c['description']}
type: application
version: 0.1.0
appVersion: "0.1.0"
kubeVersion: ">=1.28.0-0"
home: https://github.com/sus-nexus/sus-nexus
keywords: [sus-nexus, {', '.join(c['keywords'])}]
maintainers:
  - name: Plataforma SUS Nexus
annotations:
  sus-nexus.gov.br/component: {c['component']}
""")
print("ok")
