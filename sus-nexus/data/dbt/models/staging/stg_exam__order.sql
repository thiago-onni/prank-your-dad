-- Pedidos de exame (sus.exam.order.*).
with env as (
    {{ stg_envelope('events_exam', 'sus.exam.order') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'exam_order_id') }} as exam_order_id,
    {{ json_str('data', 'status') }} as status,
    {{ json_str('data', 'previous_status') }} as previous_status,
    {{ json_str('data', 'exam_code') }} as exam_code,
    {{ json_str('data', 'code_system') }} as code_system,
    {{ json_str('data', 'category') }} as exam_category,
    {{ json_str('data', 'priority') }} as priority,
    {{ iso_to_local(json_str('data', 'requested_at')) }} as requested_at,
    {{ json_str('data', 'requesting_cnes') }} as requesting_cnes,
    {{ json_str('data', 'performer_cnes') }} as performer_cnes,
    {{ json_str('data', 'regulation_request_id') }} as regulation_request_id,
    {{ iso_to_local(json_str('data', 'scheduled_at')) }} as scheduled_at,
    {{ json_str('data', 'care_line') }} as care_line,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
