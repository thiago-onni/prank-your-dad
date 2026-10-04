-- Espera não negativa e marcos em ordem: solicitação ≤ agendamento ≤ realização (quando existirem).
select regulation_request_id
from {{ ref('fct_regulation_requests') }}
where wait_days_to_schedule < 0
    or wait_days_to_performed < 0
    or (scheduled_at is not null and performed_at is not null and performed_at < scheduled_at)
