package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.Matchers.hasItem;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.projection.CoreWireMockResource;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Escrita direta por parceiro (escopo de sistema): FHIR → canônico → core (WireMock); o recurso só
 * é gravado depois da resposta do core.
 */
@QuarkusTest
@QuarkusTestResource(CoreWireMockResource.class)
class DirectWriteTest {

  static RequestSpecification partner() {
    return as(
        "partner-lis",
        "system/Patient.write system/Patient.read system/ServiceRequest.write"
            + " system/ServiceRequest.read",
        TENANT_A);
  }

  @BeforeEach
  void stubToken() {
    WireMockServer wm = CoreWireMockResource.server();
    wm.resetAll();
    wm.stubFor(
        post(urlEqualTo("/auth/token"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"tok-123\",\"expires_in\":300}")));
  }

  private static void stubCitizens(int status, String body) {
    CoreWireMockResource.server()
        .stubFor(
            post(urlEqualTo("/api/v1/citizens"))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
  }

  @Test
  void patientCreatedInCoreIsStoredWithResolvedId() {
    stubCitizens(
        201,
        "{\"municipal_citizen_id\":\"cit_01J0000000000000000000DW01\",\"classification\":\"new\","
            + "\"method\":\"deterministic\"}");
    String cns = randomCns();
    partner()
        .body(patientJson(cns, "Parceiro", "Lia", "1991-01-01"))
        .post(FHIR + "/Patient")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .header(
            "Location", Matchers.containsString("/Patient/01J0000000000000000000DW01/_history/1"))
        .body("id", Matchers.equalTo("01J0000000000000000000DW01"))
        .body("meta.tag.code", hasItem(FhirConstants.TAG_DIRECT_WRITE))
        .body(
            "identifier.find { it.system == '"
                + FhirConstants.SYSTEM_MUNICIPAL_CITIZEN_ID
                + "' }.value",
            Matchers.equalTo("cit_01J0000000000000000000DW01"));
    CoreWireMockResource.server()
        .verify(
            postRequestedFor(urlEqualTo("/api/v1/citizens"))
                .withHeader("Authorization", equalTo("Bearer tok-123"))
                .withHeader("X-Tenant-Id", equalTo(TENANT_A))
                .withRequestBody(
                    matchingJsonPath("$.demographics.birthdate", equalTo("1991-01-01")))
                .withRequestBody(
                    matchingJsonPath("$.identifiers[?(@.system == 'CNS')].value", equalTo(cns)))
                .withRequestBody(matchingJsonPath("$.source.system", equalTo("FHIR"))));
    clinician().get(FHIR + "/Patient/01J0000000000000000000DW01").then().statusCode(200);
  }

  @Test
  void pendingIdentityAnswers202AndTagsResource() {
    stubCitizens(
        202,
        "{\"municipal_citizen_id\":\"cit_01J0000000000000000000DW02\",\"classification\":\"pending\","
            + "\"method\":\"probabilistic\",\"merge_case_id\":\"mc_1\"}");
    partner()
        .body(patientJson(randomCns(), "Pendente", "Max", "1992-02-02"))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(202)
        .body("resourceType", Matchers.equalTo("OperationOutcome"))
        .body("issue[0].severity", Matchers.equalTo("information"));
    clinician()
        .get(FHIR + "/Patient/01J0000000000000000000DW02")
        .then()
        .statusCode(200)
        .body("meta.tag.code", hasItem(FhirConstants.TAG_PENDING_IDENTITY));
  }

  @Test
  void coreRejectionIs422AndNothingIsStored() {
    stubCitizens(422, "{\"title\":\"Identificador inválido\",\"status\":422}");
    partner()
        .body(patientJson(randomCns(), "Recusado", "Noa", "1993-03-03"))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(422)
        .body("issue[0].diagnostics", Matchers.containsString("Identificador inválido"));
    stubCitizens(503, "{}");
    partner()
        .body(patientJson(randomCns(), "Fora", "Oto", "1993-03-03"))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(502);
    // PUT em id diferente do resolvido pelo core → 422
    stubCitizens(
        200,
        "{\"municipal_citizen_id\":\"cit_01J0000000000000000000DW09\",\"classification\":\"confirmed\","
            + "\"method\":\"deterministic\"}");
    partner()
        .body(patientJson(randomCns(), "Outro", "Pia", "1993-03-03"))
        .put(FHIR + "/Patient/01J0000000000000000000XXXX")
        .then()
        .statusCode(422);
  }

  @Test
  void examServiceRequestGoesToCoreOtherwise422() {
    CoreWireMockResource.server()
        .stubFor(
            post(urlEqualTo("/api/v1/exams/orders"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            "{\"id\":\"exo_01J0000000000000000000DW05\",\"citizen_id\":\"cit_X\","
                                + "\"status\":\"requested\"}")));
    String lab =
        clinicalJson("servicerequest.json", "01HZX4Y5K6M7N8P9Q0R1S2T3U4")
            .replace(
                "\"system\": \"http://sus-nexus.gov.br/fhir/CodeSystem/regulation-kind\", \"code\": \"consultation\"",
                "\"system\": \"http://snomed.info/sct\", \"code\": \"108252007\"")
            .replace("0301010072", "0202010503");
    partner()
        .body(lab)
        .post(FHIR + "/ServiceRequest")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .body("id", Matchers.equalTo("01J0000000000000000000DW05"))
        .body("meta.tag.code", hasItem(FhirConstants.TAG_DIRECT_WRITE));
    CoreWireMockResource.server()
        .verify(
            postRequestedFor(urlEqualTo("/api/v1/exams/orders"))
                .withRequestBody(matchingJsonPath("$.category", equalTo("laboratory")))
                .withRequestBody(matchingJsonPath("$.exam_code", equalTo("0202010503")))
                .withRequestBody(
                    matchingJsonPath(
                        "$.citizen_ref.municipal_citizen_id",
                        equalTo("cit_01HZX4Y5K6M7N8P9Q0R1S2T3U4"))));

    // regulação (categoria não-exame) → 422 sem chamar o core
    partner()
        .body(clinicalJson("servicerequest.json", "01HZX4Y5K6M7N8P9Q0R1S2T3U4"))
        .post(FHIR + "/ServiceRequest")
        .then()
        .statusCode(422)
        .body("issue[0].expression[0]", Matchers.equalTo("ServiceRequest.category"));
    CoreWireMockResource.server().verify(1, postRequestedFor(urlEqualTo("/api/v1/exams/orders")));
  }

  @Test
  void userScopesKeepPlainWrite() {
    // escopo user/ não passa pela escrita direta (sem chamada ao core)
    clinician()
        .body(patientJson(randomCns(), "Usuario", "Rui", "1994-04-04"))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(201);
    CoreWireMockResource.server().verify(0, postRequestedFor(urlEqualTo("/api/v1/citizens")));
  }
}
