package br.gov.sus.nexus.core.platform.pagination;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.function.Function;

/** Página de resultados: {@code { "items": [...], "next_cursor": "..." | null }}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Page<T>(List<T> items, String nextCursor) {

  /**
   * Monta a página a partir de uma lista buscada com {@code limit + 1} elementos: se houver
   * excedente, há próxima página e o cursor é o id do último item retornado.
   */
  public static <T> Page<T> of(List<T> fetched, int limit, Function<T, String> keyOf) {
    if (fetched.size() <= limit) {
      return new Page<>(fetched, null);
    }
    List<T> items = fetched.subList(0, limit);
    return new Page<>(List.copyOf(items), Cursor.encode(keyOf.apply(items.get(limit - 1))));
  }
}
