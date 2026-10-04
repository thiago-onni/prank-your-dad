-- Produção por registro (PRO-001…010, contracts/events/production): validação, pendências, correções, lote,
-- retorno oficial (aceite/glosa/pagamento). Sem identificação de cidadão ou profissional.
with p as (
    select * from {{ ref('int_production_records') }}
),

b as (
    select * from {{ ref('int_production_batches') }}
)

select
    p.production_record_id,
    p.tenant_id,
    {{ health_unit_key('p.tenant_id', 'p.health_unit_cnes') }} as health_unit_key,
    p.health_unit_cnes,
    p.competence,
    p.production_kind,
    p.procedure_code,
    p.quantity,
    p.estimated_value,
    p.deadline_at,
    p.created_at,
    p.validated_at,
    p.errors_count,
    p.warnings_count,
    p.correction_count,
    p.issues_found,
    p.error_issues_found,
    p.issues_resolved,
    p.batch_id,
    b.status as batch_status,
    b.exported_at as batch_exported_at,
    b.transmitted_at as batch_transmitted_at,
    b.protocol_number,
    p.outcome,
    p.record_status,
    p.reason_code,
    p.reason,
    p.approved_quantity,
    p.paid_amount,
    p.received_at,
    p.outcome_at,
    p.validated_at is not null as is_validated,
    p.validated_at is null and p.pending_at is not null as is_pending,
    p.issues_found > 0 as had_issue,
    p.correction_count > 0 as was_corrected,
    p.validated_at is not null and p.deadline_at is not null and p.validated_at > p.deadline_at as is_validated_after_deadline,
    p.outcome in ('accepted', 'rejected', 'paid') as has_final_outcome,
    p.outcome in ('accepted', 'paid') as is_accepted,
    p.outcome = 'rejected' as is_rejected,
    p.outcome = 'paid' as is_paid
from p
left join b
    on p.tenant_id = b.tenant_id
        and p.batch_id = b.batch_id
