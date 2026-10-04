-- Calendário diário com competência (AAAAMM). Faixa: vars dim_date_start/dim_date_end.
with seq as (
    {{ integer_sequence(4) }}
),

days as (
    select {{ add_days("cast('" ~ var('dim_date_start') ~ "' as date)", 'seq.n') }} as date_day
    from seq
    where seq.n <= date_diff('day', cast('{{ var("dim_date_start") }}' as date), cast('{{ var("dim_date_end") }}' as date))
)

select
    days.date_day,
    year(days.date_day) as year,
    quarter(days.date_day) as quarter,
    month(days.date_day) as month,
    {{ month_of('days.date_day') }} as month_start,
    {{ competence_of('days.date_day') }} as competence,
    {{ iso_dow('days.date_day') }} as iso_day_of_week,
    {{ iso_dow('days.date_day') }} in (6, 7) as is_weekend,
    comp.prazo_envio is not null and days.date_day = comp.prazo_envio as is_submission_deadline
from days
left join {{ ref('seed_competencias') }} as comp
    on {{ competence_of('days.date_day') }} = cast(comp.competencia as varchar)
