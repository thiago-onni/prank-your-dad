package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinicalJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static br.gov.sus.nexus.fhir.http.ResultsAndDocumentsTest.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Bundles {@code batch}/{@code transaction}: atomicidade, {@code urn:uuid}, {@code If-None-Exist}.
 */
@QuarkusTest
class BundleTest {

  static String entry(String fullUrl, String method, String url, String resource, String extra) {
    return "{"
        + (fullUrl == null ? "" : "\"fullUrl\":\"" + fullUrl + "\",")
        + (resource == null ? "" : "\"resource\":" + resource + ",")
        + "\"request\":{\"method\":\""
        + method
        + "\",\"url\":\""
        + url
        + "\""
        + (extra == null ? "" : "," + extra)
        + "}}";
  }

  static String bundle(String type, String... entries) {
    return "{\"resourceType\":\"Bundle\",\"type\":\""
        + type
        + "\",\"entry\":["
        + String.join(",", entries)
        + "]}";
  }

  @Test
  void transactionResolvesUrnUuidAndIsAtomic() {
    String cns = randomCns();
    String patient = patientJson(cns, "Transacao", "Iris", "1985-05-05");
    String obs =
        clinicalJson("observation.json", "X")
            .replace("Patient/X", "urn:uuid:11111111-1111-1111-1111-111111111111");
    String dr =
        clinicalJson("diagnosticreport.json", "X")
            .replace("Patient/X", "urn:uuid:11111111-1111-1111-1111-111111111111")
            .replace("Observation/OBS1", "urn:uuid:22222222-2222-2222-2222-222222222222");
    JsonPath resp =
        clinician()
            .header(FhirConstants.HEADER_PURPOSE_OF_USE, "care_coordination")
            .body(
                bundle(
                    "transaction",
                    entry(
                        "urn:uuid:33333333-3333-3333-3333-333333333333",
                        "POST",
                        "DiagnosticReport",
                        dr,
                        null),
                    entry(
                        "urn:uuid:11111111-1111-1111-1111-111111111111",
                        "POST",
                        "Patient",
                        patient,
                        "\"ifNoneExist\":\"identifier="
                            + FhirConstants.SYSTEM_CNS
                            + "|"
                            + cns
                            + "\""),
                    entry(
                        "urn:uuid:22222222-2222-2222-2222-222222222222",
                        "POST",
                        "Observation",
                        obs,
                        null)))
            .post(FHIR)
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("type", equalTo("transaction-response"))
            .body("entry", hasSize(3))
            .body("entry.response.status", org.hamcrest.Matchers.everyItem(startsWith("201")))
            .extract()
            .jsonPath();
    // a resposta preserva a ordem das entradas do pedido
    String drId = resp.getString("entry[0].resource.id");
    String patientId = resp.getString("entry[1].resource.id");
    String obsId = resp.getString("entry[2].resource.id");
    assertThat(resp.getString("entry[0].resource.subject.reference"))
        .isEqualTo("Patient/" + patientId);
    assertThat(resp.getString("entry[0].resource.result[0].reference"))
        .isEqualTo("Observation/" + obsId);
    clinician()
        .get(FHIR + "/DiagnosticReport/" + drId)
        .then()
        .statusCode(200)
        .body("subject.reference", equalTo("Patient/" + patientId));

    // If-None-Exist com o mesmo CNS: devolve o existente (200) sem criar outro
    clinician()
        .body(
            bundle(
                "transaction",
                entry(
                    null,
                    "POST",
                    "Patient",
                    patient,
                    "\"ifNoneExist\":\"identifier=" + FhirConstants.SYSTEM_CNS + "|" + cns + "\"")))
        .post(FHIR)
        .then()
        .statusCode(200)
        .body("entry[0].response.status", startsWith("200"))
        .body("entry[0].resource.id", equalTo(patientId));

    // atomicidade: a segunda entrada é inválida → 422 e nada gravado
    String cns2 = randomCns();
    clinician()
        .body(
            bundle(
                "transaction",
                entry(
                    null,
                    "POST",
                    "Patient",
                    patientJson(cns2, "Atomica", "Joao", "1980-01-01"),
                    null),
                entry(
                    null,
                    "POST",
                    "Observation",
                    clinicalJson("observation.json", "X")
                        .replace(
                            "\"code\": {\"coding\": [{\"system\": \"http://loinc.org\", \"code\": \"718-7\"}], \"text\": \"Hemoglobina\"}",
                            "\"code\": {\"text\": \"sem codigo\"}"),
                    null)))
        .post(FHIR)
        .then()
        .log()
        .ifValidationFails()
        .statusCode(422)
        .body("resourceType", equalTo("OperationOutcome"));
    clinician()
        .queryParam("identifier", FhirConstants.SYSTEM_CNS + "|" + cns2)
        .get(FHIR + "/Patient")
        .then()
        .statusCode(200)
        .body("entry", org.hamcrest.Matchers.nullValue());

    // AuditEvent do Bundle com entidades tocadas
    as("auditor", "user/AuditEvent.read", TENANT_A)
        .queryParam("entity", "DiagnosticReport/" + drId)
        .get(FHIR + "/AuditEvent")
        .then()
        .statusCode(200)
        .body("entry.resource.subtype.code.flatten()", hasItem("transaction"));
  }

  @Test
  void batchEntriesAreIndependent() {
    String patient =
        create(clinician(), "Patient", patientJson(randomCns(), "Lote", "Kai", "1979-09-09"));
    String obs = create(clinician(), "Observation", clinicalJson("observation.json", patient));
    JsonPath resp =
        clinician()
            .body(
                bundle(
                    "batch",
                    entry(null, "GET", "Patient/" + patient, null, null),
                    entry(null, "GET", "Observation?patient=" + patient, null, null),
                    entry(
                        null,
                        "POST",
                        "Observation",
                        "{\"resourceType\":\"Observation\",\"status\":\"final\"}",
                        null),
                    entry(null, "DELETE", "Observation/" + obs, null, null),
                    entry(null, "GET", "Observation/NOPE9", null, null),
                    entry(
                        null,
                        "PUT",
                        "Observation/" + obs,
                        clinicalJson("observation.json", patient),
                        "\"ifMatch\":\"W/\\\"1\\\"\"")))
            .post(FHIR)
            .then()
            .log()
            .ifValidationFails()
            .statusCode(200)
            .body("type", equalTo("batch-response"))
            .extract()
            .jsonPath();
    List<String> statuses = resp.getList("entry.response.status");
    assertThat(statuses.get(0)).startsWith("200");
    assertThat(statuses.get(1)).startsWith("200");
    assertThat(resp.getInt("entry[1].resource.entry.size()")).isEqualTo(1);
    assertThat(statuses.get(2)).startsWith("422");
    assertThat(resp.getString("entry[2].response.outcome.resourceType"))
        .isEqualTo("OperationOutcome");
    assertThat(statuses.get(3)).startsWith("204");
    assertThat(statuses.get(4)).startsWith("404");
    // If-Match na versão 1 depois do delete (versão 2) → 412
    assertThat(statuses.get(5)).startsWith("412");

    // Bundle de tipo errado / não Bundle
    clinician().body(bundle("collection")).post(FHIR).then().statusCode(400);
    clinician()
        .body(patientJson(randomCns(), "N", "B", "1970-01-01"))
        .post(FHIR)
        .then()
        .statusCode(400);
    // sem escopo de escrita: entrada POST falha com 403 dentro do batch
    as("reader", "user/*.read", TENANT_A)
        .body(
            bundle(
                "batch",
                entry(
                    null, "POST", "Observation", clinicalJson("observation.json", patient), null)))
        .post(FHIR)
        .then()
        .statusCode(200)
        .body("entry[0].response.status", startsWith("403"));
  }
}
