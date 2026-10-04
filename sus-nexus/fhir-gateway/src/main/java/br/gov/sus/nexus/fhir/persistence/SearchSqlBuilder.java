package br.gov.sus.nexus.fhir.persistence;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import br.gov.sus.nexus.fhir.persistence.FhirDates.SearchValue;
import br.gov.sus.nexus.fhir.persistence.SearchQuery.SearchFilter;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Traduz uma {@link SearchQuery} em SQL parametrizado sobre as tabelas de índice. */
public final class SearchSqlBuilder {

  private SearchSqlBuilder() {}

  /** SQL + parâmetros posicionais. */
  public record Sql(String text, List<Object> params) {}

  /** Cláusula OR parcial com seus parâmetros. */
  private record Clause(List<String> ors, List<Object> params) {
    Clause() {
      this(new ArrayList<>(), new ArrayList<>());
    }

    void add(String sql, Object... values) {
      ors.add(sql);
      for (Object v : values) {
        params.add(v);
      }
    }

    String joined() {
      return "(" + String.join(" OR ", ors) + ")";
    }
  }

  public static Sql build(SearchQuery query, String tenantId) {
    List<Object> params = new ArrayList<>();
    String sortKey = sortKeyExpression(query, params);
    StringBuilder sql =
        new StringBuilder(
            "SELECT r.id, r.tenant_id, r.resource_type, r.version_id, r.last_updated, r.profile,"
                + " r.content::text, r.deleted, "
                + sortKey
                + " AS sort_key FROM fhir.fhir_resource r WHERE ");
    appendWhere(sql, params, query, tenantId);

    if (query.sort() == null) {
      if (query.afterId() != null) {
        sql.append(" AND r.id > ?");
        params.add(query.afterId());
      }
      sql.append(" ORDER BY r.id ASC LIMIT ?");
    } else {
      if (query.afterId() != null && query.afterSortKey() != null) {
        Timestamp after = Timestamp.from(java.time.Instant.parse(query.afterSortKey()));
        String cmp = query.sort().descending() ? "<" : ">";
        String key = sortKeyExpression(query, params);
        sql.append(" AND ((").append(key).append(" ").append(cmp).append(" ?) OR (");
        params.add(after);
        String key2 = sortKeyExpression(query, params);
        sql.append(key2).append(" = ? AND r.id > ?))");
        params.add(after);
        params.add(query.afterId());
      }
      sql.append(" ORDER BY sort_key ")
          .append(query.sort().descending() ? "DESC" : "ASC")
          .append(", r.id ASC LIMIT ?");
    }
    params.add(query.count() + 1);
    return new Sql(sql.toString(), params);
  }

  /** Contagem total ({@code _total=accurate}) com os mesmos filtros, sem paginação. */
  public static Sql buildCount(SearchQuery query, String tenantId) {
    List<Object> params = new ArrayList<>();
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM fhir.fhir_resource r WHERE ");
    appendWhere(sql, params, query, tenantId);
    return new Sql(sql.toString(), params);
  }

  /**
   * Expressão da chave de ordenação. Sem {@code _sort} é o próprio {@code last_updated} (não usado
   * na ordenação); {@code _lastUpdated} usa a coluna; parâmetros de data usam o menor {@code low}
   * indexado, com sentinela para recursos sem o elemento (ficam no fim em ordem crescente e no
   * início em ordem decrescente — comportamento documentado).
   */
  private static String sortKeyExpression(SearchQuery query, List<Object> params) {
    if (query.sort() == null || query.sort().param().isComputed()) {
      return "r.last_updated";
    }
    params.add(query.sort().param().name());
    params.add(Timestamp.from(FhirDates.MAX));
    return "COALESCE((SELECT MIN(t.low) FROM fhir.fhir_idx_date t WHERE t.resource_id = r.id"
        + " AND t.param = ?), ?)";
  }

  private static void appendWhere(
      StringBuilder sql, List<Object> params, SearchQuery query, String tenantId) {
    sql.append("r.tenant_id = ? AND r.resource_type = ? AND r.deleted = false");
    params.add(tenantId);
    params.add(query.resourceType());

    for (SearchFilter filter : query.filters()) {
      sql.append(" AND ");
      String name = filter.def().name();
      switch (filter.def().type()) {
        case TOKEN -> {
          if (CapabilityRegistry.PARAM_ID.name().equals(name)) {
            sql.append(idClause(filter, params));
          } else {
            sql.append(exists("fhir_idx_token", filter, tenantId, tokenClause(filter), params));
          }
        }
        case STRING ->
            sql.append(exists("fhir_idx_string", filter, tenantId, stringClause(filter), params));
        case DATE -> {
          if (CapabilityRegistry.PARAM_LAST_UPDATED.name().equals(name)) {
            Clause c = lastUpdatedClause(filter);
            sql.append(c.joined());
            params.addAll(c.params());
          } else {
            sql.append(exists("fhir_idx_date", filter, tenantId, dateClause(filter), params));
          }
        }
        case REFERENCE ->
            sql.append(
                exists("fhir_idx_reference", filter, tenantId, referenceClause(filter), params));
        case QUANTITY ->
            sql.append(
                exists("fhir_idx_quantity", filter, tenantId, quantityClause(filter), params));
      }
    }
  }

  private static String idClause(SearchFilter f, List<Object> params) {
    rejectModifier(f, "");
    String placeholders = f.values().stream().map(v -> "?").collect(Collectors.joining(","));
    params.addAll(f.values());
    return "r.id IN (" + placeholders + ")";
  }

  private static Clause tokenClause(SearchFilter f) {
    rejectModifier(f, "");
    Clause c = new Clause();
    for (String raw : f.values()) {
      int bar = raw.indexOf('|');
      if (bar < 0) {
        c.add("t.code = ?", raw);
      } else {
        String system = raw.substring(0, bar);
        String code = raw.substring(bar + 1);
        if (system.isEmpty() && code.isEmpty()) {
          throw FhirException.invalid("Valor de token inválido para " + f.def().name());
        }
        if (system.isEmpty()) {
          c.add("(t.system IS NULL AND t.code = ?)", code);
        } else if (code.isEmpty()) {
          c.add("t.system = ?", system);
        } else {
          c.add("(t.system = ? AND t.code = ?)", system, code);
        }
      }
    }
    return c;
  }

  private static Clause stringClause(SearchFilter f) {
    rejectModifier(f, "", "exact", "contains");
    Clause c = new Clause();
    for (String raw : f.values()) {
      String norm = StringNormalizer.normalize(raw);
      if (norm.isEmpty()) {
        throw FhirException.invalid("Valor vazio para " + f.def().name());
      }
      switch (f.modifier()) {
        case "exact" -> c.add("t.value_norm = ?", norm);
        case "contains" -> c.add("t.value_norm LIKE ? ESCAPE '\\'", "%" + escapeLike(norm) + "%");
        default -> c.add("t.value_norm LIKE ? ESCAPE '\\'", escapeLike(norm) + "%");
      }
    }
    return c;
  }

  private static Clause dateClause(SearchFilter f) {
    rejectModifier(f, "");
    Clause c = new Clause();
    for (String raw : f.values()) {
      SearchValue sv = parseDate(f, raw);
      Timestamp low = Timestamp.from(sv.range().low());
      Timestamp high = Timestamp.from(sv.range().high());
      switch (sv.prefix()) {
        case EQ -> c.add("(t.low >= ? AND t.high <= ?)", low, high);
        case NE -> c.add("NOT (t.low >= ? AND t.high <= ?)", low, high);
        case GT -> c.add("t.high > ?", high);
        case LT -> c.add("t.low < ?", low);
        case GE -> c.add("t.high > ?", low);
        case LE -> c.add("t.low < ?", high);
        case SA -> c.add("t.low >= ?", high);
        case EB -> c.add("t.high <= ?", low);
      }
    }
    return c;
  }

  private static Clause lastUpdatedClause(SearchFilter f) {
    rejectModifier(f, "");
    Clause c = new Clause();
    String col = "r.last_updated";
    for (String raw : f.values()) {
      SearchValue sv = parseDate(f, raw);
      Timestamp low = Timestamp.from(sv.range().low());
      Timestamp high = Timestamp.from(sv.range().high());
      switch (sv.prefix()) {
        case EQ -> c.add("(" + col + " >= ? AND " + col + " < ?)", low, high);
        case NE -> c.add("NOT (" + col + " >= ? AND " + col + " < ?)", low, high);
        case GT, SA -> c.add(col + " >= ?", high);
        case LT, EB -> c.add(col + " < ?", low);
        case GE -> c.add(col + " >= ?", low);
        case LE -> c.add(col + " < ?", high);
      }
    }
    return c;
  }

  private static Clause referenceClause(SearchFilter f) {
    String typeModifier = f.modifier().isEmpty() ? null : f.modifier();
    if (typeModifier != null && !typeModifier.matches("[A-Z][A-Za-z]+")) {
      throw FhirException.invalid(
          "Modificador não suportado para " + f.def().name() + ": " + f.modifier());
    }
    Clause c = new Clause();
    for (String raw : f.values()) {
      Optional<SearchIndexer.Target> target = SearchIndexer.parseReference(raw);
      if (target.isPresent()) {
        c.add("(t.target_type = ? AND t.target_id = ?)", target.get().type(), target.get().id());
      } else if (raw.matches("[A-Za-z0-9\\-\\.]{1,64}")) {
        if (typeModifier != null) {
          c.add("(t.target_type = ? AND t.target_id = ?)", typeModifier, raw);
        } else {
          c.add("t.target_id = ?", raw);
        }
      } else {
        throw FhirException.invalid("Referência inválida para " + f.def().name());
      }
    }
    return c;
  }

  /**
   * {@code value-quantity=[prefix]number[|system|code]}: prefixos eq/ne/gt/lt/ge/le; {@code eq}
   * (padrão) usa a precisão implícita do número informado (ex.: {@code 5.4} casa 5.35..5.45).
   */
  private static Clause quantityClause(SearchFilter f) {
    rejectModifier(f, "");
    Clause c = new Clause();
    for (String raw : f.values()) {
      String v = raw.trim();
      String prefix = "eq";
      if (v.length() > 2 && v.substring(0, 2).matches("eq|ne|gt|lt|ge|le|sa|eb|ap")) {
        prefix = v.substring(0, 2);
        v = v.substring(2);
      }
      String[] parts = v.split("\\|", -1);
      java.math.BigDecimal number;
      try {
        number = new java.math.BigDecimal(parts[0].trim());
      } catch (NumberFormatException e) {
        throw FhirException.invalid("Quantidade inválida para " + f.def().name() + ": " + raw);
      }
      java.math.BigDecimal half =
          java.math.BigDecimal.ONE.movePointLeft(Math.max(number.scale(), 0)).divide(
              java.math.BigDecimal.valueOf(2));
      java.math.BigDecimal low = number.subtract(half);
      java.math.BigDecimal high = number.add(half);
      StringBuilder unit = new StringBuilder();
      List<Object> unitParams = new ArrayList<>();
      if (parts.length == 3) {
        if (!parts[1].isBlank()) {
          unit.append(" AND t.system = ?");
          unitParams.add(parts[1]);
        }
        if (!parts[2].isBlank()) {
          unit.append(" AND t.code = ?");
          unitParams.add(parts[2]);
        }
      } else if (parts.length == 2 && !parts[1].isBlank()) {
        unit.append(" AND t.code = ?");
        unitParams.add(parts[1]);
      } else if (parts.length > 3) {
        throw FhirException.invalid("Quantidade inválida para " + f.def().name() + ": " + raw);
      }
      String cmp =
          switch (prefix) {
            case "eq", "ap" -> "(t.value >= ? AND t.value < ?)";
            case "ne" -> "NOT (t.value >= ? AND t.value < ?)";
            case "gt", "sa" -> "t.value > ?";
            case "lt", "eb" -> "t.value < ?";
            case "ge" -> "t.value >= ?";
            case "le" -> "t.value <= ?";
            default -> throw FhirException.invalid("Prefixo inválido para " + f.def().name());
          };
      List<Object> values = new ArrayList<>();
      if (prefix.equals("eq") || prefix.equals("ap") || prefix.equals("ne")) {
        values.add(low);
        values.add(high);
      } else {
        values.add(number);
      }
      values.addAll(unitParams);
      c.add("(" + cmp + unit + ")", values.toArray());
    }
    return c;
  }

  private static String exists(
      String table, SearchFilter f, String tenantId, Clause clause, List<Object> params) {
    params.add(tenantId);
    params.add(f.def().name());
    params.addAll(clause.params());
    return "EXISTS (SELECT 1 FROM fhir."
        + table
        + " t WHERE t.resource_id = r.id AND t.tenant_id = ? AND t.param = ? AND "
        + clause.joined()
        + ")";
  }

  private static SearchValue parseDate(SearchFilter f, String raw) {
    return FhirDates.parseSearchValue(raw)
        .orElseThrow(
            () -> FhirException.invalid("Data inválida para " + f.def().name() + ": " + raw));
  }

  private static void rejectModifier(SearchFilter f, String... allowed) {
    for (String a : allowed) {
      if (a.equals(f.modifier())) {
        return;
      }
    }
    throw FhirException.invalid(
        "Modificador não suportado para " + f.def().name() + ": " + f.modifier());
  }

  private static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
