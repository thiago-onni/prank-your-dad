package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static br.gov.sus.nexus.fhir.http.ResultsAndDocumentsTest.create;
import static org.hamcrest.Matchers.equalTo;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** {@code PATCH} com JSON Patch (RFC 6902): validação completa, If-Match e restrições. */
@QuarkusTest
class PatchTest {

  @Test
  void jsonPatchUpdatesWithValidationAndConcurrency() {
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Patch", "Sol", "1960-10-10"));
    String obs = create(clinician(), "Observation", clinicalJson("observation.json", patient));
    String url = FHIR + "/Observation/" + obs;

    clinician()
        .contentType(FhirConstants.MEDIA_TYPE_JSON_PATCH)
        .body(
            "[{\"op\":\"test\",\"path\":\"/status\",\"value\":\"final\"},"
                + "{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"amended\"},"
                + "{\"op\":\"remove\",\"path\":\"/note\"}]")
        .patch(url)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .header("ETag", "W/\"2\"")
        .body("status", equalTo("amended"))
        .body("note", org.hamcrest.Matchers.nullValue());

    // If-Match desatualizado → 412
    clinician()
        .contentType(FhirConstants.MEDIA_TYPE_JSON_PATCH)
        .header("If-Match", "W/\"1\"")
        .body("[{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"final\"}]")
        .patch(url)
        .then()
        .statusCode(412);
    // test falho / caminho inexistente → 422
    clinician()
        .contentType(FhirConstants.MEDIA_TYPE_JSON_PATCH)
        .body("[{\"op\":\"test\",\"path\":\"/status\",\"value\":\"final\"}]")
        .patch(url)
        .then()
        .statusCode(422);
    // resultado inválido (sem code) → 422 pelo pipeline de validação
    clinician()
        .contentType(FhirConstants.MEDIA_TYPE_JSON_PATCH)
        .body("[{\"op\":\"remove\",\"path\":\"/code/coding\"}]")
        .patch(url)
        .then()
        .statusCode(422);
    // id não pode mudar; patch malformado → 400
    clinician()
        .contentType(FhirConstants.MEDIA_TYPE_JSON_PATCH)
        .body("[{\"op\":\"replace\",\"path\":\"/id\",\"value\":\"OUTRO\"}]")
        .patch(url)
        .then()
        .statusCode(422);
    clinician()
        .contentType(FhirConstants.MEDIA_TYPE_JSON_PATCH)
        .body("{\"op\":\"remove\"}")
        .patch(url)
        .then()
        .statusCode(400);
    // escopo de leitura não basta; inexistente → 404
    as("reader", "user/*.read", TENANT_A)
        .contentType(FhirConstants.MEDIA_TYPE_JSON_PATCH)
        .body("[]")
        .patch(url)
        .then()
        .statusCode(403);
    clinician()
        .contentType(FhirConstants.MEDIA_TYPE_JSON_PATCH)
        .body("[]")
        .patch(FHIR + "/Observation/NOPE77")
        .then()
        .statusCode(404);
  }
}
