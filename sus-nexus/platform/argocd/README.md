# Argo CD — GitOps do SUS Nexus

## Estrutura

```text
argocd/
├── project.yaml            AppProject por ambiente (sourceRepos/destinations restritos, syncWindows em prod)
├── root-app.yaml           app-of-apps (aplicar uma vez por cluster, substituindo __ENV__)
└── apps/
    ├── 00-operators.yaml         ApplicationSet: operadores (cert-manager, ESO, Kyverno, OpenBao, CNPG, Strimzi,
    │                             MinIO, Keycloak Operator, APISIX, Argo Rollouts, kube-prometheus-stack, Loki,
    │                             Tempo, OTel, Velero, Harbor) — waves 0..10
    ├── 10-security-baseline.yaml ApplicationSet: platform/security + platform/observability (waves 10/15)
    ├── 20-sus-nexus-envs.yaml    ApplicationSet env × componente → chart umbrella (waves 20..60)
    └── 30-backup-dr.yaml         ApplicationSet: platform/backup-dr (wave 70)
```

Os clusters são registrados no Argo CD com os labels `sus-nexus.gov.br/managed=true` e
`sus-nexus.gov.br/env=<dev|hml|prod>` (geradores `clusters`). O cluster de dev é o próprio `in-cluster`.

## Ordem de sincronização (sync waves)

| Wave | O quê |
|---|---|
| 0 | cert-manager, External Secrets, Kyverno |
| 1 | OpenBao |
| 5 | CloudNativePG, Strimzi, MinIO Operator, Keycloak Operator |
| 10 | APISIX, Argo Rollouts, observabilidade (kube-prometheus-stack, Loki, Tempo, OTel), Velero, Harbor, **baseline de segurança** (Kyverno policies, ClusterIssuers, ClusterSecretStore) |
| 15 | Regras de alerta e dashboards |
| 20 | **data**: CNPG clusters, Kafka (KRaft), KafkaTopics/Users, Kafka Connect, MinIO Tenant, Redis, Apicurio |
| 30 | **security**: Keycloak + realm, OPA, NetworkPolicies default-deny |
| 35 | **workflows**: Temporal |
| 40 | **core** (Flyway como PreSync hook) |
| 50 | **fhir**, **ai** |
| 60 | **web**, **connectors** |
| 70 | backup/DR |

## Política de promoção (dev → hml → prod)

1. **Tudo é PR em `sus-nexus/platform/`.** Nenhuma alteração manual no cluster (Argo `selfHeal` reverte).
2. **dev**: merge em `main` sincroniza automaticamente (`automated.prune/selfHeal`). Imagens: tag `main-<sha>`.
3. **hml**: PR altera `helm/sus-nexus/values-hml.yaml` (`image.tag`/`image.digest` dos componentes) apontando
   para a imagem **assinada** (cosign) gerada pelo CI a partir de uma tag `vX.Y.Z` (`sus-nexus-release.yml`).
   Sync automático após merge. Testes de aceite/carga rodam em hml.
4. **prod**: PR altera `values-prod.yaml` com o **digest** validado em hml. Regras do repositório:
   `CODEOWNERS` em `sus-nexus/platform/` exige **2 aprovações** (1 SRE + 1 tech lead) e o check `platform` do
   CI verde. Após o merge, o Application `sus-nexus-prod-*` fica *OutOfSync* e o SRE executa o sync
   **manualmente** dentro da janela (`syncWindows` do projeto: ter–qui 20h–23h BRT). Core e FHIR usam
   **Argo Rollouts canário** (5% → 25% → 50% → 100%, com análise de taxa de sucesso e p99 no Prometheus);
   `kubectl argo rollouts promote` / `abort` conforme análise.
5. **Rollback**: `git revert` do PR (prod) + sync; para dados, o padrão expand/contract garante que a versão
   anterior do core funciona com o schema novo.
6. Kyverno (`verify-images`) bloqueia qualquer imagem não assinada pela chave do CI em hml/prod.

## Comandos úteis

```bash
argocd app list -l sus-nexus.gov.br/env=prod
argocd app sync sus-nexus-prod-core --prune
argocd app diff sus-nexus-prod-core
kubectl argo rollouts get rollout core-municipal -n core --watch
kubectl argo rollouts promote core-municipal -n core
```

## Keycloak Operator

O Keycloak Operator não publica chart Helm oficial; vendorize os manifests da release em
`argocd/vendor/keycloak-operator/` (`kubectl kustomize` dos `kubernetes.yml` + CRDs da versão 26.2) ou troque
a entrada em `00-operators.yaml` por um chart da organização.
