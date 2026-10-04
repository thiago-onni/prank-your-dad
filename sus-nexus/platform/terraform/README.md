# Terraform — infraestrutura do SUS Nexus

```text
terraform/
├── modules/
│   ├── network/       VPC + sub-redes DMZ/app/data/mgmt por zona (AWS) ou validação de CIDRs (on-prem); NACL de dados; flow logs
│   ├── onprem-rke2/   Lista de nós → cloud-init por nó, config.yaml RKE2 (perfil CIS, audit, PSA restricted, encryption at rest), inventário Ansible, kube-vip
│   ├── cluster/       EKS (IRSA, KMS, logs de auditoria, node groups app/data/observability, IMDSv2) ou RKE2 (wrapper do onprem-rke2)
│   ├── storage/       Buckets S3 ou MinIO: versionamento, Object Lock COMPLIANCE em audit-archive, lifecycle, TLS-only, replicação DR (RTC 15 min)
│   ├── dns/           Route53 ou zona BIND; registros de serviço seguem `active_site` (failover); CAA
│   └── registry/      Gera values do Harbor (S3, OIDC Keycloak, Trivy, cosign) + config de projetos/proxy-cache/replicação
└── envs/
    ├── dev/           EKS pequeno (sa-east-1), S3, Route53 privado
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

## Validação

`terraform fmt -check -recursive` e `terraform validate` (por env, `init -backend=false`) rodam no job
`platform` do CI. Checkov (IaC) roda no job `security`.
