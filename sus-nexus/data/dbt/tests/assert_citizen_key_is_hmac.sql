-- citizen_key deve ser um HMAC-SHA256 hexadecimal (64 caracteres) — nunca o identificador em claro.
select citizen_key
from {{ ref('fct_appointments') }}
where
    citizen_key is not null
    and (length(citizen_key) <> 64 or substr(citizen_key, 1, 4) = 'cit_')
