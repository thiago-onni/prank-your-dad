-- Tarefas operacionais (sus.task.*): origem humana, workflow, agente, regra ou conector.
with env as (
    {{ stg_envelope('events_task', 'sus.task') }}
)

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    municipal_citizen_id,
    {{ json_str('data', 'task_id') }} as task_id,
    {{ json_str('data', 'task_type') }} as task_type,
    {{ json_str('data', 'status') }} as status,
    {{ json_str('data', 'priority') }} as priority,
    {{ json_str('data', 'assignee.kind') }} as assignee_kind,
    {{ iso_to_local(json_str('data', 'due_at')) }} as due_at,
    {{ json_str('data', 'sla_policy_id') }} as sla_policy_id,
    {{ json_str('data', 'origin.kind') }} as origin_kind,
    {{ json_str('data', 'origin.id') }} as origin_id,
    {{ json_str('data', 'outcome') }} as outcome,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
