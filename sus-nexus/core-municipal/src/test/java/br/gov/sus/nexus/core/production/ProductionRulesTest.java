package br.gov.sus.nexus.core.production;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.platform.rules.RuleSets;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.production.application.PreAuditor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regra versionada {@code production-validation}: os casos de teste anexados à versão vigente
 * (plano §8.3 — obrigatórios) passam no avaliador. Layouts de exportação: {@code
 * ExportLayoutsTest}.
 */
@QuarkusTest
class ProductionRulesTest {

  @Inject RuleSets ruleSets;
  @Inject TenantTransactions transactions;
  @Inject EntityManager entityManager;
  @Inject ObjectMapper objectMapper;

  @Test
  @SuppressWarnings("unchecked")
  void attachedTestCasesOfCurrentRuleVersionPass() throws Exception {
    RuleSets.RuleVersion current =
        transactions.runAs(
            TENANT_A,
            () ->
                QuarkusTransaction.requiringNew()
                    .call(
                        () -> {
                          transactions.applyCurrentTenant();
                          return ruleSets.current(PreAuditor.RULE_SET).orElseThrow();
                        }));
    assertThat(current.label(PreAuditor.RULE_SET)).isEqualTo("production-validation/1");
    String raw =
        transactions.runAs(
            TENANT_A,
            () ->
                QuarkusTransaction.requiringNew()
                    .call(
                        () -> {
                          transactions.applyCurrentTenant();
                          return (String)
                              entityManager
                                  .createNativeQuery(
                                      "select test_cases::text from platform.rule_version where id = ?1")
                                  .setParameter(1, current.id())
                                  .getSingleResult();
                        }));
    JsonNode cases = objectMapper.readTree(raw);
    assertThat(cases.size()).isGreaterThanOrEqualTo(4);
    for (JsonNode c : cases) {
      Map<String, Object> facts = objectMapper.convertValue(c.get("facts"), Map.class);
      List<String> expected = new ArrayList<>();
      c.get("expected").forEach(e -> expected.add(e.asText()));
      List<String> actual =
          PreAuditor.apply(current.definition(), facts).stream()
              .map(PreAuditor.Finding::ruleId)
              .toList();
      assertThat(actual).as("caso %s", c).containsExactlyInAnyOrderElementsOf(expected);
    }
  }
}
