package br.gov.sus.nexus.core.production;

import static br.gov.sus.nexus.core.production.ProductionFlowTest.actor;
import static br.gov.sus.nexus.core.production.ProductionFlowTest.newUnit;
import static br.gov.sus.nexus.core.production.ProductionFlowTest.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Outbox;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /api/v1/production/rules}: só gestor/admin_municipal (agente/auditor → 403); jsonb
 * validado (operador, fato, estrutura → 422); casos de teste executados antes de ativar (falha →
 * 422 sem gravar); versão nova do tenant passa a valer na pré-auditoria e revoga a anterior do
 * tenant; a global (seed) e os demais tenants continuam na v1. Tenant dedicado (não interfere nos
 * testes que esperam {@code production-validation/1}).
 */
@QuarkusTest
class ProductionRuleVersionTest {

  static final String TENANT = "ibge_3550308";
  static final String PATH = "/api/v1/production/rules";

  static ObjectNode definitionV1() throws Exception {
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "select definition::text from platform.rule_version"
                    + " where id = 'rv_production_validation_1'")) {
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (ObjectNode) Outbox.MAPPER.readTree(rs.getString(1));
      }
    }
  }

  static ObjectNode withReviewRule() throws Exception {
    ObjectNode def = definitionV1();
    ((ArrayNode) def.get("rules"))
        .add(
            Outbox.MAPPER.readTree(
                "{\"id\":\"bpa_c_review\",\"severity\":\"warning\",\"field\":\"kind\","
                    + "\"message\":\"BPA-C em revisão municipal\","
                    + "\"when\":{\"fact\":\"kind\",\"op\":\"eq\",\"value\":\"bpa_c\"}}"));
    return def;
  }

  static JsonNode cases(String bpaCExpected) throws Exception {
    return Outbox.MAPPER.readTree(
        "[{\"facts\":{\"kind\":\"bpa_c\"},\"expected\":["
            + bpaCExpected
            + "]},{\"facts\":{\"kind\":\"bpa_i\",\"cbo_compatible\":false,\"evidence_present\":true},"
            + "\"expected\":[\"cbo_incompatible\"]}]");
  }

  static Map<String, Object> body(JsonNode definition, JsonNode testCases) {
    return Map.of(
        "definition",
        Outbox.MAPPER.convertValue(definition, Map.class),
        "test_cases",
        Outbox.MAPPER.convertValue(testCases, List.class),
        "justification",
        "Revisão municipal do BPA-C pactuada na CIB");
  }

  static String status(String version) throws Exception {
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "select v.status from platform.rule_version v join platform.rule_set s on"
                    + " s.id = v.rule_set_id where s.name = 'production-validation'"
                    + " and v.tenant_id = ? and v.version = ?")) {
      ps.setString(1, TENANT);
      ps.setString(2, version);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  @Test
  void newRuleVersionIsValidatedTestedAndActivated() throws Exception {
    Map<String, Object> valid = body(withReviewRule(), cases("\"bpa_c_review\""));

    // papéis: agente (mesmo com gestor) e auditor → 403
    actor(TENANT, "agent-auditoria", "agente_ia").body(valid).post(PATH).then().statusCode(403);
    actor(TENANT, "agent-auditoria", "gestor,agente_ia")
        .body(valid)
        .post(PATH)
        .then()
        .statusCode(403);
    actor(TENANT, "auditora.carla", "auditor").body(valid).post(PATH).then().statusCode(403);

    // justificativa curta → 400
    actor(TENANT, "gestor.joao", "gestor")
        .body(
            Map.of(
                "definition", valid.get("definition"),
                "test_cases", valid.get("test_cases"),
                "justification", "curta"))
        .post(PATH)
        .then()
        .statusCode(400);

    // caso de teste que não passa → 422, nada gravado
    actor(TENANT, "gestor.joao", "gestor")
        .body(body(withReviewRule(), cases("")))
        .post(PATH)
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("detail", startsWith("casos de teste não passaram"))
        .body("errors.field", hasItem("test_cases[0]"));
    // sem casos de teste → 422
    actor(TENANT, "gestor.joao", "gestor")
        .body(body(withReviewRule(), Outbox.MAPPER.createArrayNode()))
        .post(PATH)
        .then()
        .statusCode(422)
        .body("errors.field", hasItem("test_cases"));
    // jsonb inválido: operador fora da gramática, fato desconhecido, kind errado
    ObjectNode bad = withReviewRule();
    ((ObjectNode) bad.get("rules").get(0).get("when").get("all").get(0)).put("op", "regex");
    ((ObjectNode) bad.get("rules").get(2).get("when")).put("fact", "cnes_registred");
    bad.put("kind", "decision_table");
    actor(TENANT, "gestor.joao", "gestor")
        .body(body(bad, cases("\"bpa_c_review\"")))
        .post(PATH)
        .then()
        .statusCode(422)
        .body("detail", equalTo("definição de regra inválida"))
        .body("errors.field", hasItem("definition.kind"))
        .body("errors.field", hasItem("definition.rules[0].when.all[0].op"))
        .body("errors.field", hasItem("definition.rules[2].when.fact"));
    assertThat(status("2")).isNull();

    // válido → v2 ativa no tenant
    actor(TENANT, "gestor.joao", "gestor")
        .body(valid)
        .post(PATH)
        .then()
        .statusCode(201)
        .body("id", startsWith("rv_"))
        .body("rule_set", equalTo("production-validation"))
        .body("version", equalTo("2"))
        .body("label", equalTo("production-validation/2"))
        .body("previous_label", equalTo("production-validation/1"))
        .body("status", equalTo("active"))
        .body("test_cases_count", equalTo(2))
        .body("approved_by", equalTo("gestor.joao"));

    // a pré-auditoria do tenant passa a usar a v2 (nova regra dispara)
    String cnes = newUnit(TENANT);
    actor(TENANT, "connector-producao", "operador_integracao")
        .body(
            record(
                "PROD-RULE-" + System.nanoTime(), "bpa_c", cnes, "515105", "0101010010", 5, null))
        .post("/api/v1/production/records")
        .then()
        .statusCode(201)
        .body("rule_version", equalTo("production-validation/2"))
        .body("issues.rule_id", hasItem("bpa_c_review"));

    // admin_municipal ativa a v3 → revoga a v2 do tenant; a global v1 permanece ativa
    actor(TENANT, "admin.maria", "admin_municipal")
        .body(body(definitionV1(), cases("")))
        .post(PATH)
        .then()
        .statusCode(201)
        .body("version", equalTo("3"))
        .body("previous_label", equalTo("production-validation/2"));
    assertThat(status("2")).isEqualTo("revoked");
    assertThat(status("3")).isEqualTo("active");
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "select status from platform.rule_version where id = 'rv_production_validation_1'");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("active");
    }
    // auditoria da ativação com a justificativa
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "select count(*) from audit.audit_log where tenant_id = ?"
                    + " and action = 'production.rule.version_activated'")) {
      ps.setString(1, TENANT);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(2);
      }
    }
  }
}
