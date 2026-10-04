-- Estabelecimentos de saúde (CNES) por município. Fonte: réplica do core (reference.health_unit) quando
-- disponível + todo CNES observado nos eventos (garante integridade referencial dos fatos).
with observed as (
    select tenant_id, health_unit_cnes as cnes, 'aps_agenda' as seen_as from {{ ref('int_appointments') }}
    union all
    select tenant_id, requesting_cnes, 'regulation_requester' from {{ ref('int_regulation_requests') }}
    union all
    select tenant_id, provider_cnes, 'regulation_provider' from {{ ref('int_regulation_requests') }}
    union all
    select tenant_id, requesting_cnes, 'exam_requester' from {{ ref('int_exam_orders') }}
    union all
    select tenant_id, performer_cnes, 'exam_performer' from {{ ref('int_exam_orders') }}
    union all
    select tenant_id, hospital_cnes, 'hospital' from {{ ref('int_hospital_episodes') }}
    union all
    select tenant_id, reference_health_unit_cnes, 'aps_reference' from {{ ref('int_hospital_episodes') }}
    union all
    select tenant_id, health_unit_cnes, 'care' from {{ ref('int_care_gaps') }}
    union all
    select tenant_id, health_unit_cnes, 'care' from {{ ref('int_care_plans') }}
    union all
    select tenant_id, health_unit_cnes, 'territory' from {{ ref('int_citizen_current') }}
    union all
    select tenant_id, health_unit_cnes, 'production' from {{ ref('int_production_records') }}
),

observed_units as (
    select
        tenant_id,
        cnes,
        max(case when seen_as = 'hospital' then 1 else 0 end) = 1 as seen_as_hospital,
        max(case when seen_as in ('territory', 'care', 'aps_reference') then 1 else 0 end) = 1 as seen_as_aps
    from observed
    where cnes is not null and tenant_id is not null
    group by tenant_id, cnes
),

{% if use_core_replica() %}
core_units as (
    select
        tenant_id,
        cnes,
        name as health_unit_name,
        kind_code,
        kind_description,
        active
    from {{ source('core', 'health_unit') }}
),
{% else %}
    core_units as (
        select
            cast(null as varchar) as tenant_id,
            cast(null as varchar) as cnes,
            cast(null as varchar) as health_unit_name,
            cast(null as varchar) as kind_code,
            cast(null as varchar) as kind_description,
            cast(null as boolean) as active
        where 1 = 0
    ),
{% endif %}

all_units as (
    select tenant_id, cnes from core_units
    union distinct
    select tenant_id, cnes from observed_units
)

select
    {{ health_unit_key('u.tenant_id', 'u.cnes') }} as health_unit_key,
    u.tenant_id,
    u.cnes as health_unit_cnes,
    coalesce(c.health_unit_name, 'Estabelecimento CNES ' || u.cnes) as health_unit_name,
    c.kind_code,
    c.kind_description,
    case
        when coalesce(o.seen_as_hospital, false) then 'hospital'
        when coalesce(o.seen_as_aps, false) then 'aps'
        else 'outro'
    end as unit_role,
    coalesce(c.active, true) as is_active,
    c.cnes is not null as is_registered_in_core
from all_units as u
left join core_units as c
    on u.tenant_id = c.tenant_id
        and u.cnes = c.cnes
left join observed_units as o
    on u.tenant_id = o.tenant_id
        and u.cnes = o.cnes
