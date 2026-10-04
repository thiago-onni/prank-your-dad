package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RedactionTest {

  @Test
  void restrictedScopeRemovesContactData() {
    String id =
        clinician()
            .body(patientJson(randomCns(), "Redacao", "Teste", "1995-05-05"))
            .post(FHIR + "/Patient")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");

    // leitura completa
    as("full", "user/Patient.read", TENANT_A)
        .get(FHIR + "/Patient/" + id)
        .then()
        .statusCode(200)
        .body("telecom", hasSize(2))
        .body("address", hasSize(1))
        .body("meta.tag", nullValue());

    // leitura restrita (escopo granular) → sem telecom/address, com tag REDACTED
    as("restricted", "user/Patient.rs", TENANT_A)
        .get(FHIR + "/Patient/" + id)
        .then()
        .statusCode(200)
        .body("telecom", nullValue())
        .body("address", nullValue())
        .body("meta.tag[0].code", equalTo("REDACTED"))
        .body("identifier", notNullValue());

    // também em busca
    as("restricted", "user/Patient.rs", TENANT_A)
        .queryParam("_id", id)
        .get(FHIR + "/Patient")
        .then()
        .statusCode(200)
        .body("entry[0].resource.telecom", nullValue());
  }
}
