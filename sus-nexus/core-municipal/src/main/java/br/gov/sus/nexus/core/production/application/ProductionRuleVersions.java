package br.gov.sus.nexus.core.production.application;

import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.ProblemException.FieldError;
import br.gov.sus.nexus.core.platform.rules.RuleSets;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.production.api.ProductionRuleVersionCreate;
import br.gov.sus.nexus.core.production.api.ProductionRuleVersionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Nova versão da regra {@code production-validation} ("configuração antes de código", plano §8.3):
 * valida o jsonb (gramática restrita do {@code RuleEvaluator} + fatos conhecidos do {@link
 * PreAuditor}), executa os casos de teste anexados e só então ativa a versão do tenant (revogando a
 * anterior do tenant). Qualquer falha ⇒ 422 sem gravar nada.
 */
@ApplicationScoped
public class ProductionRuleVersions {

  private static final Logger LOG = Logger.getLogger(ProductionRuleVersions.class);

  static final Set<String> OPS =
      Set.of(
          "eq",
          "ne",
          "gt",
          "ge",
          "lt",
          "le",
          "in",
          "contains_any",
          "is_true",
          "is_false",
          "present");
  static final Set<String> OPS_WITH_VALUE =
      Set.of("eq", "ne", "gt", "ge", "lt", "le", "in", "contains_any");
  static final Set<String> NUMERIC_OPS = Set.of("gt", "ge", "lt", "le");
  static final Set<String> ARRAY_OPS = Set.of("in", "contains_any");
  static final Pattern RULE_ID = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
  static final int MAX_RULES = 200;
  static final int MAX_CASES = 500;

  @Inject RuleSets ruleSets;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;
  @Inject AuditService audit;
  @Inject ObjectMapper objectMapper;

  /** Valida, executa os casos de teste e ativa (chamado dentro de transação com tenant). */
  public ProductionRuleVersionDto create(ProductionRuleVersionCreate c) {
    List<FieldError> errors = new ArrayList<>();
    Set<String> ids = validateDefinition(c.definition(), errors);
    if (!errors.isEmpty()) {
      throw new DomainValidationException("definição de regra inválida", errors);
    }
    runTestCases(c.definition(), c.testCases(), ids, errors);
    if (!errors.isEmpty()) {
      throw new DomainValidationException(
          "casos de teste não passaram: a versão não foi ativada", errors);
    }
    String previous =
        ruleSets.current(PreAuditor.RULE_SET).map(v -> v.label(PreAuditor.RULE_SET)).orElse(null);
    RuleSets.RuleVersion v =
        ruleSets.activateNewVersion(
            PreAuditor.RULE_SET,
            tenantContext.require(),
            c.definition(),
            c.testCases(),
            currentActor.actorId());
    audit.record(
        AuditEntry.of("production.rule.version_activated", "rule_version", v.id(), null)
            .withReason(truncate(c.justification().trim(), 500))
            .withDetails(
                Map.of(
                    "rule_set",
                    PreAuditor.RULE_SET,
                    "version",
                    v.version(),
                    "previous",
                    previous == null ? "" : previous,
                    "rules",
                    c.definition().path("rules").size(),
                    "test_cases",
                    c.testCases().size())));
    LOG.infof(
        "regra %s ativada (anterior: %s, %d caso(s) de teste)",
        v.label(PreAuditor.RULE_SET), previous, c.testCases().size());
    return new ProductionRuleVersionDto(
        v.id(),
        PreAuditor.RULE_SET,
        v.version(),
        v.label(PreAuditor.RULE_SET),
        "active",
        c.definition().path("rules").size(),
        c.testCases().size(),
        v.approvedBy(),
        v.effectiveFrom() == null ? null : v.effectiveFrom().atOffset(ZoneOffset.UTC),
        previous);
  }

  /** Valida a definição; devolve os ids das regras. */
  static Set<String> validateDefinition(JsonNode def, List<FieldError> errors) {
    Set<String> ids = new HashSet<>();
    if (def == null || !def.isObject()) {
      errors.add(new FieldError("definition", "deve ser um objeto"));
      return ids;
    }
    if (!"validation_rules".equals(def.path("kind").asText(null))) {
      errors.add(new FieldError("definition.kind", "deve ser validation_rules"));
    }
    JsonNode rules = def.get("rules");
    if (rules == null || !rules.isArray() || rules.isEmpty()) {
      errors.add(new FieldError("definition.rules", "lista de regras obrigatória"));
      return ids;
    }
    if (rules.size() > MAX_RULES) {
      errors.add(new FieldError("definition.rules", "no máximo " + MAX_RULES + " regras"));
    }
    for (int i = 0; i < rules.size(); i++) {
      String at = "definition.rules[" + i + "]";
      JsonNode r = rules.get(i);
      if (!r.isObject()) {
        errors.add(new FieldError(at, "deve ser um objeto"));
        continue;
      }
      String id = r.path("id").asText("");
      if (!RULE_ID.matcher(id).matches()) {
        errors.add(new FieldError(at + ".id", "obrigatório (^[a-z][a-z0-9_]{1,63}$)"));
      } else if (!ids.add(id)) {
        errors.add(new FieldError(at + ".id", "duplicado: " + id));
      }
      String severity = r.path("severity").asText("");
      if (!"error".equals(severity) && !"warning".equals(severity)) {
        errors.add(new FieldError(at + ".severity", "error ou warning"));
      }
      if (!r.path("message").isTextual() || r.path("message").asText().isBlank()) {
        errors.add(new FieldError(at + ".message", "obrigatória"));
      }
      if (r.has("field") && !r.get("field").isNull() && !r.get("field").isTextual()) {
        errors.add(new FieldError(at + ".field", "deve ser texto"));
      }
      if (r.has("enabled") && !r.get("enabled").isBoolean()) {
        errors.add(new FieldError(at + ".enabled", "deve ser booleano"));
      }
      JsonNode when = r.get("when");
      if (when == null || !when.isObject() || when.isEmpty()) {
        errors.add(new FieldError(at + ".when", "condição de violação obrigatória"));
      } else {
        validateCondition(when, at + ".when", errors, 0);
      }
    }
    return ids;
  }

  static void validateCondition(JsonNode c, String at, List<FieldError> errors, int depth) {
    if (depth > 8) {
      errors.add(new FieldError(at, "aninhamento acima de 8 níveis"));
      return;
    }
    if (!c.isObject() || c.isEmpty()) {
      errors.add(new FieldError(at, "condição deve ser um objeto não vazio"));
      return;
    }
    for (String combinator : List.of("all", "any")) {
      if (c.has(combinator)) {
        JsonNode list = c.get(combinator);
        if (!list.isArray() || list.isEmpty()) {
          errors.add(new FieldError(at + "." + combinator, "lista não vazia de condições"));
          return;
        }
        for (int i = 0; i < list.size(); i++) {
          validateCondition(list.get(i), at + "." + combinator + "[" + i + "]", errors, depth + 1);
        }
        return;
      }
    }
    if (c.has("not")) {
      validateCondition(c.get("not"), at + ".not", errors, depth + 1);
      return;
    }
    String fact = c.path("fact").asText("");
    if (!PreAuditor.FACTS.contains(fact)) {
      errors.add(
          new FieldError(at + ".fact", "fato desconhecido: '" + fact + "' (ver PreAuditor.FACTS)"));
    }
    String op = c.path("op").asText("eq");
    if (!OPS.contains(op)) {
      errors.add(new FieldError(at + ".op", "operador não permitido: " + op));
      return;
    }
    JsonNode value = c.get("value");
    if (OPS_WITH_VALUE.contains(op) && (value == null || value.isNull())) {
      errors.add(new FieldError(at + ".value", "obrigatório para " + op));
    } else if (NUMERIC_OPS.contains(op) && !value.isNumber()) {
      errors.add(new FieldError(at + ".value", "numérico para " + op));
    } else if (ARRAY_OPS.contains(op) && !value.isArray()) {
      errors.add(new FieldError(at + ".value", "lista para " + op));
    }
  }

  /** Executa os casos de teste ({@code facts} → {@code expected} = ids das regras violadas). */
  void runTestCases(JsonNode def, JsonNode cases, Set<String> ids, List<FieldError> errors) {
    if (cases == null || !cases.isArray() || cases.isEmpty()) {
      errors.add(new FieldError("test_cases", "ao menos um caso de teste é obrigatório"));
      return;
    }
    if (cases.size() > MAX_CASES) {
      errors.add(new FieldError("test_cases", "no máximo " + MAX_CASES + " casos"));
      return;
    }
    for (int i = 0; i < cases.size(); i++) {
      String at = "test_cases[" + i + "]";
      JsonNode c = cases.get(i);
      if (!c.isObject() || !c.path("facts").isObject() || !c.path("expected").isArray()) {
        errors.add(new FieldError(at, "formato { facts: {...}, expected: [rule_id...] }"));
        continue;
      }
      Set<String> expected = new TreeSet<>();
      for (JsonNode e : c.get("expected")) {
        if (!ids.contains(e.asText())) {
          errors.add(new FieldError(at + ".expected", "regra inexistente: " + e.asText()));
        }
        expected.add(e.asText());
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> facts = objectMapper.convertValue(c.get("facts"), Map.class);
      Set<String> actual = new TreeSet<>();
      try {
        PreAuditor.apply(def, facts).forEach(f -> actual.add(f.ruleId()));
      } catch (IllegalArgumentException e) {
        errors.add(new FieldError(at, "falha ao avaliar: " + e.getMessage()));
        continue;
      }
      if (!actual.equals(expected)) {
        errors.add(new FieldError(at, "esperado " + expected + ", obtido " + actual));
      }
    }
  }

  private static String truncate(String s, int max) {
    return s.length() > max ? s.substring(0, max) : s;
  }
}
