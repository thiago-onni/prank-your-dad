-- Produção por competência × unidade × instrumento (kind): registros, validação, pendências, correções,
-- glosa (rejeição) e pagamento. Tolerante (vazio sem a fonte). Supressão n<5.
with base as (
    select
        tenant_id,
        competence,
        health_unit_key,
        health_unit_cnes,
        production_kind,
        count(*) as n_records,
        sum(quantity) as quantity_total,
        {{ count_true('is_validated') }} as n_validated,
        {{ count_true('is_pending') }} as n_pending,
        {{ count_true('had_issue') }} as n_with_issue,
        {{ count_true('was_corrected') }} as n_corrected,
        {{ count_true('has_final_outcome') }} as n_with_outcome,
        {{ count_true('is_accepted') }} as n_accepted,
        {{ count_true('is_rejected') }} as n_rejected,
        {{ count_true('is_paid') }} as n_paid,
        sum(estimated_value) as estimated_value_total,
        sum(paid_amount) as paid_amount_total
    from {{ ref('fct_production') }}
    where competence is not null
    group by tenant_id, competence, health_unit_key, health_unit_cnes, production_kind
)

select
    tenant_id,
    competence,
    health_unit_key,
    health_unit_cnes,
    production_kind,
    {{ suppress_small_cells('n_records') }} as n_records,
    {{ suppress_small_cells('quantity_total', n='n_records') }} as quantity_total,
    {{ suppress_small_cells('n_validated') }} as n_validated,
    {{ suppress_small_cells('n_pending') }} as n_pending,
    {{ suppress_small_cells('n_with_issue') }} as n_with_issue,
    {{ suppress_small_cells('n_corrected') }} as n_corrected,
    {{ suppress_small_cells('n_accepted') }} as n_accepted,
    {{ suppress_small_cells('n_rejected') }} as n_rejected,
    {{ suppress_small_cells('n_paid') }} as n_paid,
    {{ suppress_small_cells('estimated_value_total', n='n_records') }} as estimated_value_total,
    {{ suppress_small_cells('paid_amount_total', n='n_paid') }} as paid_amount_total,
    {{ suppress_small_cells(safe_ratio('n_rejected', 'n_with_outcome'), n='n_with_outcome') }} as rejection_rate,
    {{ suppress_small_cells(safe_ratio('n_with_issue', 'n_records'), n='n_records') }} as issue_rate
from base
