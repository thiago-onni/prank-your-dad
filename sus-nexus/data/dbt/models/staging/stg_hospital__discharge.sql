-- Altas e contrarreferências (sus.hospital.discharge.*). Gatilho do fluxo pós-alta.
with env as (
    {{ stg_envelope('events_hospital', 'sus.hospital.discharge') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'hospital_episode_id') }} as hospital_episode_id,
    {{ json_str('data', 'hospital_cnes') }} as hospital_cnes,
    {{ iso_to_local(json_str('data', 'discharged_at')) }} as discharged_at,
    {{ iso_to_local(json_str('data', 'admitted_at')) }} as admitted_at,
    {{ json_int('data', 'length_of_stay_days') }} as length_of_stay_days,
    {{ json_str('data', 'disposition') }} as disposition,
    {{ json_str('data', 'principal_diagnosis.code') }} as principal_diagnosis_cid10,
    {{ json_bool('data', 'followup_plan_present') }} as followup_plan_present,
    {{ json_int('data', 'followup_due_days') }} as followup_due_days,
    {{ json_str('data', 'reference_health_unit_cnes') }} as reference_health_unit_cnes,
    {{ json_str('data', 'reference_team_ine') }} as reference_team_ine,
    {{ json_str('data', 'risk_level') }} as risk_level,
    {{ json_bool('data', 'counter_referral_document_present') }} as counter_referral_document_present,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
