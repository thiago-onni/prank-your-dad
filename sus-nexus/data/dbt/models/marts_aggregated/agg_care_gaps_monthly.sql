-- Lacunas de cuidado por mês de detecção × território (unidade/equipe) × linha de cuidado.
with base as (
    select
        tenant_id,
        {{ month_of('detected_date') }} as month_start,
        health_unit_key,
        health_unit_cnes,
        team_ine,
        care_line,
        count(*) as n_detected,
        {{ count_true('is_resolved') }} as n_resolved,
        {{ count_true('is_open') }} as n_open,
        {{ count_true('has_task') }} as n_with_task,
        {{ pctl('days_open', 0.5) }} as days_open_p50
    from {{ ref('fct_care_gaps') }}
    where detected_date is not null
    group by tenant_id, {{ month_of('detected_date') }}, health_unit_key, health_unit_cnes, team_ine, care_line
)

select
    tenant_id,
    month_start,
    {{ competence_of('month_start') }} as competence,
    {{ territory_key('tenant_id', 'health_unit_cnes', 'team_ine', 'cast(null as varchar)') }} as territory_key,
    health_unit_key,
    health_unit_cnes,
    team_ine,
    care_line,
    {{ suppress_small_cells('n_detected') }} as n_detected,
    {{ suppress_small_cells('n_resolved') }} as n_resolved,
    {{ suppress_small_cells('n_open') }} as n_open,
    {{ suppress_small_cells('n_with_task') }} as n_with_task,
    {{ suppress_small_cells('days_open_p50', n='n_detected') }} as days_open_p50,
    {{ suppress_small_cells(safe_ratio('n_resolved', 'n_detected'), n='n_detected') }} as resolution_rate
from base
