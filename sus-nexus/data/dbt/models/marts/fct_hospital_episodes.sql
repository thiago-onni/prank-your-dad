-- Episódios hospitalares: internações, permanência (LOS), reinternação em 30 dias e tempo até o primeiro
-- contato da APS após a alta (consulta realizada ou tarefa pós-alta concluída). Grão: 1 linha por episódio.
with e as (
    select * from {{ ref('int_hospital_episodes') }}
),

seq as (
    select
        e.*,
        lead(admitted_at) over (
            partition by tenant_id, municipal_citizen_id, episode_class = 'inpatient'
            order by admitted_at
        ) as next_admitted_at,
        lag(discharged_at) over (
            partition by tenant_id, municipal_citizen_id, episode_class = 'inpatient'
            order by admitted_at
        ) as previous_discharged_at
    from e
),

contacts as (
    select tenant_id, municipal_citizen_id, attended_at as contact_at, 'consulta' as contact_kind
    from {{ ref('int_appointments') }}
    where outcome = 'attended'
    union all
    select tenant_id, municipal_citizen_id, completed_at, 'tarefa_pos_alta'
    from {{ ref('int_tasks') }}
    where task_type = 'post_discharge_followup' and completed_at is not null
),

first_contact as (
    select
        s.tenant_id,
        s.hospital_episode_id,
        min(c.contact_at) as first_contact_at
    from seq as s
    inner join contacts as c
        on s.tenant_id = c.tenant_id
            and s.municipal_citizen_id = c.municipal_citizen_id
            and s.discharged_at is not null
            and c.contact_at >= s.discharged_at
    group by s.tenant_id, s.hospital_episode_id
),

post_task as (
    select
        s.tenant_id,
        s.hospital_episode_id,
        min(t.created_at) as post_discharge_task_at
    from seq as s
    inner join {{ ref('int_tasks') }} as t
        on s.tenant_id = t.tenant_id
            and s.municipal_citizen_id = t.municipal_citizen_id
            and t.task_type = 'post_discharge_followup'
            and s.discharged_at is not null
            and t.created_at >= s.discharged_at
            and t.created_at <= s.discharged_at + interval '2' day
    group by s.tenant_id, s.hospital_episode_id
)

select
    s.hospital_episode_id,
    s.tenant_id,
    {{ citizen_key('s.municipal_citizen_id') }} as citizen_key,
    {{ health_unit_key('s.tenant_id', 's.hospital_cnes') }} as hospital_unit_key,
    {{ health_unit_key('s.tenant_id', 's.reference_health_unit_cnes') }} as reference_unit_key,
    s.hospital_cnes,
    s.reference_health_unit_cnes,
    s.episode_class,
    s.status,
    s.admission_source,
    s.principal_diagnosis_cid10,
    s.disposition,
    s.risk_level,
    {{ to_date('s.admitted_at') }} as admitted_date,
    {{ to_date('s.discharged_at') }} as discharged_date,
    s.admitted_at,
    s.discharged_at,
    {{ days_between('s.admitted_at', 's.discharged_at') }} as length_of_stay_days,
    case when s.discharged_at is null then {{ days_between('s.admitted_at', now_local()) }} end as days_inpatient_open,
    s.transfers_count,
    s.is_deceased,
    s.discharged_at is not null and not s.is_deceased as is_discharged_alive,
    s.episode_class = 'inpatient'
    and s.discharged_at is not null
    and s.next_admitted_at is not null
    and s.next_admitted_at <= s.discharged_at + interval '{{ var("readmission_window_days") }}' day as is_readmitted_30d,
    s.episode_class = 'inpatient'
    and s.previous_discharged_at is not null
    and s.admitted_at <= s.previous_discharged_at + interval '{{ var("readmission_window_days") }}' day as is_readmission,
    fc.first_contact_at as post_discharge_first_contact_at,
    {{ hours_between('s.discharged_at', 'fc.first_contact_at') }} as hours_to_first_contact,
    fc.first_contact_at is not null
    and fc.first_contact_at <= s.discharged_at + interval '{{ var("post_discharge_contact_window_days") }}' day as is_contacted_within_7d,
    pt.post_discharge_task_at,
    {{ hours_between('s.discharged_at', 'pt.post_discharge_task_at') }} * 60 as minutes_discharge_to_task,
    s.followup_plan_present,
    s.counter_referral_received_at is not null as has_counter_referral
from seq as s
left join first_contact as fc
    on s.tenant_id = fc.tenant_id
        and s.hospital_episode_id = fc.hospital_episode_id
left join post_task as pt
    on s.tenant_id = pt.tenant_id
        and s.hospital_episode_id = pt.hospital_episode_id
