package br.gov.sus.nexus.core.platform;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.integration;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import br.gov.sus.nexus.core.platform.security.AuthorizationPolicy;
import br.gov.sus.nexus.core.platform.security.OpaAuthorizationPolicy;
import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Perfil {@code sus.authz.mode=opa}: a timeline consulta o OPA (WireMock) e aplica obrigações. */
@QuarkusTest
@TestProfile(OpaProfileTest.Profile.class)
@QuarkusTestResource(value = OpaProfileTest.OpaMock.class, restrictToAnnotatedClass = true)
public class OpaProfileTest {

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("sus.authz.mode", "opa");
    }
  }

  public static class OpaMock implements QuarkusTestResourceLifecycleManager {
    public static WireMockServer server;

    @Override
    public Map<String, String> start() {
      server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
      server.start();
      return Map.of("sus.authz.opa-url", server.baseUrl());
    }

    @Override
    public void stop() {
      if (server != null) {
        server.stop();
      }
    }
  }

  @Inject AuthorizationPolicy policy;
  @Inject Bus bus;

  @Test
  void opaPolicyIsSelectedAndObligationsAreApplied() {
    assertThat(policy).isInstanceOf(OpaAuthorizationPolicy.class);
    WireMockServer opa = OpaMock.server;
    opa.resetAll();
    opa.stubFor(
        post(urlEqualTo("/v1/data/sus/authz/decision"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"result\":{\"allow\":true,\"reasons\":[\"acs.read_microarea\"],"
                            + "\"obligations\":{\"mask_identifiers\":true,\"redact_fields\":[\"summary\",\"diagnoses\"],"
                            + "\"log_access\":true},\"policy_version\":\"1.0.0\"}}")));

    String citizenId =
        integration(TENANT_A)
            .body(
                Registration.of("OPA Perfil", LocalDate.of(1990, 1, 1))
                    .territory("1234567", "0000123456", "03")
                    .build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");
    bus.relayAndDeliver();
    Await.until(
        "timeline projetada",
        () ->
            !Api.as(TENANT_A, "acs.rita", "acs")
                .header("X-Test-Cnes", "1234567")
                .header("X-Test-Microareas", "03")
                .get("/api/v1/citizens/" + citizenId + "/timeline")
                .jsonPath()
                .getList("items")
                .isEmpty());
    Api.as(TENANT_A, "acs.rita", "acs")
        .header("X-Test-Cnes", "1234567")
        .header("X-Test-Microareas", "03")
        .get("/api/v1/citizens/" + citizenId + "/timeline")
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].summary", nullValue())
        .body("items[0].detail_ref", nullValue());
    opa.verify(
        postRequestedFor(urlEqualTo("/v1/data/sus/authz/decision"))
            .withRequestBody(matchingJsonPath("$.input.subject.cnes[0]", equalTo("1234567")))
            .withRequestBody(matchingJsonPath("$.input.subject.microareas[0]", equalTo("03")))
            .withRequestBody(matchingJsonPath("$.input.subject.roles[0]", equalTo("acs")))
            .withRequestBody(matchingJsonPath("$.input.resource.domain", equalTo("identity")))
            .withRequestBody(matchingJsonPath("$.input.action", equalTo("read"))));

    opa.stubFor(
        post(urlEqualTo("/v1/data/sus/authz/decision"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"result\":{\"allow\":false,\"reasons\":[\"gestor_individual_data\"],\"obligations\":{}}}")));
    Api.gestor(TENANT_A).get("/api/v1/citizens/" + citizenId + "/timeline").then().statusCode(403);
    Api.as(TENANT_A, "acs.rita", "acs")
        .get("/api/v1/citizens/" + citizenId + "/timeline")
        .then()
        .statusCode(200)
        .body("items", hasSize(0));

    // OPA fora do ar ⇒ fail-closed (nenhum evento visível)
    opa.stop();
    try {
      Api.as(TENANT_A, "acs.rita", "acs")
          .get("/api/v1/citizens/" + citizenId + "/timeline")
          .then()
          .statusCode(200)
          .body("items", hasSize(0));
    } finally {
      opa.start();
    }
  }
}
