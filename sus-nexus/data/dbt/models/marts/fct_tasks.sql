-- Tarefas: criadas, SLA, conclusão e origem (agente/workflow/regra/conector × humano) — indicadores de automação.
-- Grão: 1 linha por tarefa. Pseudonimizado.
with t as (
    select
        *,
        {{ now_local() }} as as_of
    from {{ ref('int_tasks') }}
)

select
    task_id,
    tenant_id,
    {{ citizen_key('municipal_citizen_id') }} as citizen_key,
    task_type,
    priority,
    status,
    origin_kind,
    case when origin_kind in ('agent', 'workflow', 'rule', 'connector') then 'automacao' else 'humano' end as origin_group,
    origin_kind = 'agent' as is_agent_created,
    origin_kind in ('agent', 'workflow', 'rule', 'connector') as is_automated,
    assignee_kind,
    {{ to_date('created_at') }} as created_date,
    created_at,
    assigned_at,
    due_at,
    completed_at,
    cancelled_at,
    {{ hours_between('created_at', 'assigned_at') }} as hours_to_assign,
    {{ hours_between('created_at', 'completed_at') }} as hours_to_complete,
    completed_at is not null as is_completed,
    cancelled_at is not null and completed_at is null as is_cancelled,
    completed_at is null and cancelled_at is null as is_open,
    escalated_at is not null as is_escalated,
    case
        when due_at is null then null
        when completed_at is not null then completed_at <= due_at and sla_breached_at is null
        when cancelled_at is not null then null
        when as_of > due_at then false
    end as is_sla_met,
    sla_breached_at is not null
    or (due_at is not null and coalesce(completed_at, as_of) > due_at and cancelled_at is null) as is_sla_breached
from t
