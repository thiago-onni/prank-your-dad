{#- Schemas fixos por camada (staging, intermediate, marts, marts_aggregated, marts_identified, reference),
    iguais em todos os alvos: as regras de acesso do Trino (rules.json) dependem desses nomes. -#}
{% macro generate_schema_name(custom_schema_name, node) -%}
    {%- if custom_schema_name is none -%}{{ target.schema | trim }}{%- else -%}{{ custom_schema_name | trim }}{%- endif -%}
{%- endmacro %}
