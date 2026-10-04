-- Resultados/laudos disponíveis (sus.exam.result.*). Só metadados; o laudo fica por data_ref.
with env as (
    {{ stg_envelope('events_exam', 'sus.exam.result') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'exam_order_id') }} as exam_order_id,
    {{ json_str('data', 'exam_result_id') }} as exam_result_id,
    {{ json_str('data', 'result_status') }} as result_status,
    coalesce({{ json_bool('data', 'critical') }}, false) as is_critical,
    {{ iso_to_local(json_str('data', 'reported_at')) }} as reported_at,
    {{ json_str('data', 'performer_cnes') }} as performer_cnes,
    {{ json_str('data', 'requesting_cnes') }} as requesting_cnes,
    {{ json_str('data', 'care_line') }} as care_line,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
