{#-
  Funções portáveis Trino ↔ DuckDB. Os modelos só usam estas macros para JSON, tempo, percentis e hash,
  garantindo que o mesmo SQL rode no lakehouse (Trino) e no CI (DuckDB).
-#}

{# Extrai um escalar (texto) de uma coluna JSON textual. key aceita caminho com ponto: 'origin.kind' #}
{% macro json_str(col, key) -%}{{ return(adapter.dispatch('json_str', 'sus_nexus')(col, key)) }}{%- endmacro %}
{% macro default__json_str(col, key) -%}json_extract_scalar({{ col }}, '$.{{ key }}'){%- endmacro %}
{% macro duckdb__json_str(col, key) -%}json_extract_string({{ col }}, '$.{{ key }}'){%- endmacro %}

{% macro json_int(col, key) -%}try_cast({{ json_str(col, key) }} as integer){%- endmacro %}
{% macro json_bool(col, key) -%}try_cast({{ json_str(col, key) }} as boolean){%- endmacro %}

{# Converte um timestamptz em timestamp local (sem fuso) no fuso de negócio #}
{% macro ts_local(col) -%}{{ return(adapter.dispatch('ts_local', 'sus_nexus')(col)) }}{%- endmacro %}
{% macro default__ts_local(col) -%}cast({{ col }} at time zone '{{ var("business_timezone") }}' as timestamp(6)){%- endmacro %}
{% macro duckdb__ts_local(col) -%}timezone('{{ var("business_timezone") }}', {{ col }}){%- endmacro %}

{# Converte texto ISO-8601 com offset (contrato de eventos) em timestamp local sem fuso #}
{% macro iso_to_local(expr) -%}{{ return(adapter.dispatch('iso_to_local', 'sus_nexus')(expr)) }}{%- endmacro %}
{% macro default__iso_to_local(expr) -%}cast(from_iso8601_timestamp({{ expr }}) at time zone '{{ var("business_timezone") }}' as timestamp(6)){%- endmacro %}
{% macro duckdb__iso_to_local(expr) -%}timezone('{{ var("business_timezone") }}', try_cast({{ expr }} as timestamp with time zone)){%- endmacro %}

{# Percentil contínuo (Trino: approx_percentile — aproximado; DuckDB: quantile_cont) #}
{% macro pctl(expr, p) -%}{{ return(adapter.dispatch('pctl', 'sus_nexus')(expr, p)) }}{%- endmacro %}
{% macro default__pctl(expr, p) -%}approx_percentile({{ expr }}, {{ p }}){%- endmacro %}
{% macro duckdb__pctl(expr, p) -%}quantile_cont({{ expr }}, {{ p }}){%- endmacro %}

{# HMAC-SHA256 em hexadecimal minúsculo #}
{% macro hmac_sha256_hex(expr, key) -%}{{ return(adapter.dispatch('hmac_sha256_hex', 'sus_nexus')(expr, key)) }}{%- endmacro %}
{% macro default__hmac_sha256_hex(expr, key) -%}lower(to_hex(hmac_sha256(to_utf8({{ expr }}), to_utf8({{ key }})))){%- endmacro %}
{% macro duckdb__hmac_sha256_hex(expr, key) -%}hmac_sha256_hex({{ expr }}, {{ key }}){%- endmacro %}

{# Diferença em horas/dias com fração (double) #}
{% macro hours_between(a, b) -%}(cast(date_diff('second', {{ a }}, {{ b }}) as double) / 3600.0){%- endmacro %}
{% macro days_between(a, b) -%}(cast(date_diff('second', {{ a }}, {{ b }}) as double) / 86400.0){%- endmacro %}

{# Razão segura em double #}
{% macro safe_ratio(num, den) -%}(cast({{ num }} as double) / nullif(cast({{ den }} as double), 0)){%- endmacro %}

{# Mês de referência (primeiro dia) de um timestamp local #}
{% macro month_of(expr) -%}cast(date_trunc('month', {{ expr }}) as date){%- endmacro %}

{# Chave de unidade de saúde (tenant + CNES) #}
{% macro health_unit_key(tenant_col, cnes_col) -%}
case when {{ cnes_col }} is not null then {{ tenant_col }} || ':' || {{ cnes_col }} end
{%- endmacro %}

{# Chave de território (tenant + CNES + INE + microárea) #}
{% macro territory_key(tenant_col, cnes_col, ine_col, micro_col) -%}
{{ tenant_col }} || ':' || coalesce({{ cnes_col }}, '-') || ':' || coalesce({{ ine_col }}, '-') || ':' || coalesce({{ micro_col }}, '-')
{%- endmacro %}

{# Data (date_key) para relacionamento com dim_date #}
{% macro to_date(expr) -%}cast({{ expr }} as date){%- endmacro %}

{# Último valor não nulo de col segundo a ordem (padrão occurred_at) — max_by existe no Trino e no DuckDB #}
{% macro latest(col, order_by='occurred_at') -%}
max_by({{ col }}, case when {{ col }} is not null then {{ order_by }} end)
{%- endmacro %}

{# Agora, no fuso de negócio, sem fuso (para medir pendências em aberto) #}
{% macro now_local() -%}{{ ts_local('current_timestamp') }}{%- endmacro %}

{# Dia da semana ISO (1 = segunda … 7 = domingo) #}
{% macro iso_dow(expr) -%}{{ return(adapter.dispatch('iso_dow', 'sus_nexus')(expr)) }}{%- endmacro %}
{% macro default__iso_dow(expr) -%}day_of_week({{ expr }}){%- endmacro %}
{% macro duckdb__iso_dow(expr) -%}isodow({{ expr }}){%- endmacro %}

{# Competência AAAAMM (texto) a partir de uma data #}
{% macro competence_of(expr) -%}cast(year({{ expr }}) * 100 + month({{ expr }}) as varchar){%- endmacro %}

{# Soma n dias a uma data #}
{% macro add_days(date_expr, n) -%}{{ return(adapter.dispatch('add_days', 'sus_nexus')(date_expr, n)) }}{%- endmacro %}
{% macro default__add_days(date_expr, n) -%}date_add('day', {{ n }}, {{ date_expr }}){%- endmacro %}
{% macro duckdb__add_days(date_expr, n) -%}cast({{ date_expr }} + cast({{ n }} as integer) as date){%- endmacro %}

{# Sequência de inteiros 0..(10^digits - 1) portátil (produto cartesiano de dígitos) #}
{% macro integer_sequence(digits=4) -%}
select
    {% for i in range(digits) -%}
    d{{ i }}.n * {{ 10 ** i }}{{ ' + ' if not loop.last }}
    {%- endfor %} as n
from
    {% for i in range(digits) -%}
    (values (0), (1), (2), (3), (4), (5), (6), (7), (8), (9)) as d{{ i }} (n){{ ',' if not loop.last }}
    {% endfor %}
{%- endmacro %}

{# Número (double) de uma coluna JSON textual #}
{% macro json_num(col, key) -%}try_cast({{ json_str(col, key) }} as double){%- endmacro %}
