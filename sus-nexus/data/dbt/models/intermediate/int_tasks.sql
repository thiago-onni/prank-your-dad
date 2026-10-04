-- Estado corrente por tarefa: último status + marcos (criação, atribuição, conclusão, cancelamento) e sinais
-- de SLA/escalonamento.
with ev as (
    select * from {{ ref('stg_task__task') }}
)

select
    tenant_id,
    task_id,
    {{ latest('municipal_citizen_id') }} as municipal_citizen_id,
    {{ latest('task_type') }} as task_type,
    {{ latest('status') }} as status,
    {{ latest('priority') }} as priority,
    {{ latest('origin_kind') }} as origin_kind,
    {{ latest('origin_id') }} as origin_id,
    {{ latest('assignee_kind') }} as assignee_kind,
    {{ latest('due_at') }} as due_at,
    {{ latest('outcome') }} as outcome,
    coalesce(min(case when event_action = 'created' then occurred_at end), min(occurred_at)) as created_at,
    min(case when event_action = 'assigned' then occurred_at end) as assigned_at,
    max(case when event_action = 'completed' then occurred_at end) as completed_at,
    max(case when event_action = 'cancelled' then occurred_at end) as cancelled_at,
    min(case when event_action = 'escalated' then occurred_at end) as escalated_at,
    min(case when event_action = 'sla_breached' then occurred_at end) as sla_breached_at,
    max(occurred_at) as last_event_at
from ev
where task_id is not null
group by tenant_id, task_id
