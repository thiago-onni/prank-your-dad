-- Solicitações regulatórias (PLANO 4.9): tempo de espera, SLA, devoluções, prioridade e realização.
-- Grão: 1 linha por solicitação. Pseudonimizado.
with r as (
    select * from {{ ref('int_regulation_requests') }}
),

calc as (
    select
        r.*,
        r.status in ('requested', 'pending_documents', 'returned', 'under_review', 'authorized', 'scheduled') as is_open,
        coalesce(r.scheduled_at, r.denied_at, r.cancelled_at, r.expired_at) as decision_at,
        {{ now_local() }} as as_of
    from r
)

select
    regulation_request_id,
    tenant_id,
    {{ citizen_key('municipal_citizen_id') }} as citizen_key,
    {{ health_unit_key('tenant_id', 'requesting_cnes') }} as requesting_unit_key,
    {{ health_unit_key('tenant_id', 'provider_cnes') }} as provider_unit_key,
    requesting_cnes,
    provider_cnes,
    request_kind,
    priority,
    specialty,
    requested_service_code,
    status,
    is_open,
    {{ to_date('requested_at') }} as requested_date,
    requested_at,
    sla_due_at,
    first_review_at,
    authorized_at,
    scheduled_at,
    scheduled_for,
    performed_at,
    denied_at,
    cancelled_at,
    {{ days_between('requested_at', 'scheduled_at') }} as wait_days_to_schedule,
    {{ days_between('requested_at', 'performed_at') }} as wait_days_to_performed,
    {{ days_between('requested_at', 'first_review_at') }} as days_to_first_review,
    case when is_open and scheduled_at is null then {{ days_between('requested_at', 'as_of') }} end as days_waiting_open,
    case
        when sla_due_at is null then null
        when scheduled_at is not null then scheduled_at <= sla_due_at and not sla_breached_flag
        when decision_at is not null then decision_at <= sla_due_at
        when as_of > sla_due_at then false
    end as is_sla_met,
    sla_breached_flag
    or (sla_due_at is not null and coalesce(scheduled_at, decision_at, as_of) > sla_due_at) as is_sla_breached,
    returned_count,
    returned_count > 0 as was_returned,
    performed_at is not null as is_performed,
    denied_at is not null as is_denied,
    cancelled_at is not null as is_cancelled,
    no_show_at is not null as is_no_show,
    justification_present,
    attached_documents_count
from calc
