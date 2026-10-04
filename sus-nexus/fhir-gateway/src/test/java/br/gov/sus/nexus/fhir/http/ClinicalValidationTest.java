package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomId;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Invariantes municipais negativas por tipo (FHIRPath, reportadas com issue.expression). */
@QuarkusTest
class ClinicalValidationTest {

  private void expectInvariant(String type, String json, String key) {
    clinician()
        .body(json)
        .post(FHIR + "/" + type)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(422)
        .body("issue.diagnostics", hasItem(containsString("[" + key + "]")));
  }

  @Test
  void appointmentWithoutPatientParticipant() {
    String p = randomId();
    expectInvariant(
        "Appointment",
        clinicalJson("appointment.json", p).replace("Patient/" + p, "Practitioner/" + p),
        "sus-app-1");
    expectInvariant(
        "Appointment",
        clinicalJson("appointment.json", p)
            .replace("\"start\": \"2026-09-15T10:00:00-03:00\",", "")
            .replace("\"end\": \"2026-09-15T10:20:00-03:00\",", ""),
        "sus-app-2");
  }

  @Test
  void serviceRequestNeedsPatientSubjectAndCodedCode() {
    String p = randomId();
    expectInvariant(
        "ServiceRequest",
        clinicalJson("servicerequest.json", p).replace("Patient/" + p, "Group/" + p),
        "sus-sr-1");
    expectInvariant(
        "ServiceRequest",
        clinicalJson("servicerequest.json", p)
            .replace(
                "\"system\": \"http://www.saude.gov.br/fhir/r4/CodeSystem/BRTabelaSUS\", ", ""),
        "sus-sr-2");
  }

  @Test
  void taskNeedsForAndCodedType() {
    String p = randomId();
    expectInvariant(
        "Task",
        clinicalJson("task.json", p)
            .replace("\"for\": {\"reference\": \"Patient/" + p + "\"},", ""),
        "sus-task-1");
    expectInvariant(
        "Task",
        clinicalJson("task.json", p)
            .replace("\"system\": \"https://sus-nexus.gov.br/fhir/CodeSystem/task-type\", ", ""),
        "sus-task-2");
  }

  @Test
  void conditionAndCarePlanNeedPatientSubject() {
    String p = randomId();
    expectInvariant(
        "Condition",
        clinicalJson("condition.json", p).replace("Patient/" + p, "Group/" + p),
        "sus-cond-1");
    expectInvariant(
        "Condition",
        clinicalJson("condition.json", p)
            .replace("\"system\": \"http://hl7.org/fhir/sid/icd-10\", ", ""),
        "sus-cond-2");
    expectInvariant(
        "CarePlan",
        clinicalJson("careplan.json", p).replace("Patient/" + p, "Group/" + p),
        "sus-cp-1");
  }

  @Test
  void encounterNeedsClassSystemAndPatient() {
    String p = randomId();
    expectInvariant(
        "Encounter",
        clinicalJson("encounter.json", p)
            .replace("\"system\": \"http://terminology.hl7.org/CodeSystem/v3-ActCode\", ", ""),
        "sus-enc-2");
    expectInvariant(
        "Encounter",
        clinicalJson("encounter.json", p).replace("Patient/" + p, "Group/" + p),
        "sus-enc-1");
    // perfil obrigatório
    clinician()
        .body(clinicalJson("encounter.json", p).replace("BRCoreEncounter", "Unknown"))
        .post(FHIR + "/Encounter")
        .then()
        .statusCode(422);
  }
}
