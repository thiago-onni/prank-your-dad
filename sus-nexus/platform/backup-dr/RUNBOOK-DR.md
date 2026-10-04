# RUNBOOK — Backup e Recuperação de Desastre (DR)

Dono: SRE SUS Nexus · Revisão: a cada teste semestral · Última revisão: (preencher)

## 1. Objetivos (PLANO_IMPLEMENTACAO 4.2.6)

| Ativo | Estratégia | RPO | RTO | Mecanismo neste repositório |
|---|---|---|---|---|
| PostgreSQL (core, fhir, temporal, keycloak, langfuse, ai, apicurio) | WAL contínuo + base backup diário + réplica síncrona; cópia off-site | ≤ 5 min | ≤ 1 h | CNPG `barmanObjectStore` (helm umbrella `cnpg.backup`), `ScheduledBackup` diário/semanal, replicação do bucket `backups` para o DR |
| Kafka | MirrorMaker 2 para DR (tópicos críticos) + replay a partir do outbox/raw zone | ≤ 15 min | ≤ 4 h | `mirrormaker2.yaml` (IdentityReplicationPolicy, offsets de consumer groups sincronizados) |
| MinIO | Replicação de bucket para o DR | ≤ 15 min | ≤ 4 h | `minio.replication` (umbrella) — `audit-archive` e `backups` (+ `raw-zone`, `documents` em prod) |
| Configuração do cluster | GitOps (reconstrução declarativa) + Velero | — | ≤ 4 h | `platform/argocd` + `velero-schedules.yaml` (diário completo + 4h crítico) |
| Segredos | Snapshot OpenBao criptografado + chaves de recuperação com custódia dividida (Shamir 3-de-5) | ≤ 24 h | ≤ 2 h | `bao operator raft snapshot save` (CronJob no chart openbao, bucket `backups/openbao/`) |

Ambiente `dr` = site secundário (outro prédio/nuvem BR) com cluster RKE2/EKS mínimo, operadores instalados
pelo Argo CD (cluster registrado como `prod-dr`), bancos **em modo réplica** (CNPG `replica.enabled`) e
Kafka recebendo MM2.

## 2. Monitoramento contínuo (o que deve estar verde)

- `SusCnpgBackupFailed` / `SusCnpgLastBackupOld` (Prometheus) — WAL arquivado < 15 min, base backup < 36 h.
- `SusDrKafkaReplicationLag` — MM2 < 15 min.
- `VeleroNoRecentBackup` / `VeleroBackupFailures`.
- MinIO: `minio_bucket_replication_failed_count` = 0 (dashboard Plataforma).
- `pitr_test_success` = 1 no último dia 1 do mês (CronJob `cnpg-pitr-test`).

## 3. Cenários e procedimentos

### 3.1 Restauração pontual (PITR) de um banco — erro lógico/humano (RTO ≤ 1 h)

1. Congelar escritas: escalar `core-municipal` para 0 (`kubectl -n core scale rollout core-municipal --replicas=0`
   ou pausar o Rollout) e pausar o conector Debezium (`kubectl -n data annotate kafkaconnector core-outbox strimzi.io/pause-reconciliation=true` + `PUT /connectors/core-outbox/pause`).
2. Criar cluster de recuperação (modelo em `cnpg-backup.yaml`, CronJob `cnpg-pitr-test`) com
   `recoveryTarget.targetTime` imediatamente anterior ao incidente.
3. Validar dados no cluster recuperado (consultas de sanidade; comparar contagens com `sus.audit.v1`).
4. Promover: trocar `QUARKUS_DATASOURCE_JDBC_URL` (values) para o novo cluster **ou** fazer `pg_dump`/`pg_restore`
   seletivo das tabelas afetadas no cluster original (preferido para erro localizado).
5. Recriar slot de replicação do Debezium se o cluster foi trocado (`slot.name: core_outbox`); o outbox
   garante que eventos não publicados serão emitidos; consumidores são idempotentes (`event_inbox`).
6. Religar escritas; registrar incidente e *postmortem*.

### 3.2 Perda do site primário — failover para DR (RTO ≤ 4 h)

**Decisão de failover**: Coordenador de incidente + Secretário(a)/CIO, registrada por escrito (chat + e-mail).

1. **Declarar** o incidente; congelar DNS TTL (já é 60 s nas zonas `terraform/modules/dns`).
2. **Bancos**: no DR, promover réplicas CNPG — `kubectl cnpg promote core-db <instance>` ou, para clusters
   em modo réplica, remover `spec.replica.enabled` e aplicar. Verificar `pg_is_in_recovery() = false`.
   Se não houver réplica no DR: restaurar do bucket replicado (`bootstrap.recovery` apontando para
   `s3://backups/cnpg/prod/<cluster>`), último WAL ≤ 5 min.
3. **Kafka**: MM2 já replicou tópicos (mesmo nome, IdentityReplicationPolicy) e offsets de consumer groups
   (checkpoints). Parar MM2 (`kubectl -n data delete kmm2 sus-mm2-dr`) para evitar loop. Consumidores
   retomam das posições sincronizadas; divergência máxima = RPO (≤ 15 min) → **replay** dos eventos do
   outbox desde `now()-30min` com header `replay=true` (KAF-012) quando o banco restaurado estiver à frente.
4. **MinIO**: buckets replicados já no DR; no DR, remover regras de replicação de saída (`mc replicate rm`)
   para evitar escrita de volta no site perdido.
5. **Segredos**: OpenBao no DR restaurado do último snapshot (`bao operator raft snapshot restore`) e
   deslacrado com 3 das 5 chaves (custodiantes: SRE lead, DPO, CIO, +2). Validar leitura de
   `secret/prod/core-municipal/db`.
6. **Aplicações**: Argo CD do DR (ou Argo central apontando para `prod-dr`) sincroniza
   `sus-nexus-prod-*` com `values-prod.yaml` + overlay `values-dr.yaml` (hostnames iguais, endpoints
   internos iguais — DNS resolve para o DR). Ordem = sync waves (data → security → workflows → core → ...).
7. **Keycloak**: realm vem do `KeycloakRealmImport` + banco `keycloak-db` restaurado (sessões se perdem;
   usuários refazem login; MFA preservado por estar no banco).
8. **DNS/Borda**: `terraform apply` em `envs/prod` com `var.active_site = "dr"` (módulo `dns` troca os
   registros `api`, `app`, `fhir`, `auth` para o APISIX do DR). Certificados: cert-manager do DR emite
   (DNS-01) ou restaura `Secret`s via Velero.
9. **Validação**: smoke test E2E (login, busca de cidadão, criação de agendamento, evento publicado,
   dashboard Plataforma verde), conectores reconectam às fontes (VPN do DR), RNDS (F4) re-sincroniza.
10. **Comunicação**: status page interno; unidades de saúde avisadas de janela de degradação.

### 3.3 Retorno ao primário (failback)

Reverter os passos com o primário como "DR" temporário: estabelecer replicação CNPG DR→primário, MM2
DR→primário, replicação MinIO, janela de corte (≤ 30 min de indisponibilidade planejada), troca de DNS.

### 3.4 Perda de configuração/cluster (sem perda de dados)

Reconstruir cluster via `terraform apply` → instalar Argo CD → `root-app.yaml` → operadores → `velero restore`
dos namespaces de configuração (`4h-critical-config`) → sync das apps. Bancos reconectam aos PVs (CNPG
`bootstrap.recovery` de PVC snapshot ou do object store).

### 3.5 Comprometimento de segredos

Rotacionar via OpenBao (DB dinâmicos: revogar leases; estáticos: regerar e `ExternalSecret` refresh);
rotacionar chave HMAC por tenant (`SUS_HMAC_KEY_RING` suporta múltiplas versões), invalidar sessões Keycloak
(`/admin/realms/sus-nexus/logout-all`), assinar novas imagens com nova chave cosign se a antiga vazou.

## 4. Teste semestral obrigatório (com relatório)

Agenda: **março e setembro**, sábado 08h–14h, em hml primeiro (ensaio) e depois em prod/DR com tráfego
desviado. Checklist:

| # | Item | Critério de sucesso | Evidência |
|---|---|---|---|
| 1 | PITR core-db para T-24h | cluster Ready < 60 min; contagens coerentes | log do job + screenshot |
| 2 | Failover Kafka (parar MM2, consumir no DR) | lag < 15 min; consumer groups retomam sem reprocessar > RPO | métricas MM2 |
| 3 | Restauração OpenBao de snapshot | unseal 3/5 ok; segredo de teste lido | ata assinada pelos custodiantes |
| 4 | Velero restore de `security` + `argocd` em cluster limpo | Argo reconcilia sem erro | `velero restore describe` |
| 5 | Troca de DNS para DR | `api.`/`app.`/`fhir.`/`auth.` respondem do DR em < 5 min | dig + smoke test |
| 6 | Smoke E2E no DR | jornada "cidadão → agendamento → evento → timeline" ok | relatório de teste |
| 7 | Failback | sem perda de eventos (comparar `sus.audit.v1` nos dois sites) | relatório |
| 8 | RTO/RPO medidos | ≤ metas da tabela 1 | planilha de tempos |

Relatório (modelo em `docs/runbooks/dr-report-template.md`): participantes, cronologia, tempos medidos,
desvios, ações corretivas com prazo. Entregue ao comitê de governança e ao DPO em até 10 dias úteis.

## 5. Contatos e custódia

| Papel | Quem | Backup |
|---|---|---|
| Coordenador de incidente | SRE lead | Tech lead core |
| Custodiantes das chaves OpenBao (Shamir 3/5) | SRE lead, DPO, CIO, Tech lead core, Gerente de TI SMS | — |
| Provedor de datacenter / nuvem | (preencher) | — |
| DATASUS / RNDS (F4) | (preencher) | — |
