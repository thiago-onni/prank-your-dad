{#-
  Teste genérico de modelo: falha se o modelo tiver colunas cujo nome indique dado identificável
  (CNS, CPF, nome, nome da mãe, data de nascimento, identificador municipal em claro, telefone, endereço).
  Uso (models/**/*.yml):
    data_tests:
      - no_pii_columns
      - no_pii_columns: { extra_patterns: ['^ine$'] }
  Permitidas: citizen_key (HMAC), *_count, *_cnes (estabelecimento, não pessoa).
-#}
{% test no_pii_columns(model, extra_patterns=[]) %}
{%- set patterns = [
    '(^|_)cns($|_)', '(^|_)cpf($|_)', '(^|_)nome($|_)', '(^|_)name($|_)', 'mother', '(^|_)mae($|_)',
    'birth', 'nascimento', 'municipal_citizen_id', '(^|_)citizen_id$', 'phone', 'telefone', 'email',
    'address', 'endereco', 'identifiers', 'value_hash', 'value_masked', '^subject$'
] + extra_patterns -%}
{%- set allowed = ['health_unit_name', 'team_name', 'care_line_name', 'indicator_name', 'territory_name', 'unit_name'] -%}
{%- set offending = [] -%}
{%- if execute -%}
    {%- for col in adapter.get_columns_in_relation(model) -%}
        {%- set c = col.name | lower -%}
        {%- if c not in allowed -%}
            {%- for p in patterns -%}
                {%- if modules.re.search(p, c) and c not in offending -%}{%- do offending.append(c) -%}{%- endif -%}
            {%- endfor -%}
        {%- endif -%}
    {%- endfor -%}
{%- endif -%}
select '{{ offending | join(",") }}' as pii_columns
where {{ 'true' if offending | length > 0 else 'false' }}
{% endtest %}
