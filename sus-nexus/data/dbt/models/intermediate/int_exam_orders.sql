-- Estado corrente por pedido de exame + marcos do ciclo (pedido → autorização → agendamento → realização →
-- laudo/resultado). Resultado vem de sus.exam.result.* (metadados; laudo por data_ref).
with ord as (
    select
        tenant_id,
        exam_order_id,
        {{ latest('municipal_citizen_id') }} as municipal_citizen_id,
        {{ latest('status') }} as status,
        {{ latest('exam_code') }} as exam_code,
        {{ latest('exam_category') }} as exam_category,
        {{ latest('priority') }} as priority,
        min(requested_at) as requested_at,
        {{ latest('requesting_cnes') }} as requesting_cnes,
        {{ latest('performer_cnes') }} as performer_cnes,
        {{ latest('regulation_request_id') }} as regulation_request_id,
        {{ latest('scheduled_at') }} as scheduled_for,
        {{ latest('care_line') }} as care_line,
        min(case when status = 'authorized' then occurred_at end) as authorized_at,
        min(case when status = 'scheduled' then occurred_at end) as scheduled_at,
        min(case when status in ('collected', 'performed') then occurred_at end) as performed_at,
        min(case when status = 'reported' then occurred_at end) as reported_status_at,
        min(case when status = 'cancelled' then occurred_at end) as cancelled_at,
        min(case when status = 'not_performed' then occurred_at end) as not_performed_at,
        min(occurred_at) as first_event_at
    from {{ ref('stg_exam__order') }}
    where exam_order_id is not null
    group by tenant_id, exam_order_id
),

res as (
    select
        tenant_id,
        exam_order_id,
        min(case when event_action = 'available' then coalesce(reported_at, occurred_at) end) as result_available_at,
        max(case when is_critical then 1 else 0 end) = 1 as is_critical,
        count(distinct exam_result_id) as result_count
    from {{ ref('stg_exam__result') }}
    where exam_order_id is not null
    group by tenant_id, exam_order_id
)

select
    ord.tenant_id,
    ord.exam_order_id,
    ord.municipal_citizen_id,
    ord.status,
    ord.exam_code,
    ord.exam_category,
    ord.priority,
    coalesce(ord.requested_at, ord.first_event_at) as requested_at,
    ord.requesting_cnes,
    ord.performer_cnes,
    ord.regulation_request_id,
    ord.care_line,
    ord.authorized_at,
    ord.scheduled_at,
    ord.scheduled_for,
    ord.performed_at,
    coalesce(ord.reported_status_at, res.result_available_at) as reported_at,
    res.result_available_at,
    coalesce(res.is_critical, false) as is_critical,
    coalesce(res.result_count, 0) as result_count,
    ord.cancelled_at,
    ord.not_performed_at
from ord
left join res
    on ord.tenant_id = res.tenant_id
        and ord.exam_order_id = res.exam_order_id
