# Terraform — infraestrutura do SUS Nexus

```text
terraform/
├── modules/
│   ├── network/       VPC + sub-redes DMZ/app/data/mgmt por zona (AWS) ou validação de CIDRs (on-prem); NACL de dados por porta; flow logs (KMS); SG default fechado
│   ├── onprem-rke2/   Lista de nós → cloud-init por nó, config.yaml RKE2 (perfil CIS, audit, PSA restricted, encryption at rest), inventário Ansible, kube-vip
│   ├── cluster/       EKS (IRSA, KMS, logs de auditoria, node groups app/data/observability, IMDSv2) ou RKE2 (wrapper do onprem-rke2)
│   ├── storage/       Buckets S3 ou MinIO: versionamento, Object Lock COMPLIANCE em audit-archive, lifecycle, TLS-only, access logs, EventBridge, replicação DR (RTC 15 min)
│   ├── dns/           Route53 ou zona BIND; registros de serviço seguem `active_site` (failover); CAA
│   └── registry/      Gera values do Harbor (S3, OIDC Keycloak, Trivy, cosign) + config de projetos/proxy-cache/replicação
└── envs/
    ├── dev/           EKS pequeno (sa-east-1), KMS, S3, Route53 privado
    ├── hml/           RKE2 on-prem (3 cp + 4 agents), MinIO, BIND, backend S3 no MinIO
    └── prod/          EKS 3 AZs, KMS, S3 com Object Lock + replicação DR, Route53 público (zona delegada)
```

## Uso

```bash
cd envs/prod
cp terraform.tfvars.example terraform.tfvars
export TF_VAR_minio_access_key=... TF_VAR_minio_secret_key=...     # quando aplicável
terraform init            # backend S3 (bucket sus-nexus-tfstate / DynamoDB lock) ou MinIO (hml)
terraform plan -out=plan.tfplan
terraform apply plan.tfplan   # prod: somente via pipeline com aprovação dupla
```

On-prem (hml): após `apply`, `generated/` contém `inventory.ini`, `<nó>/user-data.yaml` (cloud-init) e
`<nó>/rke2-config.yaml`. Fluxo: criar VMs com o cloud-init → `ansible-playbook -i generated/inventory.ini rke2.yml`
(playbook em `sus-nexus-infra/ansible`, instala RKE2 na ordem first-server → servers → agents, aplica
`kube-vip.yaml` e a chave de `EncryptionConfiguration`).

Failover DNS para DR: `terraform apply -var active_site=dr` em `envs/prod` (TTL 60 s). Ver `backup-dr/RUNBOOK-DR.md`.

## Hardening (baseline Checkov)

Decisões que valem para todos os ambientes AWS (`dev`, `prod`); on-prem (`hml`) não cria recursos AWS.

- **KMS** (`envs/*`): a chave `alias/sus-nexus-<env>` tem key policy explícita — root da conta (delegação a IAM),
  `logs.<região>.amazonaws.com` (log groups cifrados) e a service-linked role do Auto Scaling (EBS dos node groups).
  `dev` passou a ter chave própria; o módulo `cluster` exige `kms_key_arn` quando `provider_kind = eks`
  (secrets do etcd, EBS e log group do control plane) e concede ao role do cluster permissões KMS restritas à chave.
- **Logs** (`cluster`, `network`): log groups do EKS e dos flow logs cifrados com a chave e retidos ≥ 365 dias
  (`cluster_log_retention_days`, `flow_logs_retention_days`; 0 = sem expiração). O role dos flow logs só escreve
  no próprio log group (sem `Resource = "*"`).
- **Rede** (`network`): `aws_default_security_group` sem regras; NACL do segmento de dados aceita apenas as portas de
  `data_ingress_ports` (PostgreSQL, Valkey, Kafka, MinIO, OpenSearch, kubelet, NodePort) a partir de app/mgmt/data,
  mais o retorno TCP efêmero 1024–65535 **sem 3389** (duas regras: 1024–3388 e 3390–65535). O SG `dmz` tem egress
  limitado a 443/80 externo + VPC, toda regra com descrição, e é aplicado ao control plane via
  `module.cluster.additional_security_group_ids` (o Checkov não segue a referência entre módulos — skip
  `CKV2_AWS_5` documentado no recurso). O chart do APISIX o referencia na annotation `aws-load-balancer-security-groups`.
- **S3** (`storage`): versionamento sempre habilitado (buckets com `versioning = false` purgam versões não-correntes
  em 1 dia — regra `purge-noncurrent`), server access logging de todos os buckets no bucket
  `<name_prefix>-access-logs` (retenção `access_logs_retention_days`, padrão 400; skips `CKV_AWS_18/144`
  documentados: destino de log não loga em si mesmo nem replica para DR), notificações para o EventBridge e
  `abort_incomplete_multipart_upload` (7 dias) em toda configuração de ciclo de vida. Object Lock COMPLIANCE em
  `audit-archive` e replicação DR não mudam.

Variáveis novas: `cluster.cluster_log_retention_days`, `cluster.additional_security_group_ids`,
`network.kms_key_arn`, `network.data_ingress_ports`, `storage.access_logs_retention_days` (todas com padrão).

## Validação

`terraform fmt -check -recursive` e `terraform validate` (por env, `init -backend=false`) rodam no job
`platform` do CI. Checkov (IaC) roda no job `security`:

```bash
pip install checkov
checkov -d sus-nexus/platform/terraform --framework terraform --quiet --compact
```

Nota: `CKV2_AWS_19` (EIP do NAT) oscila entre execuções do Checkov por não-determinismo na expansão de `for_each`
(`aws_eip.nat` ↔ `aws_nat_gateway.this`); é falso positivo — os EIPs estão anexados aos NAT Gateways.
