-- Estado corrente por registro de produção: ciclo do registro (created → pending/corrected → validated),
-- pendências de validação e retorno oficial (received → accepted/rejected → paid).
with ev as (
    select * from {{ ref('stg_production__events') }}
    where production_record_id is not null
),

rec as (
    select
        tenant_id,
        production_record_id,
        {{ latest('competence') }} as competence,
        {{ latest('production_kind') }} as production_kind,
        {{ latest('procedure_code') }} as procedure_code,
        {{ latest('health_unit_cnes') }} as health_unit_cnes,
        {{ latest('quantity') }} as quantity,
        {{ latest('status') }} as record_status_internal,
        {{ latest('errors_count') }} as errors_count,
        {{ latest('warnings_count') }} as warnings_count,
        {{ latest('correction_count') }} as correction_count,
        {{ latest('estimated_value') }} as estimated_value,
        {{ latest('deadline_at') }} as deadline_at,
        min(case when event_action = 'created' then occurred_at end) as created_at,
        max(case when event_action = 'validated' then occurred_at end) as validated_at,
        max(case when event_action = 'pending' then occurred_at end) as pending_at,
        max(case when event_action = 'corrected' then occurred_at end) as corrected_at
    from ev
    where production_entity = 'record'
    group by tenant_id, production_record_id
),

val as (
    select
        tenant_id,
        production_record_id,
        count(distinct case when event_action = 'issue_found' then issue_id end) as issues_found,
        count(distinct case when event_action = 'issue_found' and severity = 'error' then issue_id end) as error_issues_found,
        count(distinct case when event_action = 'issue_resolved' then issue_id end) as issues_resolved
    from ev
    where production_entity = 'validation'
    group by tenant_id, production_record_id
),

outc as (
    select
        tenant_id,
        production_record_id,
        {{ latest('outcome', 'coalesce(processed_at, occurred_at)') }} as outcome,
        {{ latest('record_status', 'coalesce(processed_at, occurred_at)') }} as record_status,
        {{ latest('batch_id') }} as batch_id,
        {{ latest('reason_code') }} as reason_code,
        {{ latest('reason') }} as reason,
        max(paid_amount) as paid_amount,
        {{ latest('approved_quantity') }} as approved_quantity,
        min(case when event_action = 'received' then coalesce(processed_at, occurred_at) end) as received_at,
        max(coalesce(processed_at, occurred_at)) as outcome_at,
        -- fallbacks se o registro não tiver evento próprio na janela
        {{ latest('competence') }} as competence,
        {{ latest('production_kind') }} as production_kind,
        {{ latest('procedure_code') }} as procedure_code,
        {{ latest('health_unit_cnes') }} as health_unit_cnes
    from ev
    where production_entity = 'outcome'
    group by tenant_id, production_record_id
),

ids as (
    select tenant_id, production_record_id from rec
    union distinct
    select tenant_id, production_record_id from outc
)

select
    ids.tenant_id,
    ids.production_record_id,
    coalesce(rec.competence, outc.competence) as competence,
    coalesce(rec.production_kind, outc.production_kind) as production_kind,
    coalesce(rec.procedure_code, outc.procedure_code) as procedure_code,
    coalesce(rec.health_unit_cnes, outc.health_unit_cnes) as health_unit_cnes,
    rec.quantity,
    rec.record_status_internal,
    rec.errors_count,
    rec.warnings_count,
    coalesce(rec.correction_count, 0) as correction_count,
    rec.estimated_value,
    rec.deadline_at,
    rec.created_at,
    rec.validated_at,
    rec.pending_at,
    rec.corrected_at,
    coalesce(val.issues_found, 0) as issues_found,
    coalesce(val.error_issues_found, 0) as error_issues_found,
    coalesce(val.issues_resolved, 0) as issues_resolved,
    outc.batch_id,
    outc.outcome,
    outc.record_status,
    outc.reason_code,
    outc.reason,
    outc.paid_amount,
    outc.approved_quantity,
    outc.received_at,
    outc.outcome_at
from ids
left join rec
    on ids.tenant_id = rec.tenant_id
        and ids.production_record_id = rec.production_record_id
left join val
    on ids.tenant_id = val.tenant_id
        and ids.production_record_id = val.production_record_id
left join outc
    on ids.tenant_id = outc.tenant_id
        and ids.production_record_id = outc.production_record_id
