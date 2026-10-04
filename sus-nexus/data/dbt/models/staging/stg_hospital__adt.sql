-- Movimentação hospitalar (sus.hospital.adt.*). Diagnóstico só como código CID-10.
with env as (
    {{ stg_envelope('events_hospital', 'sus.hospital.adt') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'hospital_episode_id') }} as hospital_episode_id,
    {{ json_str('data', 'hospital_cnes') }} as hospital_cnes,
    {{ json_str('data', 'episode_class') }} as episode_class,
    {{ json_str('data', 'status') }} as status,
    coalesce({{ iso_to_local(json_str('data', 'occurred_at')) }}, occurred_at) as movement_at,
    {{ iso_to_local(json_str('data', 'admitted_at')) }} as admitted_at,
    {{ json_str('data', 'ward') }} as ward,
    {{ json_str('data', 'principal_diagnosis.code') }} as principal_diagnosis_cid10,
    {{ json_str('data', 'admission_source') }} as admission_source,
    {{ json_str('data', 'regulation_request_id') }} as regulation_request_id,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
