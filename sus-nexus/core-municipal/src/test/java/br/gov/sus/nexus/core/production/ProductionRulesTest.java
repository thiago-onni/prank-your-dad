package br.gov.sus.nexus.core.production;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.platform.rules.RuleSets;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.production.application.ExportLayouts;
import br.gov.sus.nexus.core.production.application.PreAuditor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regra versionada {@code production-validation}: os casos de teste anexados à versão vigente
 * (plano §8.3 — obrigatórios) passam no avaliador; layout BPA-Mag de referência (larguras e campo
 * de controle).
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

  @Test
  void bpaMagReferenceLayout() {
    ExportLayouts.Line bpaI =
        new ExportLayouts.Line(
            "prod_x",
            "bpa_i",
            "1234567",
            "202610",
            "123456789012345",
            "225142",
            LocalDate.of(2026, 10, 1),
            "0301010064",
            "898001234565678",
            "F",
            "3143302",
            "I10",
            46,
            1,
            "elective",
            null);
    ExportLayouts.Line bpaC =
        new ExportLayouts.Line(
            "prod_y",
            "bpa_c",
            "1234567",
            "202610",
            null,
            "515105",
            LocalDate.of(2026, 10, 2),
            "0101010010",
            null,
            null,
            null,
            null,
            null,
            25,
            null,
            null);
    ExportLayouts.Rendered r =
        ExportLayouts.bpaMag(
            "202610",
            List.of(bpaI, bpaC),
            new ExportLayouts.Header("SMS Montes Claros", "SMS", "123"));
    String[] lines = new String(r.content(), StandardCharsets.US_ASCII).split("\r\n");
    assertThat(lines).hasSize(3);
    long control = (301010064L + 1 + 101010010L + 25) % 1111 + 1111;
    assertThat(lines[0])
        .startsWith("01#BPA#202610000002000001" + control)
        .hasSize(2 + 5 + 6 + 6 + 6 + 4 + 30 + 6 + 14 + 40 + 1 + 10);
    assertThat(lines[1])
        .hasSize(2 + 7 + 6 + 15 + 6 + 8 + 3 + 2 + 10 + 15 + 1 + 6 + 4 + 3 + 6 + 2 + 13 + 3)
        .startsWith("031234567202610123456789012345225142" + "20261001" + "00101" + "0301010064")
        .contains("898001234565678F314330I10 04600000101")
        .endsWith("BPA");
    assertThat(lines[2])
        .isEqualTo("021234567202610515105" + "00102" + "0101010010" + "000" + "000025" + "BPA");
    assertThat(r.missingIdentifiers()).isZero();
    assertThat(r.lines()).isEqualTo(2);
  }
}
