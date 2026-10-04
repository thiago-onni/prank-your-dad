-- Estado corrente por agendamento (APS + agenda da rede): último status + marcos (criação, confirmação,
-- cancelamento, comparecimento, falta, remarcações).
with ev as (
    select * from {{ ref('stg_aps__appointment') }}
    union all
    select * from {{ ref('stg_schedule__appointment') }}
),

agg as (
    select
        tenant_id,
        appointment_id,
        {{ latest('agenda_channel') }} as agenda_channel,
        {{ latest('municipal_citizen_id') }} as municipal_citizen_id,
        {{ latest('status') }} as status,
        {{ latest('appointment_kind') }} as appointment_kind,
        {{ latest('service_code') }} as service_code,
        {{ latest('health_unit_cnes') }} as health_unit_cnes,
        {{ latest('professional_id') }} as professional_id,
        {{ latest('scheduled_start') }} as scheduled_start,
        {{ latest('scheduled_end') }} as scheduled_end,
        {{ latest('regulation_request_id') }} as regulation_request_id,
        {{ latest('exam_order_id') }} as exam_order_id,
        {{ latest('care_line') }} as care_line,
        {{ latest('cancellation_reason') }} as cancellation_reason,
        coalesce(min(case when event_action = 'created' then occurred_at end), min(occurred_at)) as created_at,
        min(case when event_action = 'confirmed' then occurred_at end) as confirmed_at,
        max(case when event_action = 'cancelled' then occurred_at end) as cancelled_at,
        max(case when event_action = 'attended' then occurred_at end) as attended_at,
        max(case when event_action = 'no_show' then occurred_at end) as no_show_at,
        sum(case when event_action = 'rescheduled' then 1 else 0 end) as reschedule_count,
        max(case when event_action = 'duplicate_detected' then 1 else 0 end) = 1 as is_duplicate,
        max(occurred_at) as last_event_at,
        count(*) as event_count
    from ev
    where appointment_id is not null
    group by tenant_id, appointment_id
)

select
    agg.*,
    case
        when status in ('fulfilled', 'arrived') then 'attended'
        when status = 'noshow' then 'no_show'
        when status = 'cancelled' then 'cancelled'
        else 'pending'
    end as outcome
from agg
