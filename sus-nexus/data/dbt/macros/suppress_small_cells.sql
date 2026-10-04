{#-
  Supressão de células pequenas (PLANO 10.2): se n < var('small_cell_threshold') (padrão 5) o valor vira nulo.
    expr       expressão a publicar (contagem, taxa, média, percentil)
    n          tamanho da célula que determina a supressão (padrão: a própria expr — para contagens)
    keep_zero  contagem zero não identifica ninguém e é publicada (padrão true)
  Ex.: {{ suppress_small_cells('count(*)') }} · {{ suppress_small_cells('avg(los)', n='count(*)') }}
-#}
{% macro suppress_small_cells(expr, n=none, keep_zero=true, threshold=none) -%}
{%- set n_expr = n if n is not none else expr -%}
{%- set k = threshold if threshold is not none else var('small_cell_threshold', 5) -%}
case
  {%- if keep_zero %}
  when ({{ n_expr }}) = 0 then {{ expr }}
  {%- endif %}
  when ({{ n_expr }}) < {{ k }} then null
  else {{ expr }}
end
{%- endmacro %}
