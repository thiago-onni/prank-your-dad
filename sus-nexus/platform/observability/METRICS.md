# Contrato de métricas (seção 13.1: "métricas técnicas como contrato")

Nomes abaixo são **contrato** entre core, SDK de conectores, ai-service e os dashboards/alertas desta
pasta. Prefixo `sus_`; unidades em segundos/bytes; labels **nunca** contêm PII (CPF, CNS, nome, e-mail,
IDs de cidadão). Labels comuns: `service`, `tenant_id`, `environment` (adicionadas pelo ServiceMonitor).

| Métrica | Tipo | Labels | Origem |
|---|---|---|---|
| `http_server_requests_seconds` (bucket/count/sum) | histogram | `uri`, `method`, `status`, `client_id` (fhir) | Quarkus Micrometer (core, fhir, conectores) |
| `sus_outbox_publish_latency_seconds` | histogram | — | core (recorded_at − occurred_at do envelope ao confirmar no Kafka) |
| `sus_outbox_pending_events` | gauge | — | core |
| `sus_dlq_messages_total` | counter | `consumer`, `reason` | core/conectores (ao publicar em `sus.dlq.v1`) |
| `sus_dlq_untriaged_messages` | gauge | `consumer`, `critical` | core (tasks de triagem abertas) |
| `kafka_consumergroup_lag` | gauge | `consumergroup`, `topic` | Strimzi kafka-exporter |
| `kafka_connect_connector_status` | gauge | `connector` | Kafka Connect JMX (1=RUNNING) |
| `sus_connector_messages_received_total` / `_processed_total` / `_failed_total` | counter | `connector_id`, `entity` | Connector SDK |
| `sus_connector_processing_latency_seconds` | histogram | `connector_id` | Connector SDK |
| `sus_connector_last_success_timestamp_seconds` | gauge | `connector_id`, `critical` | Connector SDK |
| `sus_connector_status` | gauge (0 down, 1 degraded, 2 healthy) | `connector_id` | Connector SDK |
| `sus_connector_retries_total` | counter | `connector_id`, `attempt` | Connector SDK |
| `sus_connector_raw_objects_total` | counter | `connector_id` | Connector SDK |
| `integration_reconciliation_gap_total` | gauge | `connector_id`, `entity` | core (job de reconciliação, PLANO 7.3) |
| `sus_mpi_match_total` | counter | `outcome` (auto_linked, new_citizen, review, rejected), `rule_version` | core/identity |
| `sus_mpi_duplicates_detected_total`, `sus_mpi_merge_total`, `sus_mpi_unmerge_total` | counter | — | core/identity |
| `sus_mpi_review_queue_size` | gauge | — | core/identity |
| `sus_mpi_review_age_seconds` | histogram | — | core/identity |
| `sus_mpi_rule_info` | gauge (1) | `rule_version` | core/identity |
| `sus_access_log_total` | counter | `purpose`, `break_glass` | core/audit |
| `sus_authz_decisions_total` | counter | `service`, `decision`, `reason` | core/fhir/ai (cliente OPA) |
| `sus_fhir_validation_errors_total` | counter | `profile`, `issue_code`, `client_id` | fhir-gateway |
| `sus_fhir_audit_resources_total` | counter | `resource_type` | fhir-gateway |
| `sus_rnds_submissions_total` | counter | `outcome` | fhir-gateway (F4) |
| `sus_discharge_processing_seconds` | histogram | — | core/hospital (alta → evento processado) |
| `sus_post_discharge_task_seconds` | histogram | — | core/tasks |
| `workflow_sla_breach_total` | counter | `workflow_type` | core/Temporal workers (PLANO 8.2) |
| `temporal_workflow_active` | gauge | `workflow_type` | Temporal server |
| `sus_ai_calls_total` | counter | `agent`, `model` | ai-service |
| `sus_ai_denied_total` | counter | `agent`, `reason` | ai-service |
| `sus_ai_approval_pending` | gauge | `agent` | ai-service |
| `sus_ai_approval_total` | counter | `agent`, `decision` | ai-service |
| `sus_ai_cost_usd_total` | counter | `agent`, `model` | ai-service (via LiteLLM) |
| `sus_ai_tokens_total` | counter | `agent`, `direction` | ai-service |
| `sus_ai_latency_seconds` | histogram | `agent` | ai-service |
| `sus_ai_tool_calls_total` | counter | `agent`, `action_class` | ai-service |
| `sus_ai_eval_precision` / `sus_ai_eval_recall` | gauge | `agent` | CI de avaliação (pushgateway) |
| `sus_tenant_info` | gauge (1) | `tenant_id` | todos (para variável de dashboard) |

Dashboards: `dashboards/*.json` (plataforma, conectores, MPI, FHIR, IA). Alertas: `alert-rules/`.
