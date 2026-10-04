-- Agendamentos (sus.schedule.appointment.*) — schema compartilhado contracts/events/schedule/appointment.v1.
with env as (
    {{ stg_envelope('events_schedule', 'sus.schedule.appointment') }}
)

select
    event_id,
    event_type,
    event_action,
    'schedule' as agenda_channel,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'appointment_id') }} as appointment_id,
    {{ json_str('data', 'status') }} as status,
    {{ json_str('data', 'previous_status') }} as previous_status,
    {{ json_str('data', 'kind') }} as appointment_kind,
    {{ json_str('data', 'service_code') }} as service_code,
    {{ json_str('data', 'code_system') }} as code_system,
    coalesce({{ json_str('data', 'health_unit_cnes') }}, source_cnes) as health_unit_cnes,
    {{ json_str('data', 'professional_id') }} as professional_id,
    {{ iso_to_local(json_str('data', 'scheduled_start')) }} as scheduled_start,
    {{ iso_to_local(json_str('data', 'scheduled_end')) }} as scheduled_end,
    {{ json_str('data', 'regulation_request_id') }} as regulation_request_id,
    {{ json_str('data', 'exam_order_id') }} as exam_order_id,
    {{ json_str('data', 'care_line') }} as care_line,
    {{ json_str('data', 'cancellation_reason') }} as cancellation_reason,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
