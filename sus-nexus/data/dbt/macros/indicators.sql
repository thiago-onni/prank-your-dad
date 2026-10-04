{#-
  Bloco de indicador mensal para agg_indicadores_mensais. Gera linhas por unidade (CNES) e o total do município
  (GROUPING SETS), com numerador, denominador e valor (razão num/den, ou value_expr para percentis/médias).
  A supressão de células pequenas é aplicada no modelo final.
-#}
{% macro indicator_block(code, relation, date_col, unit_col, num_expr, den_expr, value_expr=none, where=none) -%}
select
    '{{ code }}' as indicator_code,
    tenant_id,
    {{ month_of(date_col) }} as month_start,
    {%- if unit_col is none %}
    cast(null as varchar) as health_unit_cnes,
    'municipio' as aggregation_level,
    {%- else %}
    {{ unit_col }} as health_unit_cnes,
    case when grouping({{ unit_col }}) = 1 then 'municipio' else 'unidade' end as aggregation_level,
    {%- endif %}
    cast({{ num_expr }} as double) as numerator,
    cast({{ den_expr }} as double) as denominator,
    {%- if value_expr is not none %}
    cast({{ value_expr }} as double) as indicator_value
    {%- else %}
    {{ safe_ratio(num_expr, den_expr) }} as indicator_value
    {%- endif %}
from {{ relation }}
where {{ date_col }} is not null
{%- if where is not none %}
  and ({{ where }})
{%- endif %}
{%- if unit_col is none %}
group by tenant_id, {{ month_of(date_col) }}
{%- else %}
group by grouping sets (
    (tenant_id, {{ month_of(date_col) }}, {{ unit_col }}),
    (tenant_id, {{ month_of(date_col) }})
)
{%- endif %}
{%- endmacro %}

{# Conta booleana portátil #}
{% macro count_true(expr) -%}sum(case when {{ expr }} then 1 else 0 end){%- endmacro %}
