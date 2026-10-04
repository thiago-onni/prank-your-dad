-- Indicadores mensais (formato longo) por unidade e município, com meta (seed_metas_indicadores) e supressão
-- de células pequenas. Fonte única da "Sala de Situação" e do dicionário (data/INDICADORES.md).
with blocks as (
    {{ indicator_block('AGE_ABSENTEISMO', ref('fct_appointments'), 'scheduled_date', 'health_unit_cnes',
        count_true('is_no_show'), count_true('is_realized_slot')) }}
    union all
    {{ indicator_block('AGE_COMPARECIMENTO', ref('fct_appointments'), 'scheduled_date', 'health_unit_cnes',
        count_true('is_attended'), count_true('is_realized_slot')) }}
    union all
    {{ indicator_block('AGE_CANCELAMENTO', ref('fct_appointments'), 'scheduled_date', 'health_unit_cnes',
        count_true('is_cancelled'), 'count(*)') }}
    union all
    {{ indicator_block('AGE_REAPROVEITAMENTO', ref('fct_appointments'), 'scheduled_date', 'health_unit_cnes',
        count_true('is_cancelled and is_slot_reused'), count_true('is_cancelled')) }}
    union all
    {{ indicator_block('REG_ESPERA_P50_DIAS', ref('fct_regulation_requests'), 'requested_date', 'requesting_cnes',
        'cast(null as double)', count_true('scheduled_at is not null'), value_expr=pctl('wait_days_to_schedule', 0.5)) }}
    union all
    {{ indicator_block('REG_ESPERA_P90_DIAS', ref('fct_regulation_requests'), 'requested_date', 'requesting_cnes',
        'cast(null as double)', count_true('scheduled_at is not null'), value_expr=pctl('wait_days_to_schedule', 0.9)) }}
    union all
    {{ indicator_block('REG_SLA_CUMPRIDO', ref('fct_regulation_requests'), 'requested_date', 'requesting_cnes',
        count_true('is_sla_met'), count_true('is_sla_met is not null')) }}
    union all
    {{ indicator_block('REG_DEVOLUCAO', ref('fct_regulation_requests'), 'requested_date', 'requesting_cnes',
        count_true('was_returned'), 'count(*)') }}
    union all
    {{ indicator_block('REG_REALIZACAO', ref('fct_regulation_requests'), 'requested_date', 'requesting_cnes',
        count_true('is_performed'), count_true('not is_open')) }}
    union all
    {{ indicator_block('EXA_CICLO_COMPLETO', ref('fct_exam_cycle'), 'requested_date', 'requesting_cnes',
        count_true('is_cycle_complete'), 'count(*)') }}
    union all
    {{ indicator_block('EXA_RESULTADO_SEM_RETORNO', ref('fct_exam_cycle'), 'requested_date', 'requesting_cnes',
        count_true('is_result_without_return'), count_true('is_reported')) }}
    union all
    {{ indicator_block('EXA_DIAS_PEDIDO_RESULTADO_P50', ref('fct_exam_cycle'), 'requested_date', 'requesting_cnes',
        'cast(null as double)', count_true('is_reported'), value_expr=pctl('days_request_to_result', 0.5)) }}
    union all
    {{ indicator_block('HOS_REINTERNACAO_30D', ref('fct_hospital_episodes'), 'discharged_date', 'hospital_cnes',
        count_true('is_readmitted_30d'), count_true("is_discharged_alive and episode_class = 'inpatient'"),
        where="episode_class = 'inpatient'") }}
    union all
    {{ indicator_block('HOS_CONTATO_POS_ALTA_7D', ref('fct_hospital_episodes'), 'discharged_date', 'reference_health_unit_cnes',
        count_true('is_contacted_within_7d and is_discharged_alive'), count_true('is_discharged_alive')) }}
    union all
    {{ indicator_block('HOS_PERMANENCIA_MEDIA_DIAS', ref('fct_hospital_episodes'), 'discharged_date', 'hospital_cnes',
        'sum(length_of_stay_days)', 'count(*)', where="episode_class = 'inpatient'") }}
    union all
    {{ indicator_block('CUI_LACUNAS_RESOLVIDAS', ref('fct_care_gaps'), 'detected_date', 'health_unit_cnes',
        count_true('is_resolved'), 'count(*)') }}
    union all
    {{ indicator_block('TAR_SLA_CUMPRIDO', ref('fct_tasks'), 'created_date', none,
        count_true('is_sla_met'), count_true('is_sla_met is not null')) }}
    union all
    {{ indicator_block('TAR_AUTOMACAO', ref('fct_tasks'), 'created_date', none,
        count_true('is_automated'), 'count(*)') }}
    union all
    {{ indicator_block('TAR_AGENTE_SLA_CUMPRIDO', ref('fct_tasks'), 'created_date', none,
        count_true('is_sla_met'), count_true('is_sla_met is not null'), where='is_agent_created') }}
    union all
    {{ indicator_block('PRO_GLOSA', ref('fct_production'), 'coalesce(created_at, outcome_at)', 'health_unit_cnes',
        count_true('is_rejected'), count_true('has_final_outcome')) }}
)

select
    d.indicator_code,
    m.indicator_name,
    d.tenant_id,
    d.month_start,
    {{ competence_of('d.month_start') }} as competence,
    d.aggregation_level,
    d.health_unit_cnes,
    {{ health_unit_key('d.tenant_id', 'd.health_unit_cnes') }} as health_unit_key,
    -- supressão primária: numerador e denominador com 0 < n < limiar viram nulo; valor nulo se denominador < limiar
    case
        when d.denominator > 0 and d.denominator < {{ var('small_cell_threshold') }} then null
        else {{ suppress_small_cells('d.numerator') }}
    end as numerator,
    {{ suppress_small_cells('d.denominator') }} as denominator,
    case when d.denominator < {{ var('small_cell_threshold') }} then null else d.indicator_value end as indicator_value,
    d.denominator > 0 and d.denominator < {{ var('small_cell_threshold') }} as is_suppressed,
    m.unit as indicator_unit,
    m.direction,
    m.target as target_value,
    case
        when d.denominator < {{ var('small_cell_threshold') }} or d.indicator_value is null or m.target is null then null
        when m.direction = 'maior_melhor' then d.indicator_value >= m.target
        else d.indicator_value <= m.target
    end as is_on_target
from blocks as d
left join {{ ref('seed_metas_indicadores') }} as m
    on d.indicator_code = m.indicator_code
where not (d.aggregation_level = 'unidade' and d.health_unit_cnes is null)
