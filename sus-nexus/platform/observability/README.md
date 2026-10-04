# Observabilidade

| Arquivo | Uso |
|---|---|
| `kube-prometheus-stack-values.yaml` | Prometheus (2 réplicas, remote-write receiver), Alertmanager (rotas por `team`, PagerDuty para critical), Grafana (OIDC Keycloak, sidecar de dashboards) |
| `loki-values.yaml`, `tempo-values.yaml` | Loki SimpleScalable e Tempo distributed sobre MinIO |
| `otel-collector-values.yaml` / `otel-collector-config.yaml` | Collector: receivers OTLP, `k8sattributes`, **remoção de PII** (`attributes/strip` + `transform/pii` com regex de CPF/CNS/e-mail em atributos, corpo de log, nome de span e URL), tail sampling, exporters Prometheus/Loki/Tempo |
| `alert-rules/slo-burn-rate.yaml` | SLOs 13.3 com burn rate multi-janela (MPI, eventos ≤ 60 s, alta ≤ 5 min, tarefa pós-alta ≤ 15 min, agenda/regulação) |
| `alert-rules/platform-alerts.yaml` | Conector crítico ≤ 5 min, DLQ crítica ≤ 30 min, Kafka, CNPG (WAL/backup), Temporal, IA, segurança |
| `dashboards/*.json` | Plataforma, Conectores, MPI, FHIR, IA — mesmos JSON do compose (montados no Grafana local) |
| `dashboards-configmap.yaml` | ConfigMaps gerados dos JSON (sidecar do Grafana) — regenerar com `scripts/gen-dashboards-configmap.sh` |
| `METRICS.md` | Contrato de nomes de métricas |

Dashboards 5 (Workflows) e 7 (Segurança) da seção 13.2 são compostos pelos painéis de Temporal/SLA em
*Plataforma* e pelas regras de `sus.security`; dashboards dedicados ficam para a Fase 3.
