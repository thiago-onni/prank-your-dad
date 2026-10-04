package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Projeção pelos endpoints internos dos 5 mapeadores: idempotência, Provenance e resolução por
 * CNES.
 */
@QuarkusTest
class ClinicalProjectionTest {

  static final String ORG = "01HZX4Y5K6M7N8P9Q0R1S2T3W1";
  static final String PATIENT = "01HZX4Y5K6M7N8P9Q0R1S2T3U4";

  private static RequestSpecification core() {
    return as("core-municipal", "system/*.write system/*.read", TENANT_A)
        .contentType("application/json");
  }

  @BeforeEach
  void projectHealthUnit() {
    // unidade CNES 2112345 (idempotente entre testes)
    core()
        .body(fixture("canonical-health-unit.json"))
        .post("/internal/projections/health-unit")
        .then()
        .statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(201)));
  }

  private Response project(String path, String body, int expectedStatus) {
    return core()
        .body(body)
        .post("/internal/projections/" + path)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(expectedStatus)
        .extract()
        .response();
  }

  private static void assertProvenance(String target, int expectedCount) {
    as("auditor", "user/Provenance.read", TENANT_A)
        .queryParam("target", target)
        .get(FHIR + "/Provenance")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("entry", hasSize(expectedCount));
  }

  @Test
  void appointment() {
    // Location com CNES 2112345 para resolução do participante
    String location =
        clinician()
            .body(
                "{\"resourceType\":\"Location\",\"meta\":{\"profile\":[\"https://br-core.saude.gov.br/fhir/StructureDefinition/BRCoreLocation\"]},"
                    + "\"identifier\":[{\"system\":\""
                    + FhirConstants.SYSTEM_CNES
                    + "\",\"value\":\"2112345\"}],\"status\":\"active\",\"name\":\"UBS Major Prates\","
                    + "\"managingOrganization\":{\"reference\":\"Organization/"
                    + ORG
                    + "\"}}")
            .post(FHIR + "/Location")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    String json = fixture("canonical-appointment.json");
    Response r = project("appointment", json, 201);
    assertThat(r.header("X-Provenance-Location")).contains("/Provenance/");
    r.then()
        .body("resourceType", equalTo("Appointment"))
        .body("id", equalTo("01J0000000000000000000APT1"))
        .body("status", equalTo("booked"))
        .body(
            "serviceType[0].coding[0].system",
            equalTo("http://www.saude.gov.br/fhir/r4/CodeSystem/BRTabelaSUS"))
        .body("serviceType[0].coding[0].code", equalTo("0301010072"))
        .body("participant[0].actor.reference", equalTo("Patient/" + PATIENT))
        .body("participant[1].actor.reference", org.hamcrest.Matchers.startsWith("Location/"))
        .body("participant[1].actor.identifier.value", equalTo("2112345"))
        .body("participant[2].actor.identifier.value", equalTo("prof_01J0000000000000000000PRF1"))
        .body("basedOn[0].reference", equalTo("ServiceRequest/01J0000000000000000000REG1"))
        .body(
            "extension.find { it.url == '" + FhirConstants.EXT_APPOINTMENT_KIND + "' }.valueCode",
            equalTo("regulated"))
        .body(
            "identifier.find { it.system == '"
                + FhirConstants.SYSTEM_SOURCE_RECORD_ID
                + "' }.value",
            equalTo("SISREG-AGD-778899"));

    // idempotente
    project("appointment", json, 200)
        .then()
        .header("X-Provenance-Location", nullValue())
        .header("ETag", "W/\"1\"");
    // mudança de status → nova versão + nova Provenance (com entity.what = registro de origem)
    project("appointment", json.replace("\"status\": \"booked\"", "\"status\": \"arrived\""), 200)
        .then()
        .header("ETag", "W/\"2\"")
        .body("status", equalTo("arrived"))
        .header("X-Provenance-Location", notNullValue());
    assertProvenance("Appointment/01J0000000000000000000APT1", 2);
    as("auditor", "user/Provenance.read", TENANT_A)
        .queryParam("target", "Appointment/01J0000000000000000000APT1")
        .queryParam("agent", "sus-nexus-fhir-gateway")
        .get(FHIR + "/Provenance")
        .then()
        .statusCode(200)
        .body("entry", hasSize(2))
        .body("entry[0].resource.entity[0].what.identifier.value", equalTo("SISREG-AGD-778899"));

    // visível na API FHIR com busca por paciente/data/status
    clinician()
        .queryParam("patient", "Patient/" + PATIENT)
        .queryParam("date", "2026-10-10")
        .queryParam("status", "arrived")
        .get(FHIR + "/Appointment")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1));
  }

  @Test
  void task() {
    String json = fixture("canonical-task.json");
    project("task", json, 201)
        .then()
        .body("resourceType", equalTo("Task"))
        .body("status", equalTo("in-progress"))
        .body("businessStatus.coding[0].code", equalTo("escalated"))
        .body("intent", equalTo("order"))
        .body("priority", equalTo("urgent"))
        .body("code.coding[0].system", equalTo(FhirConstants.CS_TASK_TYPE))
        .body("code.coding[0].code", equalTo("regulation_pending_document"))
        .body("for.reference", equalTo("Patient/" + PATIENT))
        .body("owner.reference", equalTo("Organization/" + ORG))
        .body("restriction.period.end", equalTo("2026-10-12T21:00:00+00:00"))
        .body("basedOn[0].reference", equalTo("ServiceRequest/01J0000000000000000000REG1"))
        .body("description", equalTo("Anexar laudo do exame de imagem à solicitação"));
    project("task", json, 200).then().header("X-Provenance-Location", nullValue());
    assertProvenance("Task/01J0000000000000000000TSK1", 1);
    clinician()
        .queryParam("business-status", "escalated")
        .queryParam("patient", PATIENT)
        .get(FHIR + "/Task")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1));
  }

  @Test
  void regulationRequest() {
    String json = fixture("canonical-regulation-request.json");
    project("regulation-request", json, 201)
        .then()
        .body("resourceType", equalTo("ServiceRequest"))
        .body("status", equalTo("active"))
        .body("intent", equalTo("order"))
        .body("priority", equalTo("asap"))
        .body("category[0].coding[0].code", equalTo("consultation"))
        .body("code.coding[0].code", equalTo("0301010072"))
        .body("subject.reference", equalTo("Patient/" + PATIENT))
        .body("requester.reference", equalTo("Organization/" + ORG))
        .body("performer[0].identifier.value", equalTo("2222222"))
        .body("performer[0].reference", nullValue())
        .body("occurrenceDateTime", equalTo("2026-10-10T13:00:00+00:00"))
        .body(
            "extension.find { it.url == '" + FhirConstants.EXT_REGULATION_STATUS + "' }.valueCode",
            equalTo("authorized"))
        .body(
            "extension.find { it.url == '"
                + FhirConstants.EXT_REGULATION_AUTHORIZED
                + "' }.valueBoolean",
            equalTo(true));
    project(
            "regulation-request",
            json.replace("\"status\": \"authorized\"", "\"status\": \"denied\""),
            200)
        .then()
        .body("status", equalTo("revoked"))
        .body(
            "extension.find { it.url == '" + FhirConstants.EXT_REGULATION_AUTHORIZED + "' }",
            nullValue());
    assertProvenance("ServiceRequest/01J0000000000000000000REG1", 2);
  }

  @Test
  void examOrder() {
    String json = fixture("canonical-exam-order.json");
    project("exam-order", json, 201)
        .then()
        .body("resourceType", equalTo("ServiceRequest"))
        .body("status", equalTo("completed"))
        .body("category[0].coding[0].system", equalTo(FhirConstants.CS_SNOMED))
        .body("category[0].coding[0].code", equalTo("108252007"))
        .body("code.coding[0].code", equalTo("0202010503"))
        .body("basedOn[0].reference", equalTo("ServiceRequest/01J0000000000000000000REG1"))
        .body("requester.reference", equalTo("Organization/" + ORG))
        .body(
            "extension.find { it.url == '" + FhirConstants.EXT_EXAM_ORDER_STATUS + "' }.valueCode",
            equalTo("reported"));
    project("exam-order", json, 200).then().header("X-Provenance-Location", nullValue());
    clinician()
        .queryParam("category", FhirConstants.CS_SNOMED + "|108252007")
        .queryParam("patient", PATIENT)
        .get(FHIR + "/ServiceRequest")
        .then()
        .statusCode(200)
        .body("entry.size()", greaterThanOrEqualTo(1));
  }

  @Test
  void encounter() {
    String json = fixture("canonical-encounter.json");
    project("encounter", json, 201)
        .then()
        .body("resourceType", equalTo("Encounter"))
        .body("status", equalTo("finished"))
        .body("class.code", equalTo("AMB"))
        .body("type[0].coding[0].code", equalTo("aps_individual"))
        .body("subject.reference", equalTo("Patient/" + PATIENT))
        .body("period.start", equalTo("2026-10-01T12:00:00+00:00"))
        .body("serviceProvider.reference", equalTo("Organization/" + ORG))
        .body("reasonCode[1].coding[0].system", equalTo(FhirConstants.CS_ICPC2))
        .body("meta.security[0].code", equalTo("R"))
        .body(
            "participant[0].individual.identifier.value",
            equalTo("prof_01J0000000000000000000PRF1"));
    project("encounter", json, 200).then().header("X-Provenance-Location", nullValue());
    assertProvenance("Encounter/01J0000000000000000000ENC1", 1);
    clinician()
        .queryParam("patient", PATIENT)
        .queryParam("class", "AMB")
        .queryParam("service-provider", "Organization/" + ORG)
        .get(FHIR + "/Encounter")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1));
  }

  @Test
  void requiresSystemScopeAndIds() {
    as("user", "user/*.write", TENANT_A)
        .contentType("application/json")
        .body(fixture("canonical-task.json"))
        .post("/internal/projections/task")
        .then()
        .statusCode(403);
    core()
        .body("{\"task_type\": \"generic\"}")
        .post("/internal/projections/task")
        .then()
        .statusCode(400);
    core()
        .body(
            fixture("canonical-appointment.json")
                .replace("\"status\": \"booked\"", "\"status\": \"weird\""))
        .post("/internal/projections/appointment")
        .then()
        .statusCode(org.hamcrest.Matchers.anyOf(equalTo(400), equalTo(422), equalTo(500)));
  }
}
