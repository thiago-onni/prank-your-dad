package br.gov.sus.nexus.core.platform.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Avaliador restrito de regras configuráveis (plano §8.3): tabelas de decisão e condições em JSON
 * sobre um conjunto de fatos nomeados — sem código arbitrário. Gramática:
 *
 * <pre>
 * condição  := { "fact": "...", "op": OP, "value": ... }
 *            | { "all": [condição...] } | { "any": [condição...] } | { "not": condição }
 * OP        := eq | ne | gt | ge | lt | le | in | contains_any | is_true | is_false | present
 * tabela    := { "kind": "decision_table", "default": R, "rules": [ { "result": R, ...condição } ] }
 * </pre>
 *
 * Um objeto vazio ({@code {}}) é sempre verdadeiro. Fatos ausentes nunca satisfazem comparações
 * (exceto {@code present} negado).
 */
public final class RuleEvaluator {

  private RuleEvaluator() {}

  /** Primeira regra satisfeita vence; sem regra satisfeita devolve {@code default}. */
  public static Optional<String> decide(JsonNode table, Map<String, Object> facts) {
    if (table == null || table.isNull()) {
      return Optional.empty();
    }
    JsonNode rules = table.path("rules");
    if (rules.isArray()) {
      for (JsonNode rule : rules) {
        if (matches(rule, facts)) {
          return Optional.ofNullable(text(rule.path("result")));
        }
      }
    }
    return Optional.ofNullable(text(table.path("default")));
  }

  /** Avalia uma condição (ou regra) contra os fatos. */
  public static boolean matches(JsonNode condition, Map<String, Object> facts) {
    if (condition == null || condition.isNull() || condition.isMissingNode()) {
      return true;
    }
    if (condition.isArray()) {
      for (JsonNode c : condition) {
        if (!matches(c, facts)) {
          return false;
        }
      }
      return true;
    }
    if (!condition.isObject()) {
      return false;
    }
    if (condition.has("all")) {
      for (JsonNode c : condition.get("all")) {
        if (!matches(c, facts)) {
          return false;
        }
      }
      return true;
    }
    if (condition.has("any")) {
      for (JsonNode c : condition.get("any")) {
        if (matches(c, facts)) {
          return true;
        }
      }
      return false;
    }
    if (condition.has("not")) {
      return !matches(condition.get("not"), facts);
    }
    if (!condition.has("fact")) {
      return true; // objeto sem condição (ex.: {} ou só "result")
    }
    Object actual = facts.get(condition.get("fact").asText());
    String op = condition.path("op").asText("eq");
    JsonNode expected = condition.get("value");
    return switch (op) {
      case "present" -> actual != null;
      case "is_true" -> Boolean.TRUE.equals(actual);
      case "is_false" -> Boolean.FALSE.equals(actual);
      case "eq" -> actual != null && equal(actual, expected);
      case "ne" -> actual != null && !equal(actual, expected);
      case "gt", "ge", "lt", "le" -> compare(actual, expected, op);
      case "in" -> actual != null && expected != null && expected.isArray() && in(actual, expected);
      case "contains_any" -> containsAny(actual, expected);
      default -> throw new IllegalArgumentException("operador de regra não permitido: " + op);
    };
  }

  private static boolean equal(Object actual, JsonNode expected) {
    if (expected == null || expected.isNull()) {
      return false;
    }
    if (actual instanceof Number n && expected.isNumber()) {
      return Double.compare(n.doubleValue(), expected.asDouble()) == 0;
    }
    if (actual instanceof Boolean b && expected.isBoolean()) {
      return b == expected.asBoolean();
    }
    return String.valueOf(actual).equalsIgnoreCase(expected.asText());
  }

  private static boolean compare(Object actual, JsonNode expected, String op) {
    if (!(actual instanceof Number n) || expected == null || !expected.isNumber()) {
      return false;
    }
    int c = Double.compare(n.doubleValue(), expected.asDouble());
    return switch (op) {
      case "gt" -> c > 0;
      case "ge" -> c >= 0;
      case "lt" -> c < 0;
      default -> c <= 0;
    };
  }

  private static boolean in(Object actual, JsonNode expected) {
    for (JsonNode e : expected) {
      if (equal(actual, e)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsAny(Object actual, JsonNode expected) {
    if (!(actual instanceof Collection<?> values) || expected == null || !expected.isArray()) {
      return false;
    }
    for (Object v : values) {
      if (v != null && in(v, expected)) {
        return true;
      }
    }
    return false;
  }

  private static String text(JsonNode n) {
    return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
  }
}
