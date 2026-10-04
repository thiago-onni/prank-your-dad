{#-
  Base comum da staging: projeta o envelope (contracts/events/envelope.schema.json), filtra replay=false e
  faz dedupe por event_id (reentregas at-least-once). Retorna um SELECT com colunas padronizadas:
    event_id, event_type, event_action, event_version, tenant_id, municipal_citizen_id, source_system,
    source_connector, source_cnes, correlation_id, occurred_at (local), published_at (local),
    occurred_at_utc, published_at_utc, kafka_topic, data (texto JSON)
-#}
{% macro stg_envelope(source_table, event_type_prefix) -%}
with src as (
    select * from {{ source('bronze', source_table) }}
    where coalesce(replay, false) = false
      and event_type like '{{ event_type_prefix }}.%'
),
ranked as (
    select
        src.*,
        row_number() over (
            partition by event_id
            order by published_at desc, _kafka_metadata_offset desc
        ) as _rn
    from src
)
select
    event_id,
    event_type,
    reverse(split_part(reverse(event_type), '.', 1)) as event_action,
    event_version,
    tenant.municipality_id as tenant_id,
    subject.municipal_citizen_id as municipal_citizen_id,
    "source".system as source_system,
    "source".connector as source_connector,
    "source".cnes as source_cnes,
    trace.correlation_id as correlation_id,
    {{ ts_local('occurred_at') }} as occurred_at,
    {{ ts_local('published_at') }} as published_at,
    occurred_at as occurred_at_utc,
    published_at as published_at_utc,
    _kafka_metadata_topic as kafka_topic,
    data
from ranked
where _rn = 1
{%- endmacro %}

{#-
  Fonte tolerante: se a tabela bronze não existir (ex.: domínio ainda sem produtor), devolve um SELECT vazio
  com o mesmo layout do bronze para que os modelos a jusante compilem e materializem vazios.
-#}
{% macro bronze_exists(source_table) -%}
    {%- if not execute -%}{{ return(true) }}{%- endif -%}
    {%- set src = source('bronze', source_table) -%}
    {%- set rel = adapter.get_relation(database=src.database, schema=src.schema, identifier=src.identifier) -%}
    {{ return(rel is not none) }}
{%- endmacro %}

{% macro empty_envelope_select() -%}
select
    cast(null as varchar) as event_id,
    cast(null as varchar) as event_type,
    cast(null as varchar) as event_action,
    cast(null as varchar) as event_version,
    cast(null as varchar) as tenant_id,
    cast(null as varchar) as municipal_citizen_id,
    cast(null as varchar) as source_system,
    cast(null as varchar) as source_connector,
    cast(null as varchar) as source_cnes,
    cast(null as varchar) as correlation_id,
    cast(null as timestamp) as occurred_at,
    cast(null as timestamp) as published_at,
    cast(null as timestamp) as occurred_at_utc,
    cast(null as timestamp) as published_at_utc,
    cast(null as varchar) as kafka_topic,
    cast(null as varchar) as data
where 1 = 0
{%- endmacro %}

{#- true → dimensões de unidade/território vêm da réplica do core (catálogo Trino `postgresql`);
    false → seeds de referência (CI/DuckDB). Sobrescreva com --vars '{use_core_replica: false}'. -#}
{% macro use_core_replica() -%}
    {%- set v = var('use_core_replica', none) -%}
    {%- if v is none -%}{{ return(target.type == 'trino') }}{%- endif -%}
    {{ return((v | string | lower) in ['true', '1', 'yes']) }}
{%- endmacro %}
