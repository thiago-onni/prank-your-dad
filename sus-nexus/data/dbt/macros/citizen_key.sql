{#-
  Pseudonimização (camada gold): citizen_key = HMAC-SHA256(municipal_citizen_id, salt).
  - O salt vem de var('citizen_key_salt') ← env DBT_CITIZEN_KEY_SALT (OpenBao em hml/prod).
  - Determinístico: permite contar pessoas distintas e cruzar fatos sem expor o identificador.
  - Nunca use nome/CNS/CPF como entrada (não trafegam nos eventos de domínio).
-#}
{% macro citizen_key(col) -%}
case when {{ col }} is not null then {{ hmac_sha256_hex(col, "'" ~ var('citizen_key_salt') ~ "'") }} end
{%- endmacro %}
