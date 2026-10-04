-- Mudanças de status regulatório (sus.regulation.status.changed) registradas a partir do sistema oficial.
with env as (
    {{ stg_envelope('events_regulation', 'sus.regulation.status') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'regulation_request_id') }} as regulation_request_id,
    {{ json_str('data', 'status') }} as status,
    {{ json_str('data', 'previous_status') }} as previous_status,
    {{ json_str('data', 'priority') }} as priority,
    {{ json_str('data', 'actor_kind') }} as actor_kind,
    {{ json_str('data', 'provider_cnes') }} as provider_cnes,
    {{ iso_to_local(json_str('data', 'scheduled_at')) }} as scheduled_at,
    {{ json_bool('data', 'return_to_origin') }} as return_to_origin,
    {{ json_bool('data', 'sla_breached') }} as sla_breached,
    coalesce({{ iso_to_local(json_str('data', 'occurred_at')) }}, occurred_at) as status_changed_at,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
