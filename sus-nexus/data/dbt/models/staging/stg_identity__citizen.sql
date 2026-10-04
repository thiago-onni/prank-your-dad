-- Eventos de cidadão (sus.identity.citizen.*): estado cadastral e território. Sem CPF/CNS/nome.
with env as (
    {{ stg_envelope('events_identity', 'sus.identity.citizen') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    coalesce({{ json_str('data', 'municipal_citizen_id') }}, municipal_citizen_id) as municipal_citizen_id,
    {{ json_str('data', 'registration_state') }} as registration_state,
    {{ json_str('data', 'match.method') }} as match_method,
    {{ json_str('data', 'match.classification') }} as match_classification,
    {{ json_str('data', 'territory.health_unit_cnes') }} as health_unit_cnes,
    {{ json_str('data', 'territory.team_ine') }} as team_ine,
    {{ json_str('data', 'territory.microarea') }} as microarea,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
