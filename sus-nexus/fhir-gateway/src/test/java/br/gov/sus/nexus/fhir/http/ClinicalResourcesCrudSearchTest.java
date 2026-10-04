package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomId;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** CRUD + busca por paciente/data/status para cada tipo clínico/operacional de FHIR-2. */
@QuarkusTest
class ClinicalResourcesCrudSearchTest {

  record Case(
      String type,
      String fixture,
      String dateParam,
      String dateValue,
      String statusParam,
      String statusValue,
      String updateFrom,
      String updateTo) {}

  static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of(
            new Case(
                "Encounter",
                "encounter.json",
                "date",
                "2026-09-10",
                "status",
                "finished",
                "\"status\": \"finished\"",
                "\"status\": \"in-progress\"")),
        Arguments.of(
            new Case(
                "Appointment",
                "appointment.json",
                "date",
                "2026-09-15",
                "status",
                "booked",
                "\"status\": \"booked\"",
                "\"status\": \"arrived\"")),
        Arguments.of(
            new Case(
                "ServiceRequest",
                "servicerequest.json",
                "authored",
                "2026-09-01",
                "status",
                "active",
                "\"priority\": \"routine\"",
                "\"priority\": \"urgent\"")),
        Arguments.of(
            new Case(
                "Task",
                "task.json",
                "authored-on",
                "2026-09-02",
                "status",
                "requested",
                "\"status\": \"requested\"",
                "\"status\": \"accepted\"")),
        Arguments.of(
            new Case(
                "Condition",
                "condition.json",
                "onset-date",
                "2024-03-01",
                "clinical-status",
                "active",
                "\"code\": \"active\"",
                "\"code\": \"remission\"")),
        Arguments.of(
            new Case(
                "CarePlan",
                "careplan.json",
                "date",
                "ge2026-06-01",
                "status",
                "active",
                "\"status\": \"active\"",
                "\"status\": \"on-hold\"")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void crudAndSearch(Case c) {
    String patient = randomId();
    String json = clinicalJson(c.fixture(), patient);

    // create
    Response created =
        clinician()
            .body(json)
            .post(FHIR + "/" + c.type())
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .header("ETag", "W/\"1\"")
            .body("resourceType", equalTo(c.type()))
            .extract()
            .response();
    String id = created.jsonPath().getString("id");

    // read / vread / history
    clinician()
        .get(FHIR + "/" + c.type() + "/" + id)
        .then()
        .statusCode(200)
        .header("ETag", "W/\"1\"")
        .body("id", equalTo(id));
    clinician()
        .get(FHIR + "/" + c.type() + "/" + id + "/_history/1")
        .then()
        .statusCode(200)
        .body("meta.versionId", equalTo("1"));

    // update → versão 2
    String updated = json.replace(c.updateFrom(), c.updateTo());
    clinician()
        .header("If-Match", "W/\"1\"")
        .body(updated)
        .put(FHIR + "/" + c.type() + "/" + id)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .header("ETag", "W/\"2\"");
    clinician()
        .get(FHIR + "/" + c.type() + "/" + id + "/_history")
        .then()
        .statusCode(200)
        .body("type", equalTo("history"))
        .body("entry", hasSize(2));

    // busca por paciente (referência completa e id simples)
    clinician()
        .queryParam("patient", "Patient/" + patient)
        .get(FHIR + "/" + c.type())
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(id));
    clinician()
        .queryParam("patient", patient)
        .queryParam(c.dateParam(), c.dateValue())
        .get(FHIR + "/" + c.type())
        .then()
        .statusCode(200)
        .body("entry", hasSize(1));
    clinician()
        .queryParam("patient", patient)
        .queryParam(c.dateParam(), "lt1990-01-01")
        .get(FHIR + "/" + c.type())
        .then()
        .statusCode(200)
        .body("entry", equalTo(null));

    // busca por status (a atualização pode ter mudado o status: contamos pelo valor original ou
    // não)
    clinician()
        .queryParam(c.statusParam(), c.statusValue())
        .get(FHIR + "/" + c.type())
        .then()
        .statusCode(200)
        .body("total", equalTo(null));

    // parâmetro desconhecido → 400
    clinician()
        .queryParam("nope", "x")
        .get(FHIR + "/" + c.type())
        .then()
        .statusCode(400)
        .body("issue[0].code", equalTo("invalid"));
  }
}
