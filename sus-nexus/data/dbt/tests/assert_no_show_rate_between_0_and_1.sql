-- Taxa de absenteísmo (no-show) publicada deve estar em [0, 1] e ser consistente com as contagens.
select *
from {{ ref('agg_appointments_monthly') }}
where no_show_rate < 0
    or no_show_rate > 1
    or (no_show_rate is not null and n_realized is null)
    or (attendance_rate is not null and no_show_rate is not null and abs(attendance_rate + no_show_rate - 1) > 0.000001)
