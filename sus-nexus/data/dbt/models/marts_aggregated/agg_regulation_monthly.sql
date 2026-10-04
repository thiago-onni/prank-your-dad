-- Regulação por mês de solicitação × unidade solicitante × prioridade: volume, espera (p50/p90), SLA,
-- devoluções e realização (PLANO 4.9). Células com n < 5 suprimidas.
with base as (
    select
        tenant_id,
        {{ month_of('requested_date') }} as month_start,
        requesting_unit_key,
        requesting_cnes,
        priority,
        count(*) as n_requests,
        {{ count_true('scheduled_at is not null') }} as n_scheduled,
        {{ count_true('is_performed') }} as n_performed,
        {{ count_true('was_returned') }} as n_returned,
        {{ count_true('is_denied') }} as n_denied,
        {{ count_true('is_cancelled') }} as n_cancelled,
        {{ count_true('is_open') }} as n_open,
        {{ count_true('is_sla_met is not null') }} as n_sla_evaluated,
        {{ count_true('is_sla_met') }} as n_sla_met,
        {{ pctl('wait_days_to_schedule', 0.5) }} as wait_days_p50,
        {{ pctl('wait_days_to_schedule', 0.9) }} as wait_days_p90,
        avg(wait_days_to_schedule) as wait_days_avg
    from {{ ref('fct_regulation_requests') }}
    where requested_date is not null
    group by tenant_id, {{ month_of('requested_date') }}, requesting_unit_key, requesting_cnes, priority
)

select
    tenant_id,
    month_start,
    {{ competence_of('month_start') }} as competence,
    requesting_unit_key,
    requesting_cnes,
    priority,
    {{ suppress_small_cells('n_requests') }} as n_requests,
    {{ suppress_small_cells('n_scheduled') }} as n_scheduled,
    {{ suppress_small_cells('n_performed') }} as n_performed,
    {{ suppress_small_cells('n_returned') }} as n_returned,
    {{ suppress_small_cells('n_denied') }} as n_denied,
    {{ suppress_small_cells('n_cancelled') }} as n_cancelled,
    {{ suppress_small_cells('n_open') }} as n_open,
    {{ suppress_small_cells('wait_days_p50', n='n_scheduled') }} as wait_days_p50,
    {{ suppress_small_cells('wait_days_p90', n='n_scheduled') }} as wait_days_p90,
    {{ suppress_small_cells('wait_days_avg', n='n_scheduled') }} as wait_days_avg,
    {{ suppress_small_cells(safe_ratio('n_sla_met', 'n_sla_evaluated'), n='n_sla_evaluated') }} as sla_met_rate,
    {{ suppress_small_cells(safe_ratio('n_returned', 'n_requests'), n='n_requests') }} as return_rate,
    {{ suppress_small_cells(safe_ratio('n_performed', 'n_requests'), n='n_requests') }} as performed_rate
from base
