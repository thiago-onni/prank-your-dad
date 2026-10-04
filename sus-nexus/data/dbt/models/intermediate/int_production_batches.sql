-- Estado corrente por lote de produção (sus.production.submission.*): gerado → aprovado → exportado → transmitido.
select
    tenant_id,
    batch_id,
    {{ latest('competence') }} as competence,
    {{ latest('health_unit_cnes') }} as health_unit_cnes,
    {{ latest('production_kind') }} as production_kind,
    {{ latest('status') }} as status,
    {{ latest('records_count') }} as records_count,
    {{ latest('total_quantity') }} as total_quantity,
    {{ latest('layout') }} as layout,
    {{ latest('file_sha256') }} as file_sha256,
    {{ latest('protocol_number') }} as protocol_number,
    min(case when event_action = 'batch_generated' then occurred_at end) as generated_at,
    min(case when event_action = 'batch_approved' then occurred_at end) as approved_at,
    min(case when event_action = 'exported' then occurred_at end) as exported_at,
    min(case when event_action = 'transmitted' then occurred_at end) as transmitted_at
from {{ ref('stg_production__events') }}
where production_entity = 'submission' and batch_id is not null
group by tenant_id, batch_id
