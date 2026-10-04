-- Lacunas de cuidado (sus.caregap.*) detectadas por protocolo versionado.
with env as (
    {{ stg_envelope('events_caregap', 'sus.caregap') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'care_gap_id') }} as care_gap_id,
    {{ json_str('data', 'care_plan_id') }} as care_plan_id,
    {{ json_str('data', 'care_line') }} as care_line,
    {{ json_str('data', 'gap_kind') }} as gap_kind,
    {{ iso_to_local(json_str('data', 'expected_by')) }} as expected_by,
    {{ json_int('data', 'days_overdue') }} as days_overdue,
    {{ json_str('data', 'protocol_version') }} as protocol_version,
    {{ json_str('data', 'health_unit_cnes') }} as health_unit_cnes,
    {{ json_str('data', 'team_ine') }} as team_ine,
    {{ json_str('data', 'microarea') }} as microarea,
    {{ iso_to_local(json_str('data', 'detected_at')) }} as detected_at,
    {{ iso_to_local(json_str('data', 'resolved_at')) }} as resolved_at,
    {{ json_str('data', 'resolution') }} as resolution,
    {{ json_str('data', 'task_id') }} as task_id,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
