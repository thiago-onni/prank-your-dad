package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProjectionTest {

  @Inject FhirCodec codec;

  private static RequestSpecification core() {
    return as("core-municipal", "system/*.write system/*.read", TENANT_A)
        .contentType("application/json")
        .header("X-Source-System", "core-municipal")
        .header("X-Source-Record-Id", "evt_01HZX4Y5K6M7N8P9Q0R1S2T3Z9");
  }

  @Test
  void projectsCitizenAndHealthUnitWithProvenance() {
    // unidade primeiro, para resolver managingOrganization por CNES
    Response org =
        core()
            .body(fixture("canonical-health-unit.json"))
            .post("/internal/projections/health-unit")
            .then()
            .log()
            .ifValidationFails()
            // 201 na primeira projeção; 200 se outro teste já projetou a mesma unidade
            .statusCode(Matchers.anyOf(equalTo(200), equalTo(201)))
            .body("resourceType", equalTo("Organization"))
            .body("id", equalTo("01HZX4Y5K6M7N8P9Q0R1S2T3W1"))
            .body(
                "identifier.find { it.system == '" + FhirConstants.SYSTEM_CNES + "' }.value",
                equalTo("2112345"))
            .extract()
            .response();
    if (org.statusCode() == 201) {
      assertThat(org.header("X-Provenance-Location")).contains("/Provenance/");
    }

    Response created =
        core()
            .body(fixture("canonical-citizen.json"))
            .post("/internal/projections/citizen")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .header("ETag", "W/\"1\"")
            .header("X-Provenance-Location", notNullValue())
            .body("resourceType", equalTo("Patient"))
            .body("id", equalTo("01HZX4Y5K6M7N8P9Q0R1S2T3U4"))
            .body(
                "meta.profile[0]",
                equalTo("https://br-core.saude.gov.br/fhir/StructureDefinition/BRCorePatient"))
            .body(
                "managingOrganization.reference",
                equalTo("Organization/01HZX4Y5K6M7N8P9Q0R1S2T3W1"))
            .extract()
            .response();
    Patient p = (Patient) codec.parse(created.asString());
    var cns =
        p.getIdentifier().stream()
            .filter(i -> FhirConstants.SYSTEM_CNS.equals(i.getSystem()))
            .findFirst()
            .orElseThrow();
    assertThat(cns.getValue()).isEqualTo("700000000000001");
    assertThat(cns.hasExtension(FhirConstants.EXT_MASKED_IDENTIFIER)).isFalse();
    assertThat(
            p.getIdentifier().stream()
                .anyMatch(
                    i ->
                        FhirConstants.SYSTEM_MUNICIPAL_CITIZEN_ID.equals(i.getSystem())
                            && "cit_01HZX4Y5K6M7N8P9Q0R1S2T3U4".equals(i.getValue())))
        .isTrue();
    assertThat(p.getNameFirstRep().getFamily()).isEqualTo("Ferreira");
    assertThat(p.getGender().toCode()).isEqualTo("female");
    assertThat(p.getExtensionByUrl(FhirConstants.EXT_MOTHERS_NAME).getValue().primitiveValue())
        .isEqualTo("Joana Ferreira");
    assertThat(p.getAddressFirstRep().getLine().get(0).getValue()).isEqualTo("Rua das Acácias, 45");

    // visível pela API FHIR e com Provenance apontando para a versão
    clinician().get(FHIR + "/Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4").then().statusCode(200);
    as("auditor", "user/Provenance.read", TENANT_A)
        .queryParam("target", "Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4")
        .get(FHIR + "/Provenance")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body(
            "entry[0].resource.target[0].reference",
            equalTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4/_history/1"))
        .body(
            "entry[0].resource.entity[0].what.identifier.value",
            equalTo("evt_01HZX4Y5K6M7N8P9Q0R1S2T3Z9"));

    // idempotente: mesmo conteúdo → 200, sem nova versão nem nova Provenance
    core()
        .body(fixture("canonical-citizen.json"))
        .post("/internal/projections/citizen")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .header("ETag", "W/\"1\"")
        .header("X-Provenance-Location", nullValue());

    // mudança → nova versão + nova Provenance
    core()
        .body(
            fixture("canonical-citizen.json")
                .replace("\"version\": 3", "\"version\": 4")
                .replace("Ana Paula Ferreira", "Ana Paula Ferreira Lima"))
        .post("/internal/projections/citizen")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(200)
        .header("ETag", "W/\"2\"")
        .header("X-Provenance-Location", notNullValue());
    as("auditor", "user/Provenance.read", TENANT_A)
        .queryParam("target", "Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4")
        .get(FHIR + "/Provenance")
        .then()
        .log()
        .ifValidationFails()
        .body("entry", hasSize(2));
  }

  @Test
  void maskedIdentifiersCarryExtension() {
    Response r =
        core()
            .body(fixture("canonical-citizen-masked.json"))
            .post("/internal/projections/citizen")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
            .extract()
            .response();
    Patient p = (Patient) codec.parse(r.asString());
    var cns =
        p.getIdentifier().stream()
            .filter(i -> FhirConstants.SYSTEM_CNS.equals(i.getSystem()))
            .findFirst()
            .orElseThrow();
    assertThat(cns.getValue()).isEqualTo("701********0002");
    assertThat(
            cns.getExtensionByUrl(FhirConstants.EXT_MASKED_IDENTIFIER).getValue().primitiveValue())
        .isEqualTo("true");
    assertThat(p.getTelecomFirstRep().hasExtension(FhirConstants.EXT_MASKED_IDENTIFIER)).isTrue();
    assertThat(p.getManagingOrganization().isEmpty()).isTrue();
  }

  @Test
  void requiresSystemWriteScope() {
    as("user", "user/*.write", TENANT_A)
        .contentType("application/json")
        .body(fixture("canonical-citizen.json"))
        .post("/internal/projections/citizen")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(403);
    as("sys-ro", "system/*.read", TENANT_A)
        .contentType("application/json")
        .body(fixture("canonical-citizen.json"))
        .post("/internal/projections/citizen")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(403);
    core()
        .body("{\"display_name\": \"sem id\"}")
        .post("/internal/projections/citizen")
        .then()
        .log()
        .ifValidationFails()
        .statusCode(400);
  }
}
