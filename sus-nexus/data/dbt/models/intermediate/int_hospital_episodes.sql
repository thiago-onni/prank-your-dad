-- Estado corrente por episódio hospitalar: ADT (admissão, transferências, alta/óbito) + resumo de alta.
with adt as (
    select
        tenant_id,
        hospital_episode_id,
        {{ latest('municipal_citizen_id') }} as municipal_citizen_id,
        {{ latest('hospital_cnes') }} as hospital_cnes,
        {{ latest('episode_class') }} as episode_class,
        {{ latest('status', 'movement_at') }} as status,
        coalesce(min(case when event_action = 'admitted' then movement_at end), min(admitted_at)) as admitted_at,
        max(case when event_action in ('discharged', 'deceased') then movement_at end) as adt_discharged_at,
        max(case when event_action = 'deceased' then 1 else 0 end) = 1 as is_deceased,
        sum(case when event_action = 'transferred' then 1 else 0 end) as transfers_count,
        {{ latest('admission_source') }} as admission_source,
        {{ latest('principal_diagnosis_cid10') }} as principal_diagnosis_cid10
    from {{ ref('stg_hospital__adt') }}
    where hospital_episode_id is not null
    group by tenant_id, hospital_episode_id
),

dis as (
    select
        tenant_id,
        hospital_episode_id,
        {{ latest('municipal_citizen_id') }} as municipal_citizen_id,
        {{ latest('hospital_cnes') }} as hospital_cnes,
        min(case when event_action = 'completed' then discharged_at end) as discharged_at,
        min(admitted_at) as admitted_at,
        {{ latest('disposition') }} as disposition,
        {{ latest('principal_diagnosis_cid10') }} as principal_diagnosis_cid10,
        {{ latest('followup_plan_present') }} as followup_plan_present,
        {{ latest('followup_due_days') }} as followup_due_days,
        {{ latest('reference_health_unit_cnes') }} as reference_health_unit_cnes,
        {{ latest('reference_team_ine') }} as reference_team_ine,
        {{ latest('risk_level') }} as risk_level,
        min(case when event_action = 'counter_referral_received' then occurred_at end) as counter_referral_received_at
    from {{ ref('stg_hospital__discharge') }}
    where hospital_episode_id is not null
    group by tenant_id, hospital_episode_id
),

ids as (
    select tenant_id, hospital_episode_id from adt
    union distinct
    select tenant_id, hospital_episode_id from dis
)

select
    ids.tenant_id,
    ids.hospital_episode_id,
    coalesce(adt.municipal_citizen_id, dis.municipal_citizen_id) as municipal_citizen_id,
    coalesce(adt.hospital_cnes, dis.hospital_cnes) as hospital_cnes,
    coalesce(adt.episode_class, 'inpatient') as episode_class,
    coalesce(adt.admitted_at, dis.admitted_at) as admitted_at,
    coalesce(dis.discharged_at, adt.adt_discharged_at) as discharged_at,
    coalesce(adt.is_deceased, false) or coalesce(dis.disposition = 'deceased', false) as is_deceased,
    case
        when coalesce(dis.discharged_at, adt.adt_discharged_at) is not null then 'discharged'
        else coalesce(adt.status, 'admitted')
    end as status,
    coalesce(adt.transfers_count, 0) as transfers_count,
    adt.admission_source,
    coalesce(dis.principal_diagnosis_cid10, adt.principal_diagnosis_cid10) as principal_diagnosis_cid10,
    dis.disposition,
    dis.followup_plan_present,
    dis.followup_due_days,
    dis.reference_health_unit_cnes,
    dis.reference_team_ine,
    dis.risk_level,
    dis.counter_referral_received_at
from ids
left join adt
    on ids.tenant_id = adt.tenant_id
        and ids.hospital_episode_id = adt.hospital_episode_id
left join dis
    on ids.tenant_id = dis.tenant_id
        and ids.hospital_episode_id = dis.hospital_episode_id
