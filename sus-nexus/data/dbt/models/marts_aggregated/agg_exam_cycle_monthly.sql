-- Ciclo de exames por mês do pedido × unidade solicitante × categoria (EXA-010).
with base as (
    select
        tenant_id,
        {{ month_of('requested_date') }} as month_start,
        requesting_unit_key,
        requesting_cnes,
        exam_category,
        count(*) as n_orders,
        {{ count_true('is_scheduled') }} as n_scheduled,
        {{ count_true('is_performed') }} as n_performed,
        {{ count_true('is_reported') }} as n_reported,
        {{ count_true('is_cycle_complete') }} as n_cycle_complete,
        {{ count_true('is_result_without_return') }} as n_result_without_return,
        {{ count_true('is_cancelled') }} as n_cancelled,
        {{ count_true('is_critical') }} as n_critical,
        {{ pctl('days_request_to_result', 0.5) }} as days_request_to_result_p50,
        {{ pctl('days_request_to_result', 0.9) }} as days_request_to_result_p90,
        {{ pctl('days_result_to_return', 0.5) }} as days_result_to_return_p50
    from {{ ref('fct_exam_cycle') }}
    where requested_date is not null
    group by tenant_id, {{ month_of('requested_date') }}, requesting_unit_key, requesting_cnes, exam_category
)

select
    tenant_id,
    month_start,
    {{ competence_of('month_start') }} as competence,
    requesting_unit_key,
    requesting_cnes,
    exam_category,
    {{ suppress_small_cells('n_orders') }} as n_orders,
    {{ suppress_small_cells('n_scheduled') }} as n_scheduled,
    {{ suppress_small_cells('n_performed') }} as n_performed,
    {{ suppress_small_cells('n_reported') }} as n_reported,
    {{ suppress_small_cells('n_cycle_complete') }} as n_cycle_complete,
    {{ suppress_small_cells('n_result_without_return') }} as n_result_without_return,
    {{ suppress_small_cells('n_cancelled') }} as n_cancelled,
    {{ suppress_small_cells('n_critical') }} as n_critical,
    {{ suppress_small_cells('days_request_to_result_p50', n='n_reported') }} as days_request_to_result_p50,
    {{ suppress_small_cells('days_request_to_result_p90', n='n_reported') }} as days_request_to_result_p90,
    {{ suppress_small_cells('days_result_to_return_p50', n='n_cycle_complete') }} as days_result_to_return_p50,
    {{ suppress_small_cells(safe_ratio('n_cycle_complete', 'n_reported'), n='n_reported') }} as return_after_result_rate,
    {{ suppress_small_cells(safe_ratio('n_cycle_complete', 'n_orders'), n='n_orders') }} as cycle_completion_rate
from base
