-- Agendamentos por mês (data agendada) × unidade × canal — comparecimento, absenteísmo, cancelamento e
-- reaproveitamento de vagas (AGE-010). Células com n < 5 suprimidas.
with base as (
    select
        tenant_id,
        {{ month_of('scheduled_date') }} as month_start,
        health_unit_key,
        health_unit_cnes,
        agenda_channel,
        count(*) as n_scheduled,
        {{ count_true('is_realized_slot') }} as n_realized,
        {{ count_true('is_attended') }} as n_attended,
        {{ count_true('is_no_show') }} as n_no_show,
        {{ count_true('is_cancelled') }} as n_cancelled,
        {{ count_true('is_cancelled and is_slot_reused') }} as n_cancelled_reused,
        {{ count_true('is_pending') }} as n_pending
    from {{ ref('fct_appointments') }}
    where scheduled_date is not null
    group by tenant_id, {{ month_of('scheduled_date') }}, health_unit_key, health_unit_cnes, agenda_channel
)

select
    tenant_id,
    month_start,
    {{ competence_of('month_start') }} as competence,
    health_unit_key,
    health_unit_cnes,
    agenda_channel,
    {{ suppress_small_cells('n_scheduled') }} as n_scheduled,
    {{ suppress_small_cells('n_realized') }} as n_realized,
    {{ suppress_small_cells('n_attended') }} as n_attended,
    {{ suppress_small_cells('n_no_show') }} as n_no_show,
    {{ suppress_small_cells('n_cancelled') }} as n_cancelled,
    {{ suppress_small_cells('n_cancelled_reused') }} as n_cancelled_reused,
    {{ suppress_small_cells('n_pending') }} as n_pending,
    {{ suppress_small_cells(safe_ratio('n_attended', 'n_realized'), n='n_realized') }} as attendance_rate,
    {{ suppress_small_cells(safe_ratio('n_no_show', 'n_realized'), n='n_realized') }} as no_show_rate,
    {{ suppress_small_cells(safe_ratio('n_cancelled', 'n_scheduled'), n='n_scheduled') }} as cancellation_rate,
    {{ suppress_small_cells(safe_ratio('n_cancelled_reused', 'n_cancelled'), n='n_cancelled') }} as slot_reuse_rate
from base
