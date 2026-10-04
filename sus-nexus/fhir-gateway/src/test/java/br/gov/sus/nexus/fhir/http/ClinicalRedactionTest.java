package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomId;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ClinicalRedactionTest {

  private String create(String type, String json) {
    return clinician()
        .body(json)
        .post(FHIR + "/" + type)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  @Test
  void restrictedScopeRedactsSensitiveElements() {
    String patient = randomId();
    String condition = create("Condition", clinicalJson("condition.json", patient));
    String sr = create("ServiceRequest", clinicalJson("servicerequest.json", patient));
    String task = create("Task", clinicalJson("task.json", patient));
    String plan = create("CarePlan", clinicalJson("careplan.json", patient));
    String enc = create("Encounter", clinicalJson("encounter.json", patient));

    as("r", "user/Condition.rs", TENANT_A)
        .get(FHIR + "/Condition/" + condition)
        .then()
        .statusCode(200)
        .body("code.text", nullValue())
        .body("code.coding[0].code", equalTo("I10"))
        .body("meta.tag[0].code", equalTo("REDACTED"));
    as("r", "user/ServiceRequest.rs", TENANT_A)
        .get(FHIR + "/ServiceRequest/" + sr)
        .then()
        .statusCode(200)
        .body("reasonCode", nullValue())
        .body("code", notNullValue());
    as("r", "user/Task.rs", TENANT_A)
        .get(FHIR + "/Task/" + task)
        .then()
        .statusCode(200)
        .body("description", nullValue())
        .body("code", notNullValue());
    as("r", "user/CarePlan.rs", TENANT_A)
        .get(FHIR + "/CarePlan/" + plan)
        .then()
        .statusCode(200)
        .body("description", nullValue())
        .body("title", notNullValue());
    as("r", "user/Encounter.rs", TENANT_A)
        .get(FHIR + "/Encounter/" + enc)
        .then()
        .statusCode(200)
        .body("reasonCode", nullValue());

    // escopo completo: nada redigido
    clinician()
        .get(FHIR + "/Condition/" + condition)
        .then()
        .statusCode(200)
        .body("code.text", equalTo("Hipertensão essencial"))
        .body("meta.tag", nullValue());
  }

  @Test
  void highlyRestrictedRequiresFullScope() {
    String patient = randomId();
    String json =
        clinicalJson("condition.json", patient)
            .replace(
                "\"meta\": {",
                "\"meta\": {\"security\": [{\"system\": \""
                    + FhirConstants.CS_SENSITIVITY
                    + "\", \"code\": \"highly_restricted\"}], ");
    String id = create("Condition", json);
    String plain = create("Condition", clinicalJson("condition.json", patient));

    as("r", "user/Condition.rs", TENANT_A)
        .get(FHIR + "/Condition/" + id)
        .then()
        .statusCode(403)
        .body("issue[0].diagnostics", org.hamcrest.Matchers.containsString("highly_restricted"));
    as("r", "user/Condition.rs", TENANT_A)
        .queryParam("patient", patient)
        .get(FHIR + "/Condition")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(plain));
    as("full", "user/Condition.read", TENANT_A)
        .get(FHIR + "/Condition/" + id)
        .then()
        .statusCode(200)
        .body("meta.security[0].code", equalTo("highly_restricted"));
    as("full", "user/Condition.read", TENANT_A)
        .queryParam("patient", patient)
        .get(FHIR + "/Condition")
        .then()
        .statusCode(200)
        .body("entry", hasSize(2));
  }
}
