-- Solicitações regulatórias (sus.regulation.request.*). Sem justificativa clínica em texto livre.
with env as (
    {{ stg_envelope('events_regulation', 'sus.regulation.request') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'regulation_request_id') }} as regulation_request_id,
    {{ json_str('data', 'kind') }} as request_kind,
    {{ json_str('data', 'status') }} as status,
    {{ json_str('data', 'priority') }} as priority,
    {{ json_str('data', 'requested_service_code') }} as requested_service_code,
    {{ json_str('data', 'specialty') }} as specialty,
    {{ iso_to_local(json_str('data', 'requested_at')) }} as requested_at,
    {{ json_str('data', 'requesting_cnes') }} as requesting_cnes,
    {{ json_str('data', 'provider_cnes') }} as provider_cnes,
    {{ json_bool('data', 'justification_present') }} as justification_present,
    {{ json_int('data', 'attached_documents_count') }} as attached_documents_count,
    {{ iso_to_local(json_str('data', 'sla_due_at')) }} as sla_due_at,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
