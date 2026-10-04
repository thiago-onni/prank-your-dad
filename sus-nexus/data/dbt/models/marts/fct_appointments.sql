-- Agendamentos (AGE-010): comparecimento, falta (no-show), cancelamento e reaproveitamento de vaga.
-- Grão: 1 linha por agendamento. Pseudonimizado (citizen_key).
with a as (
    select * from {{ ref('int_appointments') }}
    where not is_duplicate
),

reused as (
    -- vaga reaproveitada: outro agendamento para o mesmo estabelecimento/serviço/horário criado após o cancelamento
    select distinct c.tenant_id, c.appointment_id
    from a as c
    inner join a as n
        on c.tenant_id = n.tenant_id
            and c.health_unit_cnes = n.health_unit_cnes
            and c.scheduled_start = n.scheduled_start
            and coalesce(c.service_code, '-') = coalesce(n.service_code, '-')
            and c.appointment_id <> n.appointment_id
            and n.created_at >= c.cancelled_at
            and n.outcome <> 'cancelled'
    where c.outcome = 'cancelled'
),

cit as (
    select * from {{ ref('int_citizen_current') }}
)

select
    a.appointment_id,
    a.tenant_id,
    {{ citizen_key('a.municipal_citizen_id') }} as citizen_key,
    {{ health_unit_key('a.tenant_id', 'a.health_unit_cnes') }} as health_unit_key,
    a.health_unit_cnes,
    {{ territory_key('a.tenant_id', 'cit.health_unit_cnes', 'cit.team_ine', 'cit.microarea') }} as citizen_territory_key,
    a.agenda_channel,
    a.appointment_kind,
    a.service_code,
    a.care_line,
    a.status,
    a.outcome,
    {{ to_date('a.scheduled_start') }} as scheduled_date,
    a.scheduled_start,
    a.created_at,
    a.cancelled_at,
    a.attended_at,
    a.no_show_at,
    {{ days_between('a.created_at', 'a.scheduled_start') }} as lead_time_days,
    case when a.outcome = 'cancelled' then {{ hours_between('a.cancelled_at', 'a.scheduled_start') }} end as cancelled_hours_before,
    a.outcome = 'attended' as is_attended,
    a.outcome = 'no_show' as is_no_show,
    a.outcome = 'cancelled' as is_cancelled,
    a.outcome = 'pending' as is_pending,
    a.outcome in ('attended', 'no_show') as is_realized_slot,
    r.appointment_id is not null as is_slot_reused,
    a.reschedule_count,
    a.confirmed_at is not null as was_confirmed,
    a.regulation_request_id,
    a.exam_order_id
from a
left join reused as r
    on a.tenant_id = r.tenant_id
        and a.appointment_id = r.appointment_id
left join cit
    on a.tenant_id = cit.tenant_id
        and a.municipal_citizen_id = cit.municipal_citizen_id
