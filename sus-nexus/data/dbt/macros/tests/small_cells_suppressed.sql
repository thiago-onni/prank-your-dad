{#- Teste genérico de coluna: nenhum valor publicado entre 1 e (limiar-1) — células pequenas devem ser nulas. -#}
{% test small_cells_suppressed(model, column_name, threshold=none) %}
{%- set k = threshold if threshold is not none else var('small_cell_threshold', 5) -%}
select {{ column_name }}
from {{ model }}
where {{ column_name }} > 0 and {{ column_name }} < {{ k }}
{% endtest %}
