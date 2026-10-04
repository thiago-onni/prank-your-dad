-- Produção (contracts/events/production): sus.production.record|validation|submission|outcome.v1.
-- Tolerante à ausência da fonte: se bronze.events_production não existir, materializa vazio com o mesmo schema.
-- Não projeta municipal_citizen_id / citizen_identifier_hash / professional_cns_hash do registro.
{% if bronze_exists('events_production') %}
    with env as (
        {{ stg_envelope('events_production', 'sus.production') }}
    )
{% else %}
with env as (
    {{ empty_envelope_select() }}
)
{% endif %}

select
    event_id,
    event_type,
    event_action,
    tenant_id,
    split_part(event_type, '.', 3) as production_entity,
    {{ json_str('data', 'production_record_id') }} as production_record_id,
    {{ json_str('data', 'batch_id') }} as batch_id,
    {{ json_str('data', 'competence') }} as competence,
    coalesce({{ json_str('data', 'cnes') }}, source_cnes) as health_unit_cnes,
    {{ json_str('data', 'kind') }} as production_kind,
    {{ json_str('data', 'procedure_code') }} as procedure_code,
    {{ json_int('data', 'quantity') }} as quantity,
    {{ json_str('data', 'status') }} as status,
    -- record
    {{ json_int('data', 'errors_count') }} as errors_count,
    {{ json_int('data', 'warnings_count') }} as warnings_count,
    {{ json_int('data', 'correction_count') }} as correction_count,
    {{ json_num('data', 'estimated_value') }} as estimated_value,
    {{ iso_to_local(json_str('data', 'deadline_at')) }} as deadline_at,
    {{ json_str('data', 'rule_version') }} as rule_version,
    -- validation
    {{ json_str('data', 'issue_id') }} as issue_id,
    {{ json_str('data', 'rule_id') }} as rule_id,
    {{ json_str('data', 'severity') }} as severity,
    {{ json_str('data', 'origin') }} as issue_origin,
    -- outcome
    {{ json_str('data', 'outcome') }} as outcome,
    {{ json_str('data', 'record_status') }} as record_status,
    {{ json_str('data', 'reason_code') }} as reason_code,
    {{ json_str('data', 'reason') }} as reason,
    {{ json_num('data', 'paid_amount') }} as paid_amount,
    {{ json_int('data', 'approved_quantity') }} as approved_quantity,
    {{ iso_to_local(json_str('data', 'processed_at')) }} as processed_at,
    -- submission
    {{ json_int('data', 'records_count') }} as records_count,
    {{ json_int('data', 'total_quantity') }} as total_quantity,
    {{ json_str('data', 'layout') }} as layout,
    {{ json_str('data', 'file_sha256') }} as file_sha256,
    {{ json_str('data', 'protocol_number') }} as protocol_number,
    {{ json_str('data', 'actor_kind') }} as actor_kind,
    source_system,
    occurred_at,
    published_at,
    published_at_utc
from env
