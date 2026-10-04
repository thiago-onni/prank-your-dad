-- Estado corrente por plano de cuidado.
select
    tenant_id,
    care_plan_id,
    {{ latest('municipal_citizen_id') }} as municipal_citizen_id,
    {{ latest('care_line') }} as care_line,
    {{ latest('status') }} as status,
    {{ latest('protocol_id') }} as protocol_id,
    {{ latest('protocol_version') }} as protocol_version,
    {{ latest('health_unit_cnes') }} as health_unit_cnes,
    {{ latest('team_ine') }} as team_ine,
    {{ latest('origin_kind') }} as origin_kind,
    {{ latest('items_total') }} as items_total,
    {{ latest('items_overdue') }} as items_overdue,
    coalesce(min(case when event_action = 'created' then occurred_at end), min(occurred_at)) as created_at,
    max(case when event_action = 'closed' then occurred_at end) as closed_at
from {{ ref('stg_careplan__careplan') }}
where care_plan_id is not null
group by tenant_id, care_plan_id
