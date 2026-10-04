-- Fila de regulação atual (foto no momento da execução) por especialidade × prioridade × tipo.
with base as (
    select
        tenant_id,
        specialty,
        priority,
        request_kind,
        count(*) as n_open,
        {{ count_true('is_sla_breached') }} as n_overdue,
        {{ count_true("status in ('pending_documents', 'returned')") }} as n_pending_documents,
        {{ pctl('days_waiting_open', 0.5) }} as days_waiting_p50,
        {{ pctl('days_waiting_open', 0.9) }} as days_waiting_p90,
        max(days_waiting_open) as days_waiting_max
    from {{ ref('fct_regulation_requests') }}
    where is_open and scheduled_at is null
    group by tenant_id, specialty, priority, request_kind
)

select
    tenant_id,
    {{ now_local() }} as as_of,
    specialty,
    priority,
    request_kind,
    {{ suppress_small_cells('n_open') }} as n_open,
    {{ suppress_small_cells('n_overdue') }} as n_overdue,
    {{ suppress_small_cells('n_pending_documents') }} as n_pending_documents,
    {{ suppress_small_cells('days_waiting_p50', n='n_open') }} as days_waiting_p50,
    {{ suppress_small_cells('days_waiting_p90', n='n_open') }} as days_waiting_p90,
    {{ suppress_small_cells('days_waiting_max', n='n_open') }} as days_waiting_max
from base
