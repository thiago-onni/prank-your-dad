-- Território de saúde: unidade (CNES) × equipe (INE) × microárea. Derivado dos eventos (cadastro, lacunas,
-- planos) e, no lakehouse, enriquecido com a réplica do core (reference.care_team / microarea).
with t as (
    select tenant_id, health_unit_cnes, team_ine, microarea from {{ ref('int_citizen_current') }}
    union distinct
    select tenant_id, health_unit_cnes, team_ine, microarea from {{ ref('int_care_gaps') }}
    union distinct
    select tenant_id, health_unit_cnes, team_ine, cast(null as varchar) from {{ ref('int_care_plans') }}
),

{% if use_core_replica() %}
teams as (
    select ct.tenant_id, ct.ine, ct.name as team_name, ct.team_type
    from {{ source('core', 'care_team') }} as ct
),
{% else %}
    teams as (
        select
            cast(null as varchar) as tenant_id,
            cast(null as varchar) as ine,
            cast(null as varchar) as team_name,
            cast(null as varchar) as team_type
        where 1 = 0
    ),
{% endif %}

dedup as (
    select distinct tenant_id, health_unit_cnes, team_ine, microarea
    from t
    where tenant_id is not null
)

select
    {{ territory_key('d.tenant_id', 'd.health_unit_cnes', 'd.team_ine', 'd.microarea') }} as territory_key,
    d.tenant_id,
    {{ health_unit_key('d.tenant_id', 'd.health_unit_cnes') }} as health_unit_key,
    d.health_unit_cnes,
    d.team_ine,
    teams.team_name,
    teams.team_type,
    d.microarea,
    case
        when d.microarea is not null then 'microarea'
        when d.team_ine is not null then 'equipe'
        when d.health_unit_cnes is not null then 'unidade'
        else 'municipio'
    end as territory_level
from dedup as d
left join teams
    on d.tenant_id = teams.tenant_id
        and d.team_ine = teams.ine
