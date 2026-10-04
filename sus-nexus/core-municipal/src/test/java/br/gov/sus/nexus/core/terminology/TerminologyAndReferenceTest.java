package br.gov.sus.nexus.core.terminology;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.TENANT_B;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import br.gov.sus.nexus.core.terminology.api.TerminologyService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TerminologyAndReferenceTest {

  @Inject TerminologyService terminology;

  @Test
  void searchesCodesByUnaccentedTextAndCompetence() {
    aps(TENANT_A)
        .get("/api/v1/terminology/CID10/codes?q=hipertensao")
        .then()
        .statusCode(200)
        .body("items.code", hasItem("I10"));
    aps(TENANT_A)
        .get("/api/v1/terminology/CID10/codes?q=Hipertensão essencial")
        .then()
        .statusCode(200)
        .body("items[0].code", equalTo("I10"))
        .body("items[0].attributes.chapter", equalTo("IX"));
    aps(TENANT_A)
        .get("/api/v1/terminology/SIGTAP/codes?code=0301010064&competence=202403")
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].display", equalTo("Consulta médica em atenção primária"));
    aps(TENANT_A)
        .get("/api/v1/terminology/SIGTAP/codes?code=0301010064&competence=202312")
        .then()
        .statusCode(200)
        .body("items", hasSize(0));
    aps(TENANT_A)
        .get("/api/v1/terminology/CBO/codes?limit=2")
        .then()
        .statusCode(200)
        .body("items", hasSize(2))
        .body("next_cursor", not(equalTo(null)));
    aps(TENANT_A)
        .get("/api/v1/terminology/XYZ/codes")
        .then()
        .statusCode(422)
        .contentType("application/problem+json");
  }

  @Test
  void isValidRespectsSystemCodeAndCompetence() {
    assertThat(terminology.isValid("SIGTAP", "0301010064", "202403")).isTrue();
    assertThat(terminology.isValid("sigtap", "0301010064", null)).isTrue();
    assertThat(terminology.isValid("SIGTAP", "0301010064", "202312")).isFalse();
    assertThat(terminology.isValid("CID10", "I10", "202601")).isTrue();
    assertThat(terminology.isValid("CID10", "Z99.9", "202601")).isFalse();
    assertThat(terminology.isValid("CIAP2", "K86", "202601")).isTrue();
    assertThat(terminology.isValid("CBO", "225125", "202601")).isTrue();
  }

  @Test
  void healthUnitUpsertIsIdempotentPerTenantAndCnes() {
    Map<String, Object> body =
        Map.of(
            "cnes", "2222222",
            "name", "UBS Saúde da Família Centro",
            "kind_code", "02",
            "kind_description", "Centro de Saúde/Unidade Básica",
            "active", true,
            "source_system", "CNES");
    String id =
        integration(TENANT_A)
            .body(body)
            .put("/api/v1/reference/health-units")
            .then()
            .statusCode(200)
            .body("id", not(equalTo(null)))
            .extract()
            .path("id");
    integration(TENANT_A)
        .body(Map.of("cnes", "2222222", "name", "UBS Centro (renomeada)"))
        .put("/api/v1/reference/health-units")
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("name", equalTo("UBS Centro (renomeada)"))
        .body("kind_code", equalTo("02"));

    aps(TENANT_A)
        .get("/api/v1/reference/health-units?q=ubs centro")
        .then()
        .statusCode(200)
        .body("items.id", hasItem(id));
    aps(TENANT_A)
        .get("/api/v1/reference/health-units?cnes=2222222")
        .then()
        .statusCode(200)
        .body("items", hasSize(1));
    aps(TENANT_B)
        .get("/api/v1/reference/health-units?cnes=2222222")
        .then()
        .statusCode(200)
        .body("items", hasSize(0));

    integration(TENANT_A)
        .body(Map.of("cnes", "12", "name", "inválida"))
        .put("/api/v1/reference/health-units")
        .then()
        .statusCode(422)
        .body("errors[0].field", equalTo("cnes"));
  }
}
