-- Hospital por mês da alta × hospital × classe do episódio: altas, permanência, reinternação em 30 dias e
-- contato pós-alta pela APS.
with base as (
    select
        tenant_id,
        {{ month_of('discharged_date') }} as month_start,
        hospital_unit_key,
        hospital_cnes,
        episode_class,
        count(*) as n_discharges,
        {{ count_true('is_discharged_alive') }} as n_discharged_alive,
        {{ count_true('is_deceased') }} as n_deaths,
        {{ count_true('is_readmitted_30d') }} as n_readmitted_30d,
        {{ count_true('is_contacted_within_7d and is_discharged_alive') }} as n_contacted_7d,
        avg(length_of_stay_days) as los_avg_days,
        {{ pctl('length_of_stay_days', 0.5) }} as los_p50_days,
        {{ pctl('hours_to_first_contact', 0.5) }} as hours_to_first_contact_p50,
        {{ count_true('post_discharge_task_at is not null') }} as n_with_post_discharge_task,
        {{ pctl('minutes_discharge_to_task', 0.9) }} as minutes_discharge_to_task_p90
    from {{ ref('fct_hospital_episodes') }}
    where discharged_date is not null
    group by tenant_id, {{ month_of('discharged_date') }}, hospital_unit_key, hospital_cnes, episode_class
)

select
    tenant_id,
    month_start,
    {{ competence_of('month_start') }} as competence,
    hospital_unit_key,
    hospital_cnes,
    episode_class,
    {{ suppress_small_cells('n_discharges') }} as n_discharges,
    {{ suppress_small_cells('n_discharged_alive') }} as n_discharged_alive,
    {{ suppress_small_cells('n_deaths') }} as n_deaths,
    {{ suppress_small_cells('n_readmitted_30d') }} as n_readmitted_30d,
    {{ suppress_small_cells('n_contacted_7d') }} as n_contacted_7d,
    {{ suppress_small_cells('n_with_post_discharge_task') }} as n_with_post_discharge_task,
    {{ suppress_small_cells('los_avg_days', n='n_discharges') }} as los_avg_days,
    {{ suppress_small_cells('los_p50_days', n='n_discharges') }} as los_p50_days,
    {{ suppress_small_cells('hours_to_first_contact_p50', n='n_contacted_7d') }} as hours_to_first_contact_p50,
    {{ suppress_small_cells('minutes_discharge_to_task_p90', n='n_with_post_discharge_task') }} as minutes_discharge_to_task_p90,
    {{ suppress_small_cells(safe_ratio('n_readmitted_30d', 'n_discharged_alive'), n='n_discharged_alive') }} as readmission_30d_rate,
    {{ suppress_small_cells(safe_ratio('n_contacted_7d', 'n_discharged_alive'), n='n_discharged_alive') }} as post_discharge_contact_7d_rate
from base
