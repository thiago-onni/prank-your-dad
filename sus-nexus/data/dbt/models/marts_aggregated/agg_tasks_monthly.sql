-- Tarefas por mês de criação × tipo × origem (automação × humano): volume, conclusão e SLA.
with base as (
    select
        tenant_id,
        {{ month_of('created_date') }} as month_start,
        task_type,
        origin_group,
        origin_kind,
        count(*) as n_created,
        {{ count_true('is_completed') }} as n_completed,
        {{ count_true('is_open') }} as n_open,
        {{ count_true('is_escalated') }} as n_escalated,
        {{ count_true('is_sla_met is not null') }} as n_sla_evaluated,
        {{ count_true('is_sla_met') }} as n_sla_met,
        {{ count_true('is_sla_breached') }} as n_sla_breached,
        {{ pctl('hours_to_complete', 0.5) }} as hours_to_complete_p50,
        {{ pctl('hours_to_complete', 0.9) }} as hours_to_complete_p90
    from {{ ref('fct_tasks') }}
    where created_date is not null
    group by tenant_id, {{ month_of('created_date') }}, task_type, origin_group, origin_kind
)

select
    tenant_id,
    month_start,
    {{ competence_of('month_start') }} as competence,
    task_type,
    origin_group,
    origin_kind,
    {{ suppress_small_cells('n_created') }} as n_created,
    {{ suppress_small_cells('n_completed') }} as n_completed,
    {{ suppress_small_cells('n_open') }} as n_open,
    {{ suppress_small_cells('n_escalated') }} as n_escalated,
    {{ suppress_small_cells('n_sla_met') }} as n_sla_met,
    {{ suppress_small_cells('n_sla_breached') }} as n_sla_breached,
    {{ suppress_small_cells('hours_to_complete_p50', n='n_completed') }} as hours_to_complete_p50,
    {{ suppress_small_cells('hours_to_complete_p90', n='n_completed') }} as hours_to_complete_p90,
    {{ suppress_small_cells(safe_ratio('n_sla_met', 'n_sla_evaluated'), n='n_sla_evaluated') }} as sla_met_rate,
    {{ suppress_small_cells(safe_ratio('n_completed', 'n_created'), n='n_created') }} as completion_rate
from base
