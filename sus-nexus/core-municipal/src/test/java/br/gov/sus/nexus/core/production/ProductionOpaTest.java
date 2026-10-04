package br.gov.sus.nexus.core.production;

import static br.gov.sus.nexus.core.production.ProductionFlowTest.actor;
import static br.gov.sus.nexus.core.production.ProductionFlowTest.competence;
import static br.gov.sus.nexus.core.production.ProductionFlowTest.newUnit;
import static br.gov.sus.nexus.core.production.ProductionFlowTest.record;
import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import br.gov.sus.nexus.core.platform.OpaProfileTest;
import br.gov.sus.nexus.core.platform.security.AuthorizationPolicy;
import br.gov.sus.nexus.core.platform.security.OpaAuthorizationPolicy;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Produção com {@code sus.authz.mode=opa} (OPA = WireMock): cada ação consulta {@code
 * /v1/data/sus/authz/decision} com a ação do contrato ({@code register_record}, {@code
 * create_batch}, {@code approve_batch} com {@code resource.created_by}, ...), finalidade {@code
 * production_audit} por padrão; negação vira 403 problem+json (quatro olhos com tipo próprio); OPA
 * fora do ar ⇒ 403 (fail-closed); o serviço mantém o quatro olhos mesmo se a política permitir.
 */
@QuarkusTest
@TestProfile(OpaProfileTest.Profile.class)
@QuarkusTestResource(value = OpaProfileTest.OpaMock.class, restrictToAnnotatedClass = true)
class ProductionOpaTest {

  static final String DECISION = "/v1/data/sus/authz/decision";
  static final String ALLOW =
      "{\"result\":{\"allow\":true,\"reasons\":[\"production.mock\"],"
          + "\"obligations\":{\"mask_identifiers\":true,\"redact_fields\":[],\"log_access\":true},"
          + "\"policy_version\":\"2.0.0\"}}";

  @Inject AuthorizationPolicy policy;

  static String deny(String reason) {
    return "{\"result\":{\"allow\":false,\"reasons\":[\""
        + reason
        + "\"],\"obligations\":{},\"policy_version\":\"2.0.0\"}}";
  }

  @Test
  void productionActionsConsultOpaFailClosedAndFourEyes() {
    assertThat(policy).isInstanceOf(OpaAuthorizationPolicy.class);
    WireMockServer opa = OpaProfileTest.OpaMock.server;
    opa.resetAll();
    opa.stubFor(
        post(urlEqualTo(DECISION))
            .atPriority(10)
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(ALLOW)));

    String cnes = newUnit(TENANT_A);
    // registro (sem X-Purpose-Of-Use ⇒ production_audit) consulta o OPA com register_record
    RestAssured.given()
        .contentType(ContentType.JSON)
        .header("X-Tenant-Id", TENANT_A)
        .header("X-Test-User", "connector-producao")
        .header("X-Test-Roles", "operador_integracao")
        .body(
            record("PROD-OPA-" + System.nanoTime(), "bpa_c", cnes, "515105", "0101010010", 3, null))
        .post("/api/v1/production/records")
        .then()
        .statusCode(201);
    opa.verify(
        postRequestedFor(urlEqualTo(DECISION))
            .withRequestBody(matchingJsonPath("$.input.action", equalTo("register_record")))
            .withRequestBody(
                matchingJsonPath("$.input.resource.type", equalTo("production_record")))
            .withRequestBody(matchingJsonPath("$.input.resource.domain", equalTo("production")))
            .withRequestBody(matchingJsonPath("$.input.subject.client_type", equalTo("service")))
            .withRequestBody(
                matchingJsonPath("$.input.context.purpose", equalTo("production_audit"))));

    String batchId =
        actor(TENANT_A, "auditora.carla", "auditor")
            .body(Map.of("competence", competence(), "cnes", cnes, "kind", "bpa_c"))
            .post("/api/v1/production/batches")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    opa.verify(
        postRequestedFor(urlEqualTo(DECISION))
            .withRequestBody(matchingJsonPath("$.input.action", equalTo("create_batch")))
            .withRequestBody(matchingJsonPath("$.input.subject.id", equalTo("auditora.carla"))));

    // política (mock) permite, mas o serviço mantém o quatro olhos
    actor(TENANT_A, "auditora.carla", "auditor")
        .body(Map.of("justification", "Conferido pela própria auditora que gerou"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(403)
        .contentType("application/problem+json")
        .body("type", org.hamcrest.Matchers.equalTo("urn:sus-nexus:problem:four-eyes"));
    opa.verify(
        postRequestedFor(urlEqualTo(DECISION))
            .withRequestBody(matchingJsonPath("$.input.action", equalTo("approve_batch")))
            .withRequestBody(matchingJsonPath("$.input.resource.id", equalTo(batchId)))
            .withRequestBody(
                matchingJsonPath("$.input.resource.created_by", equalTo("auditora.carla"))));

    // negação de quatro olhos pela política → 403 four-eyes
    opa.stubFor(
        post(urlEqualTo(DECISION))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.input.action", equalTo("approve_batch")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(deny("production_four_eyes_creator_cannot_approve"))));
    actor(TENANT_A, "gestor.joao", "gestor")
        .body(Map.of("justification", "Conferido com o relatório de produção"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(403)
        .body("type", org.hamcrest.Matchers.equalTo("urn:sus-nexus:problem:four-eyes"));

    // demais negações → 403 forbidden com os motivos da política
    opa.stubFor(
        post(urlEqualTo(DECISION))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.input.action", equalTo("read")))
            .withRequestBody(matchingJsonPath("$.input.resource.type", equalTo("production_batch")))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(deny("production_purpose_not_allowed"))));
    actor(TENANT_A, "gestor.joao", "gestor")
        .get("/api/v1/production/batches/" + batchId)
        .then()
        .statusCode(403)
        .body("type", org.hamcrest.Matchers.equalTo("urn:sus-nexus:problem:forbidden"))
        .body("detail", containsString("production_purpose_not_allowed"));

    // aprovação por outra pessoa (política permite) → 200
    opa.resetMappings();
    opa.stubFor(
        post(urlEqualTo(DECISION))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(ALLOW)));
    actor(TENANT_A, "gestor.joao", "gestor")
        .body(Map.of("justification", "Conferido com o relatório de produção"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(200);

    // OPA fora do ar ⇒ fail-closed (403) nas ações de produção
    opa.stop();
    try {
      actor(TENANT_A, "auditora.carla", "auditor")
          .get("/api/v1/production/issues")
          .then()
          .statusCode(403)
          .body("detail", containsString("opa_unavailable"));
      actor(TENANT_A, "auditora.carla", "auditor")
          .body(Map.of())
          .post("/api/v1/production/batches/" + batchId + "/export")
          .then()
          .statusCode(403);
    } finally {
      opa.start();
    }
  }
}
