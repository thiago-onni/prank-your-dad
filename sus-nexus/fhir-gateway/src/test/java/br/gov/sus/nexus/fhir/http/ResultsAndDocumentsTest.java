package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Observation, DiagnosticReport e DocumentReference: CRUD, busca, _include, regras e redação. */
@QuarkusTest
class ResultsAndDocumentsTest {

  /** Clínico com escopo explícito para DocumentReference (exigido além do curinga). */
  static RequestSpecification docClinician() {
    return as(
        "user-docs",
        "user/*.read user/*.write user/DocumentReference.read user/DocumentReference.write",
        TENANT_A);
  }

  static String create(RequestSpecification who, String type, String json) {
    return who.body(json)
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
  void observationAndReportCrudSearchAndIncludes() {
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Laudo", "Ana", "1975-02-02"));
    String sr = create(clinician(), "ServiceRequest", clinicalJson("servicerequest.json", patient));
    String obs1 =
        create(
            clinician(),
            "Observation",
            clinicalJson("observation.json", patient)
                .replace("ServiceRequest/SR1", "ServiceRequest/" + sr));
    String obs2 =
        create(
            clinician(),
            "Observation",
            clinicalJson("observation.json", patient)
                .replace("ServiceRequest/SR1", "ServiceRequest/" + sr)
                .replace("\"value\": 13.5", "\"value\": 9.8")
                .replace("718-7", "6690-2"));
    String report =
        create(
            clinician(),
            "DiagnosticReport",
            clinicalJson("diagnosticreport.json", patient)
                .replace("ServiceRequest/SR1", "ServiceRequest/" + sr)
                .replace(
                    "\"result\": [{\"reference\": \"Observation/OBS1\"}]",
                    "\"result\": [{\"reference\": \"Observation/"
                        + obs1
                        + "\"}, {\"reference\": \"Observation/"
                        + obs2
                        + "\"}]"));

    // read + update + vread
    clinician()
        .get(FHIR + "/Observation/" + obs1)
        .then()
        .statusCode(200)
        .body("valueQuantity.value", equalTo(13.5f));
    clinician()
        .body(
            clinicalJson("observation.json", patient)
                .replace("ServiceRequest/SR1", "ServiceRequest/" + sr)
                .replace("\"status\": \"final\"", "\"status\": \"amended\""))
        .put(FHIR + "/Observation/" + obs1)
        .then()
        .statusCode(200)
        .header("ETag", "W/\"2\"");
    clinician().get(FHIR + "/Observation/" + obs1 + "/_history/1").then().statusCode(200);

    // busca Observation por paciente, código, data, status, categoria, based-on e value-quantity
    clinician()
        .queryParam("patient", patient)
        .queryParam("code", "http://loinc.org|718-7")
        .queryParam("date", "2026-09-28")
        .queryParam("status", "amended")
        .queryParam("category", "laboratory")
        .queryParam("based-on", "ServiceRequest/" + sr)
        .get(FHIR + "/Observation")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(obs1));
    clinician()
        .queryParam("patient", patient)
        .queryParam("value-quantity", "gt10")
        .get(FHIR + "/Observation")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(obs1));
    clinician()
        .queryParam("patient", patient)
        .queryParam("value-quantity", "9.8|http://unitsofmeasure.org|g/dL")
        .get(FHIR + "/Observation")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(obs2));
    clinician()
        .queryParam("patient", patient)
        .queryParam("value-quantity", "le13.5")
        .get(FHIR + "/Observation")
        .then()
        .statusCode(200)
        .body("entry", hasSize(2));
    clinician()
        .queryParam("value-quantity", "abc")
        .get(FHIR + "/Observation")
        .then()
        .statusCode(400);

    // DiagnosticReport: busca + _include result/based-on
    JsonPath bundle =
        clinician()
            .queryParam("patient", patient)
            .queryParam("status", "final")
            .queryParam("code", "0202010503")
            .queryParam("category", "LAB")
            .queryParam("date", "ge2026-09-01")
            .queryParam("result", "Observation/" + obs2)
            .queryParam("_include", "DiagnosticReport:result")
            .queryParam("_include", "DiagnosticReport:based-on")
            .get(FHIR + "/DiagnosticReport")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(bundle.getList("entry.findAll { it.search.mode == 'match' }.resource.id"))
        .containsExactly(report);
    assertThat(bundle.getList("entry.findAll { it.search.mode == 'include' }.resource.id"))
        .containsExactlyInAnyOrder(obs1, obs2, sr);

    // Observation:based-on
    List<String> inc =
        clinician()
            .queryParam("patient", patient)
            .queryParam("_include", "Observation:based-on")
            .get(FHIR + "/Observation")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("entry.findAll { it.search.mode == 'include' }.resource.resourceType");
    assertThat(inc).containsExactly("ServiceRequest");
  }

  @Test
  void documentReferenceRequiresExplicitScopeAndRejectsInlineData() {
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Documento", "Bia", "1981-03-03"));
    String json = clinicalJson("documentreference.json", patient);

    // curinga user/*.write não basta para DocumentReference
    clinician()
        .body(json)
        .post(FHIR + "/DocumentReference")
        .then()
        .statusCode(403)
        .body("issue[0].diagnostics", org.hamcrest.Matchers.containsString("scope-explicit"));

    // conteúdo inline → 422 sus-doc-1
    docClinician()
        .body(
            json.replace(
                "\"url\": \"http://localhost:8081/fhir/r4/Binary/BIN1\"",
                "\"data\": \"JVBERi0xLjQK\""))
        .post(FHIR + "/DocumentReference")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(422)
        .body("issue.diagnostics", hasItem(org.hamcrest.Matchers.containsString("sus-doc-1")));

    String id = create(docClinician(), "DocumentReference", json);
    docClinician()
        .queryParam("patient", patient)
        .queryParam("type", "http://loinc.org|11502-2")
        .queryParam("status", "current")
        .queryParam("category", "LAB")
        .queryParam("date", "2026-09-29")
        .queryParam("related", "ServiceRequest/SR1")
        .queryParam("_include", "DocumentReference:subject")
        .get(FHIR + "/DocumentReference")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("entry", hasSize(2))
        .body("entry[0].resource.id", equalTo(id))
        .body("entry[1].resource.resourceType", equalTo("Patient"));
    clinician().get(FHIR + "/DocumentReference/" + id).then().statusCode(403);
    as("doc-reader", "user/DocumentReference.read", TENANT_A)
        .get(FHIR + "/DocumentReference/" + id)
        .then()
        .statusCode(200);
  }

  @Test
  void municipalInvariantsForResults() {
    String json = clinicalJson("observation.json", "X1");
    clinician()
        .body(
            json.replace(
                "\"subject\": {\"reference\": \"Patient/X1\"}",
                "\"subject\": {\"reference\": \"Group/G1\"}"))
        .post(FHIR + "/Observation")
        .then()
        .statusCode(422)
        .body("issue.diagnostics", hasItem(org.hamcrest.Matchers.containsString("sus-obs-1")));
    clinician()
        .body(
            clinicalJson("diagnosticreport.json", "X1")
                .replace(
                    "\"conclusion\": \"Hemograma dentro da normalidade\"",
                    "\"presentedForm\": [{\"contentType\": \"application/pdf\", \"data\": \"JVBERi0xLjQK\"}]"))
        .post(FHIR + "/DiagnosticReport")
        .then()
        .statusCode(422)
        .body("issue.diagnostics", hasItem(org.hamcrest.Matchers.containsString("sus-dr-3")));
  }

  @Test
  void granularScopeRedactsValueStringNoteAndConclusion() {
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Redacao", "Cid", "1990-04-04"));
    String obs =
        create(
            clinician(),
            "Observation",
            clinicalJson("observation.json", patient)
                .replace(
                    "\"valueQuantity\": {\"value\": 13.5, \"unit\": \"g/dL\", \"system\": \"http://unitsofmeasure.org\", \"code\": \"g/dL\"}",
                    "\"valueString\": \"Reagente\""));
    String dr =
        create(clinician(), "DiagnosticReport", clinicalJson("diagnosticreport.json", patient));
    as("restrito", "user/Observation.rs user/DiagnosticReport.rs", TENANT_A)
        .get(FHIR + "/Observation/" + obs)
        .then()
        .statusCode(200)
        .body("valueString", nullValue())
        .body("note", nullValue())
        .body("meta.tag.code", hasItem("REDACTED"));
    as("restrito", "user/Observation.rs user/DiagnosticReport.rs", TENANT_A)
        .get(FHIR + "/DiagnosticReport/" + dr)
        .then()
        .statusCode(200)
        .body("conclusion", nullValue());
    clinician()
        .get(FHIR + "/Observation/" + obs)
        .then()
        .statusCode(200)
        .body("valueString", equalTo("Reagente"));
  }

  @Test
  void validateOperationAcceptsParameters() {
    String obs = clinicalJson("observation.json", "P1");
    String params =
        "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"resource\",\"resource\":"
            + obs
            + "}]}";
    clinician()
        .body(params)
        .post(FHIR + "/Observation/$validate")
        .then()
        .statusCode(200)
        .body("issue.severity", org.hamcrest.Matchers.not(hasItem("error")));
    String bad =
        "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"resource\",\"resource\":"
            + obs.replace(
                "\"meta\": {\"profile\": [\"http://sus-nexus.gov.br/fhir/StructureDefinition/SUSNexusObservation\"]},",
                "")
            + "},{\"name\":\"profile\",\"valueUri\":\"http://example.org/StructureDefinition/Unknown\"}]}";
    clinician()
        .body(bad)
        .post(FHIR + "/Observation/$validate")
        .then()
        .statusCode(200)
        .body("issue.severity", hasItem("error"))
        .body(
            "issue.diagnostics",
            hasItem(org.hamcrest.Matchers.containsString("Perfil desconhecido")));
    clinician()
        .body("{\"resourceType\":\"Parameters\",\"parameter\":[]}")
        .post(FHIR + "/$validate")
        .then()
        .statusCode(400);
  }
}
