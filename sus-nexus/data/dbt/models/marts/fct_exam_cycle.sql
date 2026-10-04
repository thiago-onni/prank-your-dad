-- Ciclo do exame (EXA-010): pedido → agendamento → realização → laudo → retorno ao profissional solicitante.
-- Grão: 1 linha por pedido de exame. Pseudonimizado.
with o as (
    select * from {{ ref('int_exam_orders') }}
),

ret as (
    -- retorno: consulta realizada do mesmo cidadão vinculada ao pedido, ou consulta de retorno após o laudo
    select
        o.tenant_id,
        o.exam_order_id,
        min(a.attended_at) as return_attended_at
    from o
    inner join {{ ref('int_appointments') }} as a
        on o.tenant_id = a.tenant_id
            and o.municipal_citizen_id = a.municipal_citizen_id
            and a.outcome = 'attended'
            and o.reported_at is not null
            and a.attended_at >= o.reported_at
            and a.attended_at <= o.reported_at + interval '{{ var("exam_return_window_days") }}' day
            and (a.exam_order_id = o.exam_order_id or a.appointment_kind = 'return')
    group by o.tenant_id, o.exam_order_id
)

select
    o.exam_order_id,
    o.tenant_id,
    {{ citizen_key('o.municipal_citizen_id') }} as citizen_key,
    {{ health_unit_key('o.tenant_id', 'o.requesting_cnes') }} as requesting_unit_key,
    {{ health_unit_key('o.tenant_id', 'o.performer_cnes') }} as performer_unit_key,
    o.requesting_cnes,
    o.performer_cnes,
    o.exam_code,
    o.exam_category,
    o.priority,
    o.care_line,
    o.status,
    {{ to_date('o.requested_at') }} as requested_date,
    o.requested_at,
    o.authorized_at,
    o.scheduled_at,
    o.performed_at,
    o.reported_at,
    ret.return_attended_at,
    case
        when ret.return_attended_at is not null then 'retorno'
        when o.reported_at is not null then 'laudo'
        when o.performed_at is not null then 'realizado'
        when o.scheduled_at is not null then 'agendado'
        when o.authorized_at is not null then 'autorizado'
        when o.cancelled_at is not null or o.not_performed_at is not null then 'encerrado_sem_realizacao'
        else 'solicitado'
    end as cycle_stage,
    {{ days_between('o.requested_at', 'o.scheduled_at') }} as days_request_to_schedule,
    {{ days_between('o.requested_at', 'o.performed_at') }} as days_request_to_performed,
    {{ days_between('o.performed_at', 'o.reported_at') }} as days_performed_to_report,
    {{ days_between('o.requested_at', 'o.reported_at') }} as days_request_to_result,
    {{ days_between('o.reported_at', 'ret.return_attended_at') }} as days_result_to_return,
    o.scheduled_at is not null as is_scheduled,
    o.performed_at is not null as is_performed,
    o.reported_at is not null as is_reported,
    ret.return_attended_at is not null as has_return,
    ret.return_attended_at is not null as is_cycle_complete,
    o.reported_at is not null and ret.return_attended_at is null as is_result_without_return,
    o.is_critical,
    o.cancelled_at is not null or o.not_performed_at is not null as is_cancelled
from o
left join ret
    on o.tenant_id = ret.tenant_id
        and o.exam_order_id = ret.exam_order_id
