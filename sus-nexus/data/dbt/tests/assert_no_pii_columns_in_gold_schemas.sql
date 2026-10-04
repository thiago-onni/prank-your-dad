-- Varredura do catálogo: nenhuma coluna com nome identificável (cns, cpf, nome, nascimento, identificador
-- municipal em claro) nos schemas gold pseudonimizados/agregados. marts_identified é a única exceção.
select table_schema, table_name, column_name
from information_schema.columns
where lower(table_schema) in ('marts', 'marts_aggregated')
    and (
        lower(column_name) like '%cns%'
        or lower(column_name) like '%cpf%'
        or lower(column_name) like '%nome%'
        or lower(column_name) like '%birth%'
        or lower(column_name) like '%nascimento%'
        or lower(column_name) like '%mother%'
        or lower(column_name) like '%municipal_citizen_id%'
        or lower(column_name) like '%phone%'
        or lower(column_name) like '%address%'
    )
