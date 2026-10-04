package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientContext;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Contexto {@code patient/}: compartimento do próprio paciente nos recursos clínicos. */
@QuarkusTest
class PatientCompartmentTest {

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
  void patientContextIsLimitedToOwnCompartment() {
    String mine = create("Patient", patientJson(randomCns(), "Meu", "Paciente", "1980-01-01"));
    String other = create("Patient", patientJson(randomCns(), "Outro", "Paciente", "1981-01-01"));
    String myEncounter = create("Encounter", clinicalJson("encounter.json", mine));
    String otherEncounter = create("Encounter", clinicalJson("encounter.json", other));
    String myTask = create("Task", clinicalJson("task.json", mine));
    create("Task", clinicalJson("task.json", other));
    String myAppointment = create("Appointment", clinicalJson("appointment.json", mine));

    // busca: somente o próprio compartimento, mesmo sem filtro
    patientContext(mine)
        .get(FHIR + "/Encounter")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(myEncounter));
    patientContext(mine)
        .get(FHIR + "/Task")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(myTask));
    // tentativa de ampliar o filtro para outro paciente não vaza nada (AND com o compartimento)
    patientContext(mine)
        .queryParam("patient", other)
        .get(FHIR + "/Encounter")
        .then()
        .statusCode(200)
        .body("entry", equalTo(null));

    // leitura por id: próprio → 200; de outro → 403
    patientContext(mine).get(FHIR + "/Encounter/" + myEncounter).then().statusCode(200);
    patientContext(mine).get(FHIR + "/Appointment/" + myAppointment).then().statusCode(200);
    patientContext(mine)
        .get(FHIR + "/Encounter/" + otherEncounter)
        .then()
        .statusCode(403)
        .body("issue[0].code", equalTo("forbidden"));
    patientContext(mine)
        .get(FHIR + "/Encounter/" + otherEncounter + "/_history")
        .then()
        .statusCode(403);
    patientContext(mine).get(FHIR + "/Patient/" + other).then().statusCode(403);

    // _include no contexto patient/ só traz o próprio Patient
    patientContext(mine)
        .queryParam("_include", "Encounter:patient")
        .get(FHIR + "/Encounter")
        .then()
        .statusCode(200)
        .body("entry", hasSize(2))
        .body("entry[1].resource.id", equalTo(mine));

    // tipos sem compartimento de paciente são negados no contexto patient/
    patientContext(mine).get(FHIR + "/Provenance").then().statusCode(403);
    patientContext(mine).get(FHIR + "/Organization").then().statusCode(403);
    // escrita nunca no contexto patient/
    br.gov.sus.nexus.fhir.FhirTestSupport.as(
            "patient-app", "patient/*.*", br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A)
        .header(br.gov.sus.nexus.fhir.FhirConstants.HEADER_TEST_PATIENT, mine)
        .body(clinicalJson("task.json", mine))
        .post(FHIR + "/Task")
        .then()
        .statusCode(403);
  }
}
