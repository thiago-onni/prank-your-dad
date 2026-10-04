package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PatientCrudTest {

  @Test
  void createReadUpdateWithVersioningAndEtags() {
    String cns = randomCns();
    Response created =
        clinician()
            .body(patientJson(cns, "Oliveira", "Joana", "1990-01-01"))
            .post(FHIR + "/Patient")
            .then()
            .statusCode(201)
            .header("ETag", "W/\"1\"")
            .header("Location", notNullValue())
            .body("meta.versionId", equalTo("1"))
            .body("meta.lastUpdated", notNullValue())
            .extract()
            .response();
    String id = created.jsonPath().getString("id");
    assertThat(created.header("Location")).endsWith("/Patient/" + id + "/_history/1");

    // read + If-None-Match
    clinician()
        .get(FHIR + "/Patient/" + id)
        .then()
        .statusCode(200)
        .header("ETag", "W/\"1\"")
        .header("Last-Modified", notNullValue())
        .body("id", equalTo(id));
    clinician()
        .header("If-None-Match", "W/\"1\"")
        .get(FHIR + "/Patient/" + id)
        .then()
        .statusCode(304);

    // update with correct If-Match
    String updated =
        created.asString().replace("\"given\":[\"Joana\"]", "\"given\":[\"Joana\",\"Maria\"]");
    clinician()
        .header("If-Match", "W/\"1\"")
        .body(updated)
        .put(FHIR + "/Patient/" + id)
        .then()
        .statusCode(200)
        .header("ETag", "W/\"2\"")
        .body("meta.versionId", equalTo("2"));

    // stale If-Match → 412
    clinician()
        .header("If-Match", "W/\"1\"")
        .body(updated)
        .put(FHIR + "/Patient/" + id)
        .then()
        .statusCode(412)
        .body("issue[0].code", equalTo("conflict"));

    // vread both versions
    clinician()
        .get(FHIR + "/Patient/" + id + "/_history/1")
        .then()
        .statusCode(200)
        .header("ETag", "W/\"1\"")
        .body("meta.versionId", equalTo("1"));
    clinician()
        .get(FHIR + "/Patient/" + id + "/_history/2")
        .then()
        .statusCode(200)
        .body("meta.versionId", equalTo("2"))
        .body("name[0].given", hasSize(2));
    clinician().get(FHIR + "/Patient/" + id + "/_history/9").then().statusCode(404);

    // history
    clinician()
        .get(FHIR + "/Patient/" + id + "/_history")
        .then()
        .statusCode(200)
        .body("type", equalTo("history"))
        .body("total", equalTo(2))
        .body("entry[0].resource.meta.versionId", equalTo("2"))
        .body("entry[1].resource.meta.versionId", equalTo("1"));
  }

  @Test
  void updateAsCreateAndIdMismatch() {
    String id = "pat-" + randomCns();
    String body = patientJson(randomCns(), "Costa", "Rui", "1985-07-07");
    clinician()
        .body(body)
        .put(FHIR + "/Patient/" + id)
        .then()
        .statusCode(201)
        .header("ETag", "W/\"1\"")
        .body("id", equalTo(id));
    String wrongId =
        body.replace(
            "\"resourceType\": \"Patient\",", "\"resourceType\": \"Patient\", \"id\": \"other\",");
    clinician()
        .body(wrongId)
        .put(FHIR + "/Patient/" + id)
        .then()
        .statusCode(400)
        .body("issue[0].expression[0]", equalTo("Patient.id"));
    clinician()
        .header("If-Match", "W/\"1\"")
        .body(body)
        .put(FHIR + "/Patient/does-not-exist-" + id)
        .then()
        .statusCode(412);
  }

  @Test
  void unknownResourceIs404() {
    clinician()
        .get(FHIR + "/Patient/does-not-exist")
        .then()
        .statusCode(404)
        .body("resourceType", equalTo("OperationOutcome"))
        .body("issue[0].code", equalTo("not-found"));
  }
}
