package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_B;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Binary no object storage (FileBinaryStorage em teste): escopo explícito, compartimento e
 * auditoria.
 */
@QuarkusTest
class BinaryTest {

  static final byte[] PDF = "%PDF-1.4 laudo de teste".getBytes(StandardCharsets.UTF_8);

  static RequestSpecification binaryUser() {
    return as("lab-user", "user/*.read user/Binary.read user/Binary.write", TENANT_A);
  }

  static String binaryJson(String patientId) {
    return "{\"resourceType\":\"Binary\",\"contentType\":\"application/pdf\","
        + "\"securityContext\":{\"reference\":\"Patient/"
        + patientId
        + "\"},\"data\":\""
        + Base64.getEncoder().encodeToString(PDF)
        + "\"}";
  }

  @Test
  void createAndReadWithExplicitScopeCompartmentAndAudit() {
    String patient =
        clinician()
            .body(patientJson(randomCns(), "Binario", "Edu", "1970-07-07"))
            .post(FHIR + "/Patient")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");

    // curinga user/*.write não basta
    clinician().body(binaryJson(patient)).post(FHIR + "/Binary").then().statusCode(403);

    String id =
        binaryUser()
            .body(binaryJson(patient))
            .post(FHIR + "/Binary")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .body("data", nullValue())
            .body("contentType", equalTo("application/pdf"))
            .extract()
            .jsonPath()
            .getString("id");

    // recurso FHIR com data em base64
    String data =
        binaryUser()
            .get(FHIR + "/Binary/" + id)
            .then()
            .statusCode(200)
            .header("ETag", "W/\"1\"")
            .extract()
            .jsonPath()
            .getString("data");
    assertThat(Base64.getDecoder().decode(data)).isEqualTo(PDF);

    // conteúdo bruto
    byte[] raw =
        binaryUser()
            .accept("application/pdf")
            .get(FHIR + "/Binary/" + id)
            .then()
            .statusCode(200)
            .contentType("application/pdf")
            .extract()
            .asByteArray();
    assertThat(raw).isEqualTo(PDF);

    // escopo de leitura curinga sem Binary.read → 403
    clinician().get(FHIR + "/Binary/" + id).then().statusCode(403);
    // outro tenant → 404
    as("b", "user/Binary.read", TENANT_B).get(FHIR + "/Binary/" + id).then().statusCode(404);
    // contexto patient/: o próprio paciente lê; outro paciente não
    as("cidadao", "patient/Binary.read", TENANT_A)
        .header(FhirConstants.HEADER_TEST_PATIENT, patient)
        .get(FHIR + "/Binary/" + id)
        .then()
        .statusCode(200);
    as("cidadao", "patient/Binary.read", TENANT_A)
        .header(FhirConstants.HEADER_TEST_PATIENT, "01J00000000000000000OTHER1")
        .get(FHIR + "/Binary/" + id)
        .then()
        .statusCode(403);

    // AuditEvent da leitura (sucesso e negações)
    as("auditor", "user/AuditEvent.read", TENANT_A)
        .queryParam("entity", "Binary/" + id)
        .get(FHIR + "/AuditEvent")
        .then()
        .statusCode(200)
        .body("entry.resource.subtype.code.flatten()", hasItem("read"))
        .body("entry.resource.outcome", hasItem("4"))
        .body("entry.resource.outcome", hasItem("0"));

    // não há busca nem update de Binary
    binaryUser().get(FHIR + "/Binary").then().statusCode(405);
    binaryUser().body(binaryJson(patient)).put(FHIR + "/Binary/" + id).then().statusCode(405);
  }

  @Test
  void rejectsMissingSecurityContextOrData() {
    binaryUser()
        .body("{\"resourceType\":\"Binary\",\"contentType\":\"application/pdf\",\"data\":\"QUJD\"}")
        .post(FHIR + "/Binary")
        .then()
        .statusCode(422)
        .body("issue.diagnostics", hasItem(org.hamcrest.Matchers.containsString("sus-bin-2")));
    binaryUser()
        .body(
            "{\"resourceType\":\"Binary\",\"contentType\":\"application/pdf\","
                + "\"securityContext\":{\"reference\":\"Patient/P1\"}}")
        .post(FHIR + "/Binary")
        .then()
        .statusCode(422);
  }
}
