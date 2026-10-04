-- Indicadores de unidade 'proporcao' devem ficar em [0, 1]; numerador nunca maior que o denominador.
select i.*
from {{ ref('agg_indicadores_mensais') }} as i
where i.indicator_unit = 'proporcao'
    and (
        i.indicator_value < 0
        or i.indicator_value > 1
        or (i.numerator is not null and i.denominator is not null and i.numerator > i.denominator)
    )
