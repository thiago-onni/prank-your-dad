-- Planos de cuidado (sus.careplan.*).
with env as (
    {{ stg_envelope('events_careplan', 'sus.careplan') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'care_plan_id') }} as care_plan_id,
    {{ json_str('data', 'care_line') }} as care_line,
    {{ json_str('data', 'status') }} as status,
    {{ json_str('data', 'protocol_id') }} as protocol_id,
    {{ json_str('data', 'protocol_version') }} as protocol_version,
    {{ json_str('data', 'health_unit_cnes') }} as health_unit_cnes,
    {{ json_str('data', 'team_ine') }} as team_ine,
    {{ json_str('data', 'origin.kind') }} as origin_kind,
    {{ json_int('data', 'items_total') }} as items_total,
    {{ json_int('data', 'items_due') }} as items_due,
    {{ json_int('data', 'items_overdue') }} as items_overdue,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
