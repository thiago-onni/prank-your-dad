package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SearchIncludeSortTotalTest {

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
  void includeSortCursorAndTotal() {
    String patient = create("Patient", patientJson(randomCns(), "Inclusao", "Teste", "1970-01-01"));
    String sr = create("ServiceRequest", clinicalJson("servicerequest.json", patient));
    String t1 =
        create(
            "Task",
            clinicalJson("task.json", patient)
                .replace("ServiceRequest/SR1", "ServiceRequest/" + sr));
    String t2 =
        create(
            "Task",
            clinicalJson("task.json", patient)
                .replace("ServiceRequest/SR1", "ServiceRequest/" + sr)
                .replace("2026-09-02T08:00:00-03:00", "2026-09-05T08:00:00-03:00"));

    // _include Task:patient e Task:based-on
    JsonPath bundle =
        clinician()
            .queryParam("patient", patient)
            .queryParam("_include", "Task:patient")
            .queryParam("_include", "Task:based-on")
            .queryParam("_total", "accurate")
            .get(FHIR + "/Task")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("total", equalTo(2))
            .extract()
            .jsonPath();
    List<String> modes = bundle.getList("entry.search.mode");
    assertThat(modes).containsExactlyInAnyOrder("match", "match", "include", "include");
    List<String> included =
        bundle.getList("entry.findAll { it.search.mode == 'include' }.resource.resourceType");
    assertThat(included).containsExactlyInAnyOrder("Patient", "ServiceRequest");
    assertThat(bundle.getList("entry.findAll { it.search.mode == 'include' }.resource.id"))
        .containsExactlyInAnyOrder(patient, sr);

    // _sort decrescente por authored-on
    clinician()
        .queryParam("patient", patient)
        .queryParam("_sort", "-authored-on")
        .get(FHIR + "/Task")
        .then()
        .statusCode(200)
        .body("entry[0].resource.id", equalTo(t2))
        .body("entry[1].resource.id", equalTo(t1));

    // _sort crescente com _count=1 e cursor: a segunda página traz t2
    JsonPath page1 =
        clinician()
            .queryParam("patient", patient)
            .queryParam("_sort", "authored-on")
            .queryParam("_count", "1")
            .get(FHIR + "/Task")
            .then()
            .statusCode(200)
            .body("entry", hasSize(1))
            .body("entry[0].resource.id", equalTo(t1))
            .extract()
            .jsonPath();
    String next = page1.getString("link.find { it.relation == 'next' }.url");
    assertThat(next).contains("_cursor=");
    String cursor = next.substring(next.indexOf("_cursor=") + 8);
    clinician()
        .queryParam("_cursor", cursor)
        .get(FHIR + "/Task")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(t2))
        .body("link.find { it.relation == 'next' }", equalTo(null));

    // _sort por _lastUpdated decrescente: t2 foi criado por último
    clinician()
        .queryParam("patient", patient)
        .queryParam("_sort", "-_lastUpdated")
        .get(FHIR + "/Task")
        .then()
        .statusCode(200)
        .body("entry[0].resource.id", equalTo(t2));

    // inválidos
    clinician()
        .queryParam("_include", "Task:owner")
        .get(FHIR + "/Task")
        .then()
        .statusCode(400)
        .body("issue[0].diagnostics", org.hamcrest.Matchers.containsString("_include"));
    clinician().queryParam("_sort", "status").get(FHIR + "/Task").then().statusCode(400);
    clinician().queryParam("_total", "maybe").get(FHIR + "/Task").then().statusCode(400);
  }

  @Test
  void includeRespectsIncludedTypeScope() {
    String patient = create("Patient", patientJson(randomCns(), "Escopo", "Inc", "1971-01-01"));
    create("Encounter", clinicalJson("encounter.json", patient));
    // sem escopo de leitura de Patient, o include é omitido silenciosamente
    br.gov.sus.nexus.fhir.FhirTestSupport.as(
            "enc-only", "user/Encounter.read", br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A)
        .queryParam("patient", patient)
        .queryParam("_include", "Encounter:patient")
        .get(FHIR + "/Encounter")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].search.mode", equalTo("match"));
    clinician()
        .queryParam("patient", patient)
        .queryParam("_include", "Encounter:patient")
        .get(FHIR + "/Encounter")
        .then()
        .statusCode(200)
        .body("entry", hasSize(2));
  }
}
