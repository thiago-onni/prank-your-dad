-- Estado corrente por lacuna de cuidado (detected → resolved).
select
    tenant_id,
    care_gap_id,
    {{ latest('municipal_citizen_id') }} as municipal_citizen_id,
    {{ latest('care_plan_id') }} as care_plan_id,
    {{ latest('care_line') }} as care_line,
    {{ latest('gap_kind') }} as gap_kind,
    {{ latest('protocol_version') }} as protocol_version,
    {{ latest('health_unit_cnes') }} as health_unit_cnes,
    {{ latest('team_ine') }} as team_ine,
    {{ latest('microarea') }} as microarea,
    {{ latest('expected_by') }} as expected_by,
    coalesce(min(detected_at), min(case when event_action = 'detected' then occurred_at end)) as detected_at,
    max(case when event_action = 'resolved' then coalesce(resolved_at, occurred_at) end) as resolved_at,
    {{ latest('resolution') }} as resolution,
    {{ latest('task_id') }} as task_id
from {{ ref('stg_caregap__caregap') }}
where care_gap_id is not null
group by tenant_id, care_gap_id
