package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientContext;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static br.gov.sus.nexus.fhir.http.ResultsAndDocumentsTest.create;
import static br.gov.sus.nexus.fhir.http.ResultsAndDocumentsTest.docClinician;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code Patient/$everything}: compartimento, política/redação, cursor, filtros e auditoria. */
@QuarkusTest
class EverythingTest {

  @Test
  void everythingReturnsCompartmentWithPolicyCursorAndAudit() {
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Tudo", "Davi", "1966-06-06"));
    String other =
        create(clinician(), "Patient", patientJson(randomCns(), "Outro", "Eva", "1967-07-07"));
    String sr = create(clinician(), "ServiceRequest", clinicalJson("servicerequest.json", patient));
    String obs = create(clinician(), "Observation", clinicalJson("observation.json", patient));
    String cond = create(clinician(), "Condition", clinicalJson("condition.json", patient));
    String doc =
        create(
            docClinician(), "DocumentReference", clinicalJson("documentreference.json", patient));
    create(clinician(), "Observation", clinicalJson("observation.json", other));

    JsonPath all =
        clinician()
            .header(FhirConstants.HEADER_PURPOSE_OF_USE, "care_coordination")
            .get(FHIR + "/Patient/" + patient + "/$everything")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("type", equalTo("searchset"))
            .extract()
            .jsonPath();
    List<String> ids = all.getList("entry.resource.id");
    // DocumentReference exige escopo explícito: omitido para user/*.read
    assertThat(ids).containsExactlyInAnyOrder(patient, sr, obs, cond);

    docClinician()
        .get(FHIR + "/Patient/" + patient + "/$everything")
        .then()
        .statusCode(200)
        .body("entry.resource.id", hasItem(doc));

    // _type e _count + cursor
    List<String> paged = new ArrayList<>();
    String url = FHIR + "/Patient/" + patient + "/$everything";
    JsonPath page =
        clinician()
            .queryParam("_type", "Observation,ServiceRequest,Condition")
            .queryParam("_count", "2")
            .get(url)
            .then()
            .statusCode(200)
            .body("entry", hasSize(2))
            .extract()
            .jsonPath();
    paged.addAll(page.getList("entry.resource.id"));
    String next = page.getString("link.find { it.relation == 'next' }.url");
    String cursor = next.substring(next.indexOf("_cursor=") + 8);
    page =
        clinician()
            .queryParam("_cursor", cursor)
            .get(url)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    paged.addAll(page.getList("entry.resource.id"));
    assertThat(paged).containsExactlyInAnyOrder(patient, sr, obs, cond);

    // _since no futuro → vazio
    clinician()
        .queryParam("_since", "2999-01-01")
        .get(url)
        .then()
        .statusCode(200)
        .body("entry", org.hamcrest.Matchers.nullValue());

    // redação por escopo granular
    as("restrito", "user/Patient.rs user/Observation.rs", TENANT_A)
        .get(url)
        .then()
        .statusCode(200)
        .body("entry.resource.resourceType", org.hamcrest.Matchers.not(hasItem("Condition")))
        .body(
            "entry.find { it.resource.resourceType == 'Patient' }.resource.telecom",
            org.hamcrest.Matchers.nullValue());

    // contexto patient/: próprio paciente sim, outro não
    patientContext(patient).get(url).then().statusCode(200);
    patientContext(other).get(url).then().statusCode(403);

    // parâmetros inválidos e tipo sem a operação
    clinician().queryParam("_type", "Organization").get(url).then().statusCode(400);
    clinician().queryParam("foo", "bar").get(url).then().statusCode(400);
    clinician().get(FHIR + "/Encounter/" + patient + "/$everything").then().statusCode(404);
    clinician().get(FHIR + "/Patient/NOPE1/$everything").then().statusCode(404);

    // AuditEvent com purposeOfEvent e uma entidade por recurso devolvido
    JsonPath audit =
        as("auditor", "user/AuditEvent.read", TENANT_A)
            .queryParam("patient", "Patient/" + patient)
            .get(FHIR + "/AuditEvent")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    List<Object> everything =
        audit.getList("entry.findAll { it.resource.subtype[0].code == 'everything' }.resource");
    assertThat(everything).isNotEmpty();
    assertThat(
            audit.getList(
                "entry.findAll { it.resource.purposeOfEvent != null && it.resource.subtype[0].code"
                    + " == 'everything' }.resource.purposeOfEvent[0].coding[0].code"))
        .contains("care_coordination");
    assertThat(
            audit.getList(
                "entry.findAll { it.resource.subtype[0].code == 'everything' }"
                    + ".resource.entity.what.reference.flatten()",
                String.class))
        .anyMatch(r -> r.startsWith("Observation/" + obs));
  }

  @Test
  void pageLimitIsEnforced() {
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Limite", "Fabi", "1968-08-08"));
    for (int i = 0; i < 5; i++) {
      create(clinician(), "Observation", clinicalJson("observation.json", patient));
    }
    String url = FHIR + "/Patient/" + patient + "/$everything";
    String next =
        clinician()
            .queryParam("_count", "1")
            .get(url)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("link.find { it.relation == 'next' }.url");
    int status = 200;
    int pages = 1;
    while (next != null && status == 200 && pages < 10) {
      var r =
          clinician().queryParam("_cursor", next.substring(next.indexOf("_cursor=") + 8)).get(url);
      status = r.statusCode();
      pages++;
      next =
          status == 200 ? r.jsonPath().getString("link.find { it.relation == 'next' }.url") : null;
    }
    // %test.sus.fhir.everything.max-pages=3
    assertThat(status).isEqualTo(400);
    assertThat(pages).isEqualTo(4);
  }
}
