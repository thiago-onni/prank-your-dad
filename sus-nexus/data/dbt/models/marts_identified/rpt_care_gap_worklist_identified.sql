-- Lista operacional IDENTIFICADA de lacunas abertas (busca ativa). Schema marts_identified: acesso só para
-- grupos com RBAC/ABAC (rules.json do Trino) e finalidade care_coordination. Contém municipal_citizen_id
-- (identificador interno, sem nome/CNS/CPF); a reidentificação nominal ocorre no core, com access_log.
select
    g.tenant_id,
    g.care_gap_id,
    g.municipal_citizen_id,
    {{ citizen_key('g.municipal_citizen_id') }} as citizen_key,
    g.care_line,
    g.gap_kind,
    g.health_unit_cnes,
    g.team_ine,
    g.microarea,
    g.detected_at,
    g.expected_by,
    {{ days_between('g.detected_at', now_local()) }} as days_open,
    g.task_id
from {{ ref('int_care_gaps') }} as g
where g.resolved_at is null
