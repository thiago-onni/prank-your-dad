package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_B;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SecurityAndTenantTest {

  private String createInTenantA() {
    return clinician()
        .body(patientJson(randomCns(), "Tenant", "Um", "2001-01-01"))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  @Test
  void unauthenticatedIs401() {
    RestAssured.given()
        .accept("application/fhir+json")
        .get(FHIR + "/Patient")
        .then()
        .statusCode(401)
        .body("issue[0].code", equalTo("login"));
  }

  @Test
  void missingScopeIs403() {
    as("reader", "user/*.read", TENANT_A)
        .body(patientJson(randomCns(), "Sem", "Escopo", "2001-01-01"))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(403)
        .body("issue[0].code", equalTo("forbidden"));
    as("other", "user/Organization.read", TENANT_A).get(FHIR + "/Patient").then().statusCode(403);
    as("no-tenant", "user/*.read", "").get(FHIR + "/Patient").then().statusCode(403);
  }

  @Test
  void tenantIsolation() {
    String id = createInTenantA();
    as("user-b", "user/*.read", TENANT_B).get(FHIR + "/Patient/" + id).then().statusCode(404);
    as("user-b", "user/*.read", TENANT_B)
        .queryParam("_id", id)
        .get(FHIR + "/Patient")
        .then()
        .statusCode(200)
        .body("entry", nullValue());
    as("user-b", "user/*.read", TENANT_B)
        .get(FHIR + "/Patient/" + id + "/_history")
        .then()
        .statusCode(404);
    clinician().get(FHIR + "/Patient/" + id).then().statusCode(200);
  }

  @Test
  void patientContextIsLimitedToOwnPatient() {
    String mine = createInTenantA();
    String other = createInTenantA();
    as("citizen", "patient/*.read", TENANT_A)
        .header(FhirConstants.HEADER_TEST_PATIENT, mine)
        .get(FHIR + "/Patient/" + mine)
        .then()
        .statusCode(200);
    as("citizen", "patient/*.read", TENANT_A)
        .header(FhirConstants.HEADER_TEST_PATIENT, mine)
        .get(FHIR + "/Patient/" + other)
        .then()
        .statusCode(403);
    as("citizen", "patient/*.read", TENANT_A)
        .header(FhirConstants.HEADER_TEST_PATIENT, mine)
        .queryParam("name", "Tenant")
        .get(FHIR + "/Patient")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(mine));
    as("citizen", "patient/*.read", TENANT_A).get(FHIR + "/Patient/" + mine).then().statusCode(403);
  }
}
