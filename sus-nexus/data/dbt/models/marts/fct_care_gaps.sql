-- Lacunas de cuidado abertas/resolvidas por linha de cuidado e território. Grão: 1 linha por lacuna.
select
    g.care_gap_id,
    g.tenant_id,
    {{ citizen_key('g.municipal_citizen_id') }} as citizen_key,
    {{ health_unit_key('g.tenant_id', 'g.health_unit_cnes') }} as health_unit_key,
    {{ territory_key('g.tenant_id', 'g.health_unit_cnes', 'g.team_ine', 'g.microarea') }} as territory_key,
    g.health_unit_cnes,
    g.team_ine,
    g.microarea,
    g.care_line,
    g.gap_kind,
    g.protocol_version,
    {{ to_date('g.detected_at') }} as detected_date,
    g.detected_at,
    g.resolved_at,
    g.resolution,
    g.resolved_at is null as is_open,
    g.resolved_at is not null as is_resolved,
    {{ days_between('g.detected_at', 'coalesce(g.resolved_at, ' ~ now_local() ~ ')') }} as days_open,
    g.task_id is not null as has_task
from {{ ref('int_care_gaps') }} as g
