# SUS Nexus — Plataforma (infra, GitOps, observabilidade, DR)

Camada de plataforma do monorepo: ambiente local (Compose), charts Helm, Argo CD (GitOps), Terraform,
segurança de cluster, observabilidade, backup/DR e CI. Derivada de `docs/sus-nexus/PLANO_IMPLEMENTACAO.md`
(seções 3, 4, 12, 13) e de `sus-nexus/CONVENTIONS.md`.

```text
platform/
├── compose/          docker-compose local com perfis (infra | core | ai | observability) + init scripts
├── helm/
│   ├── charts/       sus-nexus-core, sus-nexus-fhir, sus-nexus-web, sus-nexus-ai, sus-nexus-connector (templates idênticos)
│   ├── sus-nexus/    chart umbrella: componentes + CNPG, Strimzi (KRaft), Kafka Connect/Debezium, Apicurio,
│   │                 Keycloak Operator, OPA, MinIO Tenant, Redis, Temporal; values-dev|hml|prod.yaml
│   └── scripts/      gen-kafka-topics.py (topics.yaml → KafkaTopic), sync-chart-templates.sh, sync-realm.sh
├── argocd/           AppProject por ambiente, root app-of-apps, ApplicationSets (operadores, baseline, envs, DR)
├── terraform/        módulos network, onprem-rke2, cluster (EKS|RKE2), storage (S3|MinIO), dns, registry; envs dev|hml|prod
├── security/         Kyverno (cosign, PSS), cert-manager ClusterIssuers, External Secrets → OpenBao
├── observability/    kube-prometheus-stack/Loki/Tempo/OTel values, alertas SLO (burn rate), dashboards JSON
├── backup-dr/        CNPG backups + teste PITR mensal, Velero, MirrorMaker 2, RUNBOOK-DR.md
└── images/           Dockerfiles de plataforma (kafka-connect-debezium)
```

## Topologia por ambiente

| Ambiente | Infra | Dados | Apps | Segurança |
|---|---|---|---|---|
| **local** | Docker Compose (`compose/`) | Postgres 16 (pgvector) 1 nó, Kafka KRaft 1 broker (RF 1), MinIO 1 nó | build local, 1 réplica, `quarkus dev` opcional | Keycloak dev (sem MFA), OPA com `policies/` montado, OpenBao dev |
| **dev** | EKS pequeno sa-east-1 (`terraform/envs/dev`) ou cluster compartilhado | CNPG 1 instância/cluster, Kafka 1 broker (partições ×0.25), MinIO 1×4 | 1 réplica, sem HPA/PDB, Deployment | usuários de dev importados no Keycloak, Kyverno `verify-images` em Audit |
| **hml** | RKE2 on-prem CIS (3 cp + 4 agents) (`envs/hml`) | CNPG 3 instâncias (core) / 2 demais, Kafka 3 brokers RF 3, MinIO 4×2 | 2 réplicas, HPA, **Rollouts canário** (core/fhir), dados sintéticos/mascarados | MFA obrigatório (TOTP), Object Lock COMPLIANCE 1 ano |
| **prod** | EKS 3 AZs + KMS (`envs/prod`) ou RKE2 2 salas | CNPG 3 instâncias síncronas + pooler, Kafka 3 brokers + Cruise Control + listener externo mTLS, MinIO 4×4 + replicação DR | 3+ réplicas, HPA, PDB minAvailable 2, canário 5→25→50%, WAF | MFA TOTP/WebAuthn, admin Keycloak só da rede de gestão, Object Lock 5 anos, sync manual Argo (janela) |
| **dr** | site secundário (cluster `prod-dr`) | réplicas CNPG, MirrorMaker 2, buckets replicados | sob demanda (failover) | ver `backup-dr/RUNBOOK-DR.md` |

Namespaces: `core`, `fhir`, `connectors`, `ai`, `web`, `data`, `security`, `workflows`, `observability`,
`gateway` + operadores (`cnpg-system`, `strimzi-system`, `minio-operator`, `keycloak-operator`, `cert-manager`,
`external-secrets`, `kyverno`, `argo-rollouts`, `velero`, `openbao`, `harbor`). Todos os namespaces de
aplicação/dados têm **default-deny** de rede.

## Subir local

```bash
cd sus-nexus/platform/compose && cp .env.example .env
docker compose --profile core up -d --build                 # infra + core, fhir, web, connector-pec
docker compose --profile core --profile ai --profile observability up -d
./debezium/register-outbox-connector.sh                      # após o core criar platform.event_outbox
```

Detalhes (portas, credenciais, bancos, buckets): [`compose/README.md`](compose/README.md).
Caminhos de Dockerfile são variáveis (`CORE_DOCKERFILE=src/main/docker/Dockerfile.jvm` etc. no `.env`).

### Portas (CONVENTIONS.md)

core 8080 · fhir 8081 · conectores 8090+ · ai 8000 · web 3000 · Postgres 5432 · Kafka 9092 · Apicurio 8085 ·
Keycloak 8180 · OPA 8181 · Temporal 7233 / UI 8233 · MinIO 9000 / 9001 · Redis 6379 · Grafana 3001.
Extras: Kafka UI 8086, Connect 8083, OpenBao 8200, LiteLLM 4000, Langfuse 3003, Prometheus 9090, Metabase 3002.

### Credenciais de dev (somente local)

| O quê | Valor |
|---|---|
| Keycloak admin | `admin` / `admin` (realm `sus-nexus`) |
| Usuários do realm | `admin.municipal`, `gestor`, `prof.aps`, `acs`, `regulador`, `agendador`, `prof.hospitalar`, `auditor`, `dpo`, `operador.integracao`, `cadastro.mestre` — senha `sus-nexus-dev`, `municipality_id=ibge_3143302` |
| Client secrets | `<clientId>-dev-secret` |
| Postgres | `postgres`/`postgres`; app `sus_nexus`/`sus_nexus`; CDC `debezium`/`sus_nexus` |
| MinIO | `minioadmin`/`minioadmin`; app `sus-app`/`sus-app-secret` |
| OpenBao | token `root` |
| Grafana | `admin`/`admin` · Langfuse `dev@sus-nexus.local`/`sus-nexus-dev` · LiteLLM `sk-dev-litellm` |

## GitOps e promoção (dev → hml → prod)

1. Tudo muda por **PR em `sus-nexus/platform/`**; o CI (`.github/workflows/sus-nexus-ci.yml`, job `platform`)
   roda helm lint/template, kubeconform, yamllint, terraform fmt/validate e verifica arquivos gerados.
2. `main` → Argo sincroniza **dev** automaticamente. Tags `vX.Y.Z` (`sus-nexus-release.yml`) geram imagens
   multi-arch assinadas (cosign keyless + SBOM), charts (chart-releaser + OCI) e bundle OPA.
3. **hml**: PR em `helm/sus-nexus/values-hml.yaml` com `image.digest` dos componentes → sync automático →
   aceite/carga.
4. **prod**: PR em `values-prod.yaml` (CODEOWNERS: 2 aprovações, SRE + tech lead) → sync **manual** pelo SRE
   na janela (`AppProject.syncWindows`) → Rollout canário com análise automática (taxa de sucesso, p99).
5. Rollback = `git revert` + sync. Detalhes: [`argocd/README.md`](argocd/README.md).

## Como adicionar um conector

1. Módulo Maven em `sus-nexus/connectors/connector-<id>` (fora deste diretório) + entrada no realm
   (`compose/keycloak/realm-sus-nexus.json`: client `connector-<id>`; rode `helm/scripts/sync-realm.sh`).
2. Tópico de ingestão em `contracts/events/topics.yaml` (se novo) → `python3 helm/scripts/gen-kafka-topics.py`.
3. Compose: copie um bloco do perfil `connectors` (ex.: `connector-lis`) em `compose/docker-compose.yml`
   (build `../../connectors` com `CONNECTOR_MODULE`/`PORT`; portas 8093–8097, MLLP 2575–2577).
4. Helm: em `helm/sus-nexus/Chart.yaml` adicione uma dependência com `alias: connector-<id>` (chart
   `sus-nexus-connector`) e o bloco `connector-<id>:` em `values.yaml` (`connector.id`, `sourceSystem`,
   `ingestTopic`, `edgeAgent`); ligue nos `values-<env>.yaml`. O `KafkaUser` + ACLs são gerados
   automaticamente (`templates/kafka-users.yaml`); adicione o alias à lista em `kafka-users.yaml`.
5. Argo: inclua `connector-<id>: { enabled: true }` no elemento `connectors` de
   `argocd/apps/20-sus-nexus-envs.yaml`.
6. CI: adicione a linha na matriz `images` de `sus-nexus-ci.yml`/`sus-nexus-release.yml`
   (`context: sus-nexus/connectors`, `dockerfile: Dockerfile`, `build_args: "CONNECTOR_MODULE=<mod>\nPORT=<porta>"`).
7. Segredos no OpenBao: `secret/<env>/connector-<id>/{oidc,s3,kafka,source}`.

Conector como **agente de borda** (roda dentro da unidade): `connector.edgeAgent: true` — o chart não cria
workload, só `KafkaUser`/ACL; o agente usa o listener externo mTLS (porta 9095) e sai apenas para o barramento.

Conector com **listener MLLP** (HL7 v2 — LIS 2575, HIS 2576, RIS 2577): `connector.mllp.enabled: true`,
`connector.mllp.port` e **obrigatoriamente** `connector.mllp.allowedSourceCidrs` (faixas dos
hospitais/laboratórios; o render falha se vazio). O chart cria o Service TCP `<conector>-mllp`
(`serviceType` ClusterIP/NodePort/LoadBalancer; em LoadBalancer usa `loadBalancerSourceRanges` e
`externalTrafficPolicy: Local`) e uma regra na NetworkPolicy que só aceita a porta `mllp` vinda desses
`ipBlock`s. HIS/RIS vêm com `edgeAgent: true` (borda); em dev rodam no cluster (`edgeAgent: false`).

## Como adicionar um serviço

Copie `helm/charts/sus-nexus-ai` (ou core) → ajuste `Chart.yaml`, `values.yaml` (porta, probes, `config`,
`externalSecret.data`, `networkPolicy`, `apisix`), registre no umbrella (dependência + `values*.yaml`),
no Argo (`20-sus-nexus-envs.yaml`), no compose e nas matrizes de imagem do CI. Os templates são
compartilhados: edite só em `sus-nexus-core/templates` e rode `scripts/sync-chart-templates.sh`.

## Hardening aplicado

- **Pods**: non-root, `readOnlyRootFilesystem`, `drop ALL`, seccomp RuntimeDefault, sem escalonamento de
  privilégio, requests/limits obrigatórios, SA sem token automontado; Kyverno em Enforce
  (`security/kyverno`), PSA `restricted` no RKE2.
- **Supply chain**: imagens só de registries aprovados, assinadas (cosign keyless do workflow de release) com
  attestation SBOM; Trivy fs/imagem, gitleaks, Semgrep, Checkov no CI; preferência por `image.digest` em prod.
- **Rede**: default-deny por namespace + allowances explícitas por chart; segmentos DMZ/app/data/mgmt no
  Terraform (NACL de dados); APISIX com OIDC, rate-limit por cliente, headers de segurança, WAF (Coraza)
  em prod; mTLS Kafka + ACL por serviço; TLS Postgres (`hostssl` + SCRAM).
- **Segredos**: nunca em Git — ESO → OpenBao (KV v2, auth Kubernetes); dynamic DB creds para BI; PKI interna
  cert-manager + ACME; secrets do etcd criptografados (KMS/EncryptionConfiguration).
- **Dados/LGPD**: OTel Collector remove CPF/CNS/e-mail de atributos, corpo de log, nome de span e URL;
  `ServiceMonitor` descarta labels com PII; `audit-archive` WORM (COMPLIANCE); replicação e backups
  criptografados; Loki/Tempo com retenção curta.
- **Operação**: PDB, HPA, anti-affinity por zona, Rollouts canário com análise, migrações Flyway como
  PreSync hook (expand/contract), alertas por burn rate de SLO, teste PITR mensal e DR semestral.

## Validação local

```bash
cd sus-nexus/platform
helm lint helm/charts/* && helm dependency build helm/sus-nexus
for e in dev hml prod; do helm template x helm/sus-nexus -f helm/sus-nexus/values-$e.yaml --kube-version 1.30.0 | kubeconform -strict -ignore-missing-schemas -summary; done
yamllint -c .yamllint.yaml .
terraform fmt -check -recursive terraform && (cd terraform/envs/prod && terraform init -backend=false && terraform validate)
python3 helm/scripts/gen-kafka-topics.py --check && bash helm/scripts/sync-chart-templates.sh --check
```
