-- Célula suprimida (denominador < limiar) não pode publicar valor, numerador ou denominador.
select *
from {{ ref('agg_indicadores_mensais') }}
where is_suppressed
    and (indicator_value is not null or denominator is not null or numerator is not null)
