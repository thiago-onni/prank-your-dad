package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.await;
import static br.gov.sus.nexus.fhir.FhirTestSupport.fixture;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.projection.CoreWireMockResource;
import br.gov.sus.nexus.fhir.projection.ProjectionEventHandler;
import br.gov.sus.nexus.fhir.projection.ProjectionInboxRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Projeções FHIR-3 (resultado de exame, episódio hospitalar, plano e lacuna de cuidado) pelos
 * endpoints internos e pelos consumidores Kafka: idempotência, Provenance e vínculos.
 */
@QuarkusTest
@QuarkusTestResource(CoreWireMockResource.class)
class Fhir3ProjectionTest {

  static final String ORG = "01HZX4Y5K6M7N8P9Q0R1S2T3W1";
  static final String PATIENT = "01HZX4Y5K6M7N8P9Q0R1S2T3U4";

  @Inject
  @Connector("smallrye-in-memory")
  InMemoryConnector connector;

  @Inject ProjectionInboxRepository inbox;

  private static RequestSpecification core() {
    return as("core-municipal", "system/*.write system/*.read", TENANT_A)
        .contentType("application/json");
  }

  private static RequestSpecification reader() {
    return as("reader", "user/*.read user/DocumentReference.read user/Provenance.read", TENANT_A);
  }

  @BeforeEach
  void setUp() {
    WireMockServer wm = CoreWireMockResource.server();
    wm.resetAll();
    wm.stubFor(
        post(urlEqualTo("/auth/token"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"tok-123\",\"expires_in\":300}")));
    core()
        .body(fixture("canonical-health-unit.json"))
        .post("/internal/projections/health-unit")
        .then()
        .statusCode(Matchers.anyOf(equalTo(200), equalTo(201)));
  }

  private Response project(String path, String body, int expected) {
    return core()
        .body(body)
        .post("/internal/projections/" + path)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(expected)
        .extract()
        .response();
  }

  private static int provenanceCount(String target) {
    return reader()
        .queryParam("target", target)
        .get(FHIR + "/Provenance")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("entry")
        .size();
  }

  @Test
  void examResultProjectsReportObservationsAndDocument() {
    String json = fixture("canonical-exam-result-order.json");
    Response r = project("exam-result", json, 201);
    r.then()
        .body("resourceType", equalTo("DiagnosticReport"))
        .body("id", equalTo("01J0000000000000000000EXR3"))
        .body("status", equalTo("final"))
        .body("performer[0].reference", equalTo("Organization/" + ORG))
        .body("result", hasSize(3));
    assertThat(r.header("X-Provenance-Location")).contains("/Provenance/");

    // Observations ligadas via result e via _include
    JsonPath bundle =
        reader()
            .queryParam("patient", PATIENT)
            .queryParam("_id", "01J0000000000000000000EXR3")
            .queryParam("_include", "DiagnosticReport:result")
            .get(FHIR + "/DiagnosticReport")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(
            bundle.getList("entry.findAll { it.search.mode == 'include' }.resource.resourceType"))
        .containsExactly("Observation", "Observation", "Observation");
    reader()
        .queryParam("patient", PATIENT)
        .queryParam("value-quantity", "gt15")
        .queryParam("code", "6690-2")
        .get(FHIR + "/Observation")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.interpretation[0].coding[0].code", equalTo("A"));
    reader()
        .get(FHIR + "/DocumentReference/01J0000000000000000000EXR3-doc")
        .then()
        .statusCode(200)
        .body("content[0].attachment.data", nullValue())
        .body(
            "context.related.reference",
            Matchers.hasItem("DiagnosticReport/01J0000000000000000000EXR3"));
    assertThat(provenanceCount("DiagnosticReport/01J0000000000000000000EXR3")).isEqualTo(1);
    assertThat(provenanceCount("Observation/01J0000000000000000000EXR3-obs-1")).isEqualTo(1);

    // idempotente: nada muda, sem nova Provenance
    project("exam-result", json, 200)
        .then()
        .header("X-Provenance-Location", nullValue())
        .header("ETag", "W/\"1\"");
    assertThat(provenanceCount("DiagnosticReport/01J0000000000000000000EXR3")).isEqualTo(1);

    // inconclusivo → partial com extensão; nova versão + Provenance
    project(
            "exam-result",
            json.replace("\"status\": \"final\"", "\"status\": \"inconclusive\""),
            200)
        .then()
        .body("status", equalTo("partial"))
        .body(
            "extension.find { it.url == '" + FhirConstants.EXT_EXAM_RESULT_STATUS + "' }.valueCode",
            equalTo("inconclusive"))
        .header("X-Provenance-Location", notNullValue());
    assertThat(provenanceCount("DiagnosticReport/01J0000000000000000000EXR3")).isEqualTo(2);

    // result_id inexistente / sem resultados → 400
    core()
        .queryParam("result_id", "exr_X")
        .body(json)
        .post("/internal/projections/exam-result")
        .then()
        .statusCode(400);
  }

  @Test
  void hospitalEpisodeCarePlanAndCareGap() {
    project("hospital-episode", fixture("canonical-hospital-episode.json"), 201)
        .then()
        .body("resourceType", equalTo("Encounter"))
        .body("class.code", equalTo("IMP"))
        .body("serviceProvider.reference", equalTo("Organization/" + ORG))
        .body("hospitalization.dischargeDisposition.coding[0].code", equalTo("alt-home"))
        .body("reasonCode[0].coding[0].code", equalTo("I50.0"));
    project("hospital-episode", fixture("canonical-hospital-episode.json"), 200)
        .then()
        .header("X-Provenance-Location", nullValue());

    project("care-plan", fixture("canonical-care-plan.json"), 201)
        .then()
        .body("resourceType", equalTo("CarePlan"))
        .body("activity", hasSize(3))
        .body("contributor[0].reference", equalTo("Organization/" + ORG))
        .body("supportingInfo[0].reference", equalTo("Encounter/01J0000000000000000000HEP1"));
    assertThat(provenanceCount("CarePlan/01J0000000000000000000CPL1")).isEqualTo(1);

    project("care-gap", fixture("canonical-care-gap.json"), 201)
        .then()
        .body("resourceType", equalTo("Task"))
        .body("code.coding[0].code", equalTo("care_gap"))
        .body("focus.reference", equalTo("CarePlan/01J0000000000000000000CPL1"))
        .body("owner.reference", equalTo("Organization/" + ORG));
    reader()
        .queryParam("code", FhirConstants.CS_TASK_TYPE + "|care_gap")
        .queryParam("patient", PATIENT)
        .get(FHIR + "/Task")
        .then()
        .statusCode(200)
        .body("entry.resource.id", Matchers.hasItem("01J0000000000000000000GAP1"));

    // escopo/ids obrigatórios
    as("user", "user/*.write", TENANT_A)
        .contentType("application/json")
        .body(fixture("canonical-care-plan.json"))
        .post("/internal/projections/care-plan")
        .then()
        .statusCode(403);
    core()
        .body("{\"care_line\":\"x\"}")
        .post("/internal/projections/care-gap")
        .then()
        .statusCode(400);
  }

  @Test
  void kafkaConsumersForFhir3Topics() {
    WireMockServer wm = CoreWireMockResource.server();
    // resultado: o core devolve o pedido; o resultado do evento não está em results[] → metadados
    wm.stubFor(
        get(urlEqualTo("/api/v1/exams/orders/exo_01J0000000000000000000EXK1"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        fixture("canonical-exam-result-order.json")
                            .replace("EXO3", "EXK1")
                            .replace("\"results\": [", "\"results_ignored\": ["))));
    connector.source("exam-result").send(fixture("event-exam-result.json"));
    await(() -> inbox.alreadyProcessed(TENANT_A, "evt_01J0000000000000000000EXR1"), 15_000);
    reader()
        .get(FHIR + "/DiagnosticReport/01J0000000000000000000EXK1")
        .then()
        .statusCode(200)
        .body("status", equalTo("preliminary"))
        .body("basedOn[0].reference", equalTo("ServiceRequest/01J0000000000000000000EXK1"));

    // ADT e alta → episódio buscado no core
    String episode = fixture("canonical-hospital-episode.json").replace("HEP1", "HEPK");
    wm.stubFor(
        get(urlEqualTo("/api/v1/hospital/episodes/hep_01J0000000000000000000HEPK"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        episode
                            .replace("\"status\": \"discharged\"", "\"status\": \"admitted\"")
                            .replace("\"discharged_at\": \"2026-09-25T15:00:00-03:00\",", ""))));
    connector
        .source("hospital-adt")
        .send(
            envelope(
                "evt_01J0000000000000000000ADT1",
                "sus.hospital.adt.admitted",
                "{\"action\":\"admitted\",\"hospital_episode_id\":\"hep_01J0000000000000000000HEPK\","
                    + "\"hospital_cnes\":\"2112345\",\"episode_class\":\"inpatient\",\"status\":\"admitted\","
                    + "\"occurred_at\":\"2026-09-20T10:00:00-03:00\"}"));
    await(() -> inbox.alreadyProcessed(TENANT_A, "evt_01J0000000000000000000ADT1"), 15_000);
    reader()
        .get(FHIR + "/Encounter/01J0000000000000000000HEPK")
        .then()
        .statusCode(200)
        .body("status", equalTo("in-progress"));
    wm.stubFor(
        get(urlEqualTo("/api/v1/hospital/episodes/hep_01J0000000000000000000HEPK"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(episode)));
    connector
        .source("hospital-discharge")
        .send(
            envelope(
                "evt_01J0000000000000000000DSC1",
                "sus.hospital.discharge.completed",
                "{\"action\":\"completed\",\"hospital_episode_id\":\"hep_01J0000000000000000000HEPK\","
                    + "\"hospital_cnes\":\"2112345\",\"discharged_at\":\"2026-09-25T15:00:00-03:00\","
                    + "\"disposition\":\"home_with_care\"}"));
    await(() -> inbox.alreadyProcessed(TENANT_A, "evt_01J0000000000000000000DSC1"), 15_000);
    reader()
        .get(FHIR + "/Encounter/01J0000000000000000000HEPK")
        .then()
        .statusCode(200)
        .body("status", equalTo("finished"))
        .header("ETag", "W/\"2\"");

    // plano de cuidado
    wm.stubFor(
        get(urlEqualTo("/api/v1/careplans/cp_01J0000000000000000000CPLK"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(fixture("canonical-care-plan.json").replace("CPL1", "CPLK"))));
    connector
        .source("careplan")
        .send(
            envelope(
                "evt_01J0000000000000000000CPE1",
                "sus.careplan.created",
                "{\"action\":\"created\",\"care_plan_id\":\"cp_01J0000000000000000000CPLK\","
                    + "\"care_line\":\"hipertensao\",\"status\":\"active\",\"protocol_version\":\"2.1\"}"));
    await(() -> inbox.alreadyProcessed(TENANT_A, "evt_01J0000000000000000000CPE1"), 15_000);
    reader().get(FHIR + "/CarePlan/01J0000000000000000000CPLK").then().statusCode(200);

    // lacuna: do próprio evento (sem chamada ao core)
    connector.source("caregap").send(fixture("event-caregap.json"));
    await(() -> inbox.alreadyProcessed(TENANT_A, "evt_01J0000000000000000000GAPK"), 15_000);
    reader()
        .get(FHIR + "/Task/01J0000000000000000000GAPK")
        .then()
        .statusCode(200)
        .body("reasonCode.coding[0].code", equalTo("return_overdue"))
        .body("priority", equalTo("asap"))
        .body("status", equalTo("requested"))
        .body("owner.identifier.value", equalTo("2112345"));
    List<String> events =
        reader()
            .queryParam("target", "Task/01J0000000000000000000GAPK")
            .get(FHIR + "/Provenance")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList(
                "entry.resource.extension.flatten().findAll { it.url == '"
                    + FhirConstants.EXT_EVENT_ID
                    + "' }.valueString");
    assertThat(events).containsExactly("evt_01J0000000000000000000GAPK");
  }

  private static String envelope(String eventId, String type, String data) {
    return "{\"event_id\":\""
        + eventId
        + "\",\"event_type\":\""
        + type
        + "\",\"event_version\":\"1.0\",\"occurred_at\":\"2026-09-25T15:00:00-03:00\","
        + "\"tenant\":{\"municipality_id\":\""
        + TENANT_A
        + "\"},\"subject\":{\"municipal_citizen_id\":\"cit_"
        + PATIENT
        + "\"},\"source\":{\"system\":\"HIS\",\"connector\":\"connector-his\","
        + "\"source_record_id\":\"HIS-1\",\"cnes\":\"2112345\"},\"data\":"
        + data
        + ",\"trace\":{\"correlation_id\":\"corr-"
        + eventId
        + "\"}}";
  }

  @Test
  void topicsAreRegistered() {
    assertThat(
            List.of(
                ProjectionEventHandler.TOPIC_EXAM_RESULT,
                ProjectionEventHandler.TOPIC_HOSPITAL_ADT,
                ProjectionEventHandler.TOPIC_HOSPITAL_DISCHARGE,
                ProjectionEventHandler.TOPIC_CAREPLAN,
                ProjectionEventHandler.TOPIC_CAREGAP))
        .containsExactly(
            "sus.exam.result.v1",
            "sus.hospital.adt.v1",
            "sus.hospital.discharge.v1",
            "sus.careplan.v1",
            "sus.caregap.v1");
  }
}
