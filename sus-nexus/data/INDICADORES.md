# Dicionário de indicadores — SUS Nexus (Fase 4)

Fonte de verdade técnica: `dbt/models/marts_aggregated/*.yml` (`meta.indicador`) e `dbt/seeds/seed_metas_indicadores.csv` (nome, meta, dono). Publicado no OpenMetadata pela ingestão do dbt (`platform/compose/openmetadata/ingestion/dbt.yaml`). Indicadores priorizados com a gestão (PLANO 4.9/10.2); **metas são exemplos** até a pactuação.

Regras comuns:

- **Periodicidade**: mensal (mês civil no fuso America/Sao_Paulo; competência `AAAAMM`), recalculada a cada execução do dbt; a fila de regulação (`agg_regulation_queue_current`) é uma foto diária.
- **Agregação**: por unidade (CNES) e total do município (`aggregation_level`), em `marts_aggregated.agg_indicadores_mensais`; quebras adicionais nos `agg_*_monthly`.
- **Supressão**: célula com denominador `0 < n < 5` → numerador, denominador e valor nulos (`is_suppressed`). Contagens `0 < n < 5` nulas nos `agg_*`.
- **Pseudonimização**: só dados agregados; nenhum identificador individual (ver `README.md`).
- **Situação**: `is_on_target` compara `indicator_value` com `target_value` conforme `direction` (`maior_melhor`/`menor_melhor`).

| Código | Indicador | Fórmula | Numerador | Denominador | Unidade | Meta (exemplo) | Fonte (eventos → fato) | Dono | Ref. |
|---|---|---|---|---|---|---|---|---|---|
| `AGE_ABSENTEISMO` | Taxa de absenteísmo (no-show) | Faltas / (comparecimentos + faltas) no mês da data agendada | agendamentos com desfecho no_show | agendamentos com desfecho attended ou no_show | proporção (0–1) | ≤ 0.15 | sus.aps.appointment.v1, sus.schedule.appointment.v1 → `fct_appointments` | Coordenação da APS | AGE-010 |
| `AGE_COMPARECIMENTO` | Taxa de comparecimento | Comparecimentos / (comparecimentos + faltas) | attended | attended + no_show | proporção (0–1) | ≥ 0.85 | idem → `fct_appointments` | Coordenação da APS | AGE-010 |
| `AGE_CANCELAMENTO` | Taxa de cancelamento | Cancelados / agendados no mês | cancelled | todos os agendamentos do mês | proporção (0–1) | ≤ 0.10 | idem → `fct_appointments` | Coordenação da APS | AGE-010 |
| `AGE_REAPROVEITAMENTO` | Reaproveitamento de vagas canceladas | Cancelados com vaga reocupada / cancelados | cancelled com is_slot_reused | cancelled | proporção (0–1) | ≥ 0.50 | idem → `fct_appointments` | Central de agendamento | AGE-010 |
| `REG_ESPERA_P50_DIAS` | Tempo de espera até agendamento (mediana) | Mediana de dias entre a solicitação e o agendamento (status scheduled), por mês da solicitação | — | solicitações agendadas | dias | ≤ 30 | sus.regulation.request.v1, sus.regulation.status.v1 → `fct_regulation_requests` | Complexo regulador | PLANO 4.9 |
| `REG_ESPERA_P90_DIAS` | Tempo de espera até agendamento (p90) | Percentil 90 de dias entre a solicitação e o agendamento | — | solicitações agendadas | dias | ≤ 90 | idem → `fct_regulation_requests` | Complexo regulador | PLANO 4.9 |
| `REG_SLA_CUMPRIDO` | Solicitações reguladas dentro do SLA | Solicitações agendadas/decididas até sla_due_at / solicitações com SLA avaliável | is_sla_met = true | is_sla_met não nulo | proporção (0–1) | ≥ 0.80 | idem → `fct_regulation_requests` | Complexo regulador | PLANO 4.9 |
| `REG_DEVOLUCAO` | Taxa de devolução de solicitações | Solicitações devolvidas ao menos uma vez / solicitações | returned_count > 0 | solicitações do mês | proporção (0–1) | ≤ 0.10 | idem → `fct_regulation_requests` | Complexo regulador | PLANO 4.9 |
| `REG_REALIZACAO` | Solicitações encerradas com realização | Solicitações realizadas / solicitações encerradas | status performed | solicitações não abertas (realizadas, negadas, canceladas, faltas, expiradas) | proporção (0–1) | ≥ 0.85 | idem → `fct_regulation_requests` | Complexo regulador | PLANO 4.9 |
| `EXA_CICLO_COMPLETO` | Pedidos de exame com ciclo completo (retorno) | Pedidos com retorno ao solicitante / pedidos | is_cycle_complete | pedidos do mês | proporção (0–1) | ≥ 0.60 | sus.exam.order.v1, sus.exam.result.v1, agendamentos → `fct_exam_cycle` | Coordenação de apoio diagnóstico | EXA-010 |
| `EXA_RESULTADO_SEM_RETORNO` | Resultados sem retorno ao solicitante | Pedidos com laudo e sem retorno / pedidos com laudo | is_result_without_return | is_reported | proporção (0–1) | ≤ 0.30 | idem → `fct_exam_cycle` | Coordenação de apoio diagnóstico | EXA-010 |
| `EXA_DIAS_PEDIDO_RESULTADO_P50` | Dias do pedido ao resultado (mediana) | Mediana de dias do pedido ao laudo/resultado | — | pedidos com laudo | dias | ≤ 15 | idem → `fct_exam_cycle` | Coordenação de apoio diagnóstico | EXA-010 |
| `HOS_REINTERNACAO_30D` | Reinternação em até 30 dias | Altas vivas de internação seguidas de nova internação do mesmo cidadão em até 30 dias / altas vivas de internação | is_readmitted_30d | altas vivas (inpatient) | proporção (0–1) | ≤ 0.10 | sus.hospital.adt.v1, sus.hospital.discharge.v1 → `fct_hospital_episodes` | Diretoria hospitalar | HOS-005 |
| `HOS_CONTATO_POS_ALTA_7D` | Contato da APS em até 7 dias após a alta | Altas vivas com contato da APS em até 7 dias / altas vivas | is_contacted_within_7d | altas vivas | proporção (0–1) | ≥ 0.80 | adt/discharge + agendamentos + sus.task.v1 → `fct_hospital_episodes` | Coordenação da APS | HOS-003 |
| `HOS_PERMANENCIA_MEDIA_DIAS` | Tempo médio de permanência (internação) | Soma dos dias de permanência / altas de internação | soma de length_of_stay_days | altas (inpatient) | dias | ≤ 5 | sus.hospital.adt.v1, sus.hospital.discharge.v1 → `fct_hospital_episodes` | Diretoria hospitalar | HOS-001 |
| `CUI_LACUNAS_RESOLVIDAS` | Lacunas de cuidado resolvidas | Lacunas resolvidas / lacunas detectadas no mês | is_resolved | lacunas detectadas | proporção (0–1) | ≥ 0.60 | sus.caregap.v1 → `fct_care_gaps` | Coordenação de linhas de cuidado | CUI-003 |
| `TAR_SLA_CUMPRIDO` | Tarefas concluídas dentro do SLA | Tarefas concluídas até due_at / tarefas com SLA avaliável | is_sla_met | is_sla_met não nulo | proporção (0–1) | ≥ 0.85 | sus.task.v1 → `fct_tasks` | Gestão operacional | PLANO 4.9 |
| `TAR_AUTOMACAO` | Tarefas criadas por automação (agente/workflow/regra/conector) | Tarefas criadas por agente/workflow/regra/conector / tarefas criadas | is_automated | tarefas criadas | proporção (0–1) | ≥ 0.50 | sus.task.v1 → `fct_tasks` | Escritório de IA | PLANO 9.3 |
| `TAR_AGENTE_SLA_CUMPRIDO` | Tarefas criadas por agentes concluídas no SLA | Tarefas criadas por agentes concluídas no SLA / tarefas de agentes com SLA avaliável | is_sla_met (origin agent) | is_sla_met não nulo (origin agent) | proporção (0–1) | ≥ 0.85 | sus.task.v1 → `fct_tasks` | Escritório de IA | PLANO 9.3 |
| `PRO_GLOSA` | Taxa de glosa (rejeição) da produção | Registros glosados / registros com retorno final, por mês de geração do registro | último `outcome` = rejected | último `outcome` em accepted/rejected/paid (exclui `received`) | proporção (0–1) | ≤ 0.05 | sus.production.record/outcome.v1 → `fct_production` | Controle e avaliação | PRO-006 |

## Observações por indicador

- `AGE_ABSENTEISMO`: Exclui duplicidades detectadas e agendamentos futuros/sem desfecho.
- `AGE_COMPARECIMENTO`: Complementar ao absenteísmo.
- `AGE_REAPROVEITAMENTO`: Vaga reocupada = outro agendamento no mesmo CNES, serviço e horário criado após o cancelamento e não cancelado.
- `REG_ESPERA_P50_DIAS`: Trino: approx_percentile; DuckDB: quantile_cont.
- `REG_SLA_CUMPRIDO`: Em aberto além do prazo conta como descumprido; em aberto dentro do prazo não entra.
- `REG_DEVOLUCAO`: Devolução = status returned, return_to_origin ou evento request.returned.
- `EXA_CICLO_COMPLETO`: Retorno = consulta realizada do mesmo cidadão vinculada ao pedido ou consulta de retorno até 60 dias após o laudo.
- `EXA_RESULTADO_SEM_RETORNO`: Base da busca ativa de resultados (EXA-007).
- `HOS_REINTERNACAO_30D`: Por mês da alta; hospital = CNES do episódio índice.
- `HOS_CONTATO_POS_ALTA_7D`: Contato = consulta realizada ou tarefa post_discharge_followup concluída após a alta. Unidade = UBS de referência.
- `CUI_LACUNAS_RESOLVIDAS`: Território = unidade/equipe da lacuna (agg_care_gaps_monthly também por linha de cuidado).
- `TAR_SLA_CUMPRIDO`: Sem unidade (agregação municipal).
- `TAR_AUTOMACAO`: Origem em data.origin.kind.
- `TAR_AGENTE_SLA_CUMPRIDO`: Métrica de liberação de autonomia (PLANO 9.3).
- `PRO_GLOSA`: Último retorno oficial ordenado por `processed_at`; motivo em `reason_code`/`reason`. Vazio se `bronze.events_production` não existir no ambiente.

## Outras medidas publicadas (sem meta)

| Modelo | Medidas |
|---|---|
| `agg_appointments_monthly` | agendados, realizados, comparecimentos, faltas, cancelados, vagas reaproveitadas, pendentes; taxas por unidade × canal (APS/rede) |
| `agg_regulation_monthly` | solicitações, agendadas, realizadas, devolvidas, negadas, canceladas, em aberto; espera p50/p90/média; SLA; por unidade solicitante × prioridade |
| `agg_regulation_queue_current` | fila atual por especialidade × prioridade × tipo: em fila, fora do SLA, pendência documental, espera p50/p90/máx |
| `agg_exam_cycle_monthly` | pedidos, agendados, realizados, laudos, ciclos completos, resultados sem retorno, críticos; dias até resultado p50/p90 |
| `agg_hospital_monthly` | altas, óbitos, reinternações 30d, contatos 7d, tarefas pós-alta, permanência média/p50, horas até contato p50, minutos alta→tarefa p90 (SLO < 15 min) |
| `agg_tasks_monthly` | criadas, concluídas, abertas, escalonadas, no/fora do SLA, horas até conclusão p50/p90 por tipo × origem |
| `agg_care_gaps_monthly` | detectadas, resolvidas, abertas, com tarefa, dias em aberto p50 por unidade × equipe × linha de cuidado |
| `agg_production_monthly` | por competência × unidade × instrumento (`kind`): registros, quantidade, validados, pendentes, com pendência, corrigidos, aceitos, glosados, pagos, valor estimado e pago; taxas de glosa e de pendência |
