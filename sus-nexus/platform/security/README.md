# Segurança de plataforma

| Pasta | Conteúdo | Aplicado por |
|---|---|---|
| `kyverno/` | `verify-images` (cosign keyless + chave, SBOM attestation), `restrict-image-registries`, `disallow-privileged`, `require-run-as-nonroot` (+ drop ALL, seccomp), `require-resources`, `require-readonly-rootfs`, labels/probes, geração de default-deny por namespace | Argo `security-baseline-<env>` (wave 10) |
| `cert-manager/` | `selfsigned-bootstrap` → CA interna `internal-ca` (mTLS), `letsencrypt-staging`/`letsencrypt-prod` (DNS-01 Route53 + HTTP-01 APISIX) | idem |
| `external-secrets/` | `ClusterSecretStore openbao` (KV v2, auth Kubernetes), `openbao-database` (credenciais dinâmicas), exemplo de `SecretStore` por namespace | idem |

## Convenção de segredos no OpenBao

```text
secret/<env>/<componente>/<grupo>      ex.: secret/prod/core-municipal/db  → { username, password }
secret/<env>/data/<cluster-db>          ex.: secret/prod/data/core-db       → { username, password }
secret/<env>/platform/backup-s3         → { access_key, secret_key }
secret/shared/dns/route53               → { access_key_id, secret_access_key }
```

Namespaces que consomem segredos recebem o label `sus-nexus.gov.br/secrets=openbao` (condição do
ClusterSecretStore). O chart umbrella e os charts de componente geram `ExternalSecret` com esse layout.

## Hardening aplicado (resumo)

- Kyverno em modo **Enforce** para privileged/escalation/host namespaces/hostPath, runAsNonRoot, drop ALL,
  seccomp, recursos; **Audit→Enforce** gradual para readOnlyRootFilesystem (Enforce nos namespaces de app).
- Imagens: somente registries aprovados; namespaces de app exigem assinatura cosign + attestation SBOM.
- Default-deny de rede em todos os namespaces (umbrella + Kyverno generate), allowances explícitas por chart.
- mTLS Kafka (listener tls + ACL por serviço), TLS CNPG (`hostssl ... scram-sha-256`), TLS Keycloak via cert-manager.
- Segredos nunca em Git: ESO → OpenBao; `dev` do compose é a única exceção (valores em `.env.example`).
- CIS: RKE2 com perfil `cis` (terraform `modules/onprem-rke2`), EKS com `cluster_encryption` + logs de auditoria.
