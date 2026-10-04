-- Estado corrente do cidadão (último evento sus.identity.citizen.* por id): estado cadastral e território.
-- Uso interno (silver): alimenta a atribuição territorial dos fatos. Não exposto a BI.
with ev as (
    select * from {{ ref('stg_identity__citizen') }}
)

select
    tenant_id,
    municipal_citizen_id,
    {{ latest('registration_state') }} as registration_state,
    {{ latest('health_unit_cnes') }} as health_unit_cnes,
    {{ latest('team_ine') }} as team_ine,
    {{ latest('microarea') }} as microarea,
    min(occurred_at) as first_seen_at,
    max(occurred_at) as last_event_at
from ev
where municipal_citizen_id is not null
group by tenant_id, municipal_citizen_id
