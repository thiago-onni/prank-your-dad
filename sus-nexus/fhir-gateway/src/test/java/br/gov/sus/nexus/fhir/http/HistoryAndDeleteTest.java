package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_B;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static br.gov.sus.nexus.fhir.http.ResultsAndDocumentsTest.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DELETE lógico (410) e {@code _history} de tipo/sistema com {@code _since}/cursor. */
@QuarkusTest
class HistoryAndDeleteTest {

  @Test
  void softDeleteAnswers410AndKeepsHistory() {
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Apagar", "Gil", "1955-05-05"));
    String obs = create(clinician(), "Observation", clinicalJson("observation.json", patient));

    as("reader", "user/*.read", TENANT_A)
        .delete(FHIR + "/Observation/" + obs)
        .then()
        .statusCode(403);
    clinician()
        .delete(FHIR + "/Observation/" + obs)
        .then()
        .statusCode(204)
        .header("ETag", "W/\"2\"");
    clinician()
        .get(FHIR + "/Observation/" + obs)
        .then()
        .statusCode(410)
        .body("issue[0].code", equalTo("deleted"));
    clinician().get(FHIR + "/Observation/" + obs + "/_history/2").then().statusCode(410);
    clinician().get(FHIR + "/Observation/" + obs + "/_history/1").then().statusCode(200);
    clinician()
        .queryParam("_id", obs)
        .get(FHIR + "/Observation")
        .then()
        .statusCode(200)
        .body("entry", nullValue());
    clinician()
        .get(FHIR + "/Observation/" + obs + "/_history")
        .then()
        .statusCode(200)
        .body("entry", hasSize(2))
        .body("entry[0].request.method", equalTo("DELETE"))
        .body("entry[0].resource", nullValue());
    // idempotente; inexistente → 404; outro tenant → 404
    clinician().delete(FHIR + "/Observation/" + obs).then().statusCode(204);
    clinician().delete(FHIR + "/Observation/NOPE123").then().statusCode(404);
    as("b", "user/*.write", TENANT_B).delete(FHIR + "/Patient/" + patient).then().statusCode(404);
    // tipos somente leitura não aceitam delete
    clinician().delete(FHIR + "/AuditEvent/x1").then().statusCode(405);
    // update após delete recria (201)
    clinician()
        .body(clinicalJson("observation.json", patient))
        .put(FHIR + "/Observation/" + obs)
        .then()
        .statusCode(201)
        .header("ETag", "W/\"3\"");
  }

  @Test
  void typeAndSystemHistory() {
    Instant start =
        Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Historia", "Hugo", "1950-01-01"));
    String obs = create(clinician(), "Observation", clinicalJson("observation.json", patient));
    clinician()
        .body(clinicalJson("observation.json", patient).replace("\"final\"", "\"amended\""))
        .put(FHIR + "/Observation/" + obs)
        .then()
        .statusCode(200);

    JsonPath page =
        clinician()
            .queryParam("_since", start.toString())
            .queryParam("_count", "1")
            .get(FHIR + "/Observation/_history")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("type", equalTo("history"))
            .body("entry", hasSize(1))
            .extract()
            .jsonPath();
    assertThat(page.getString("entry[0].resource.id")).isEqualTo(obs);
    assertThat(page.getString("entry[0].resource.meta.versionId")).isEqualTo("2");
    String next = page.getString("link.find { it.relation == 'next' }.url");
    assertThat(next).contains("/Observation/_history?_cursor=");
    clinician()
        .queryParam("_cursor", next.substring(next.indexOf("_cursor=") + 8))
        .get(FHIR + "/Observation/_history")
        .then()
        .statusCode(200)
        .body("entry[0].resource.meta.versionId", equalTo("1"));

    // sistema: inclui Patient e Observation do período; não inclui AuditEvent (sem history)
    List<String> types =
        clinician()
            .queryParam("_since", start.toString())
            .queryParam("_count", "200")
            .get(FHIR + "/_history")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("entry.resource.resourceType");
    assertThat(types).contains("Patient", "Observation").doesNotContain("AuditEvent");

    // escopo só de Patient filtra o histórico de sistema
    List<String> onlyPatient =
        as("p", "user/Patient.read", TENANT_A)
            .queryParam("_since", start.toString())
            .get(FHIR + "/_history")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("entry.resource.resourceType");
    assertThat(onlyPatient).isNotEmpty().allMatch("Patient"::equals);

    clinician().queryParam("foo", "1").get(FHIR + "/_history").then().statusCode(400);
    clinician()
        .queryParam("_since", "ontem")
        .get(FHIR + "/Observation/_history")
        .then()
        .statusCode(400);
    as("x", "user/Organization.read", TENANT_A)
        .get(FHIR + "/Observation/_history")
        .then()
        .statusCode(403);
  }
}
