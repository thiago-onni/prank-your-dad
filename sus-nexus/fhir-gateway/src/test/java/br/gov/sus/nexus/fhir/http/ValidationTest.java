package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.fixture;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ValidationTest {

  @Test
  void missingIdentifierIs422WithExpression() {
    clinician()
        .body(fixture("patient-missing-identifier.json"))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(422)
        .body("resourceType", equalTo("OperationOutcome"))
        .body("issue.code", hasItem("invariant"))
        .body("issue.expression.flatten()", hasItem("Patient.identifier"))
        .body("issue.diagnostics", hasItem(containsString("sus-pat-1")));
  }

  @Test
  void missingProfileIs422() {
    String noProfile =
        patientJson(randomCns(), "Lima", "Beto", "1999-09-09")
            .replace(
                "\"profile\": [\"https://br-core.saude.gov.br/fhir/StructureDefinition/BRCorePatient\"]",
                "\"tag\": [{\"code\": \"x\"}]");
    clinician()
        .body(noProfile)
        .post(FHIR + "/Patient")
        .then()
        .statusCode(422)
        .body("issue.expression.flatten()", hasItem("Patient.meta.profile"));
  }

  @Test
  void unknownProfileIs422() {
    String unknown =
        patientJson(randomCns(), "Lima", "Beto", "1999-09-09")
            .replace(
                "\"profile\": [\"https://br-core.saude.gov.br/fhir/StructureDefinition/BRCorePatient\"]",
                "\"profile\": [\"https://br-core.saude.gov.br/fhir/StructureDefinition/BRCorePatient\", \"http://example.org/StructureDefinition/Unknown\"]");
    clinician()
        .body(unknown)
        .post(FHIR + "/Patient")
        .then()
        .statusCode(422)
        .body("issue.code", hasItem("not-supported"));
  }

  @Test
  void invalidCodeIsRejected() {
    // código fora do binding required de Patient.gender: rejeitado já no parse estrito (400)
    String badGender =
        patientJson(randomCns(), "Lima", "Beto", "1999-09-09").replace("\"female\"", "\"girl\"");
    clinician()
        .body(badGender)
        .post(FHIR + "/Patient")
        .then()
        .statusCode(400)
        .body("issue[0].code", equalTo("structure"))
        .body("issue[0].diagnostics", containsString("girl"));
    // código inválido em CodeableConcept com binding required: validador oficial (422)
    String badMarital =
        patientJson(randomCns(), "Lima", "Beto", "1999-09-09")
            .replace(
                "\"gender\": \"female\",",
                "\"gender\": \"female\", \"maritalStatus\": {\"coding\": [{\"system\":"
                    + " \"http://terminology.hl7.org/CodeSystem/v3-MaritalStatus\", \"code\":"
                    + " \"ZZZ\"}]},");
    clinician()
        .body(badMarital)
        .post(FHIR + "/Patient")
        .then()
        .statusCode(422)
        .body("issue.expression.flatten()", hasItem(containsString("Patient.maritalStatus")));
  }

  @Test
  void missingRequiredElementIsReportedByOfficialValidator() {
    String noOther =
        patientJson(randomCns(), "Link", "Sem", "1999-09-09")
            .replace("\"active\": true,", "\"active\": true, \"link\": [{\"type\": \"seealso\"}],");
    clinician()
        .body(noOther)
        .post(FHIR + "/Patient")
        .then()
        .statusCode(422)
        .body("issue.expression.flatten()", hasItem(containsString("Patient.link")))
        .body("issue.diagnostics", hasItem(containsString("minimum required")));
  }

  @Test
  void malformedAndUnknownContentAre400() {
    clinician()
        .body("{not json")
        .post(FHIR + "/Patient")
        .then()
        .statusCode(400)
        .body("issue[0].code", equalTo("structure"));
    clinician()
        .body("{\"resourceType\":\"Patient\",\"foo\":1}")
        .post(FHIR + "/Patient")
        .then()
        .statusCode(400)
        .body("issue[0].code", equalTo("structure"));
  }

  @Test
  void typeMismatchWithUrlIs400() {
    clinician()
        .body(fixture("organization-cnes.json"))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(400)
        .body("issue[0].code", equalTo("invalid"));
  }

  @Test
  void validateOperationAlwaysReturnsOutcome() {
    clinician()
        .body(fixture("patient-missing-identifier.json"))
        .post(FHIR + "/Patient/$validate")
        .then()
        .statusCode(200)
        .body("resourceType", equalTo("OperationOutcome"))
        .body("issue.severity", hasItem("error"));
    clinician()
        .body(fixture("patient-brcore.json"))
        .post(FHIR + "/$validate")
        .then()
        .statusCode(200)
        .body("issue.severity", not(hasItem("error")))
        .body("issue.severity", hasItem("information"));
    clinician()
        .body(fixture("organization-cnes.json"))
        .post(FHIR + "/Organization/$validate")
        .then()
        .statusCode(200)
        .body("issue.severity", not(hasItem("error")))
        .body("issue.severity", hasItem("information"));
  }
}
