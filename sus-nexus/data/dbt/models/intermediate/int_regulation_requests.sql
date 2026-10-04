-- Estado corrente por solicitação regulatória: atributos do pedido (request.*) + linha do tempo de status
-- (status.changed) com marcos de análise, devolução, autorização, agendamento e realização.
with req as (
    select
        tenant_id,
        regulation_request_id,
        {{ latest('municipal_citizen_id') }} as municipal_citizen_id,
        {{ latest('request_kind') }} as request_kind,
        {{ latest('priority') }} as request_priority,
        {{ latest('requested_service_code') }} as requested_service_code,
        {{ latest('specialty') }} as specialty,
        min(requested_at) as requested_at,
        {{ latest('requesting_cnes') }} as requesting_cnes,
        {{ latest('provider_cnes') }} as request_provider_cnes,
        {{ latest('sla_due_at') }} as sla_due_at,
        {{ latest('justification_present') }} as justification_present,
        {{ latest('attached_documents_count') }} as attached_documents_count,
        sum(case when event_action = 'returned' then 1 else 0 end) as returned_events,
        max(case when event_action = 'cancelled' then occurred_at end) as request_cancelled_at,
        min(occurred_at) as first_event_at,
        max(occurred_at) as last_request_event_at,
        {{ latest('status') }} as request_status
    from {{ ref('stg_regulation__request') }}
    where regulation_request_id is not null
    group by tenant_id, regulation_request_id
),

st as (
    select
        tenant_id,
        regulation_request_id,
        {{ latest('status', 'status_changed_at') }} as last_status,
        max(status_changed_at) as last_status_at,
        {{ latest('priority', 'status_changed_at') }} as status_priority,
        {{ latest('provider_cnes', 'status_changed_at') }} as provider_cnes,
        min(case when status = 'under_review' then status_changed_at end) as first_review_at,
        min(case when status = 'authorized' then status_changed_at end) as authorized_at,
        min(case when status = 'scheduled' then status_changed_at end) as scheduled_at,
        {{ latest('scheduled_at', 'status_changed_at') }} as scheduled_for,
        min(case when status = 'performed' then status_changed_at end) as performed_at,
        min(case when status = 'denied' then status_changed_at end) as denied_at,
        min(case when status = 'cancelled' then status_changed_at end) as cancelled_at,
        min(case when status = 'no_show' then status_changed_at end) as no_show_at,
        min(case when status = 'expired' then status_changed_at end) as expired_at,
        sum(case when status = 'returned' or return_to_origin then 1 else 0 end) as returned_status_events,
        max(case when sla_breached then 1 else 0 end) = 1 as sla_breached_flag
    from {{ ref('stg_regulation__status') }}
    where regulation_request_id is not null
    group by tenant_id, regulation_request_id
)

select
    req.tenant_id,
    req.regulation_request_id,
    req.municipal_citizen_id,
    req.request_kind,
    coalesce(st.status_priority, req.request_priority) as priority,
    req.requested_service_code,
    req.specialty,
    coalesce(req.requested_at, req.first_event_at) as requested_at,
    req.requesting_cnes,
    coalesce(st.provider_cnes, req.request_provider_cnes) as provider_cnes,
    req.sla_due_at,
    req.justification_present,
    req.attached_documents_count,
    case
        when st.last_status_at is not null and st.last_status_at >= req.last_request_event_at then st.last_status
        else req.request_status
    end as status,
    greatest(req.returned_events, coalesce(st.returned_status_events, 0)) as returned_count,
    st.first_review_at,
    st.authorized_at,
    st.scheduled_at,
    st.scheduled_for,
    st.performed_at,
    st.denied_at,
    coalesce(st.cancelled_at, req.request_cancelled_at) as cancelled_at,
    st.no_show_at,
    st.expired_at,
    coalesce(st.sla_breached_flag, false) as sla_breached_flag
from req
left join st
    on req.tenant_id = st.tenant_id
        and req.regulation_request_id = st.regulation_request_id
