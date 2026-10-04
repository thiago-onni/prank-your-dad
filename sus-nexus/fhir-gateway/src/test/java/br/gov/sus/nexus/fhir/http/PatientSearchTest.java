package br.gov.sus.nexus.fhir.http;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.patientJson;
import static br.gov.sus.nexus.fhir.FhirTestSupport.randomCns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PatientSearchTest {

  private String create(String cns, String family, String given, String birth) {
    return clinician()
        .body(patientJson(cns, family, given, birth))
        .post(FHIR + "/Patient")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  @Test
  void searchByIdentifierNameBirthdateAndId() {
    String family = "Fam" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String cns1 = randomCns();
    String id1 = create(cns1, family, "José", "1970-03-15");
    String id2 = create(randomCns(), family, "Ana", "1982-12-01");

    clinician()
        .queryParam("identifier", FhirConstants.SYSTEM_CNS + "|" + cns1)
        .get(FHIR + "/Patient")
        .then()
        .statusCode(200)
        .body("type", equalTo("searchset"))
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(id1))
        .body("entry[0].search.mode", equalTo("match"));
    clinician()
        .queryParam("identifier", cns1)
        .get(FHIR + "/Patient")
        .then()
        .body("entry", hasSize(1));

    // nome: prefixo, sem acento e caixa
    clinician()
        .queryParam("name", family.toLowerCase())
        .get(FHIR + "/Patient")
        .then()
        .statusCode(200)
        .body("entry", hasSize(2));
    clinician()
        .queryParam("name", "jose")
        .queryParam("name", family)
        .get(FHIR + "/Patient")
        .then()
        .statusCode(200)
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(id1));
    clinician()
        .queryParam("name:exact", family)
        .get(FHIR + "/Patient")
        .then()
        .body("entry", hasSize(2));

    // data de nascimento com prefixos
    clinician()
        .queryParam("name", family)
        .queryParam("birthdate", "1970-03-15")
        .get(FHIR + "/Patient")
        .then()
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(id1));
    clinician()
        .queryParam("name", family)
        .queryParam("birthdate", "ge1980")
        .get(FHIR + "/Patient")
        .then()
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(id2));
    clinician()
        .queryParam("name", family)
        .queryParam("birthdate", "lt1980-01-01")
        .get(FHIR + "/Patient")
        .then()
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(id1));

    clinician()
        .queryParam("_id", id2)
        .get(FHIR + "/Patient")
        .then()
        .body("entry", hasSize(1))
        .body("entry[0].resource.id", equalTo(id2));
    clinician()
        .queryParam("name", family)
        .queryParam("_lastUpdated", "ge2000-01-01")
        .get(FHIR + "/Patient")
        .then()
        .body("entry", hasSize(2));
  }

  @Test
  void paginationWithSignedCursor() {
    String family = "Pag" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    for (int i = 0; i < 5; i++) {
      create(randomCns(), family, "P" + i, "2000-01-0" + (i + 1));
    }
    JsonPath page1 =
        clinician()
            .queryParam("name", family)
            .queryParam("_count", "2")
            .get(FHIR + "/Patient")
            .then()
            .statusCode(200)
            .body("entry", hasSize(2))
            .extract()
            .jsonPath();
    List<java.util.Map<String, String>> links = page1.getList("link");
    String next =
        links.stream()
            .filter(l -> "next".equals(l.get("relation")))
            .findFirst()
            .orElseThrow()
            .get("url");
    assertThat(next).contains("_cursor=");

    URI nextUri = URI.create(next);
    String cursor = nextUri.getRawQuery().substring("_cursor=".length());
    JsonPath page2 =
        clinician()
            .queryParam("_cursor", cursor)
            .get(FHIR + "/Patient")
            .then()
            .statusCode(200)
            .body("entry", hasSize(2))
            .extract()
            .jsonPath();
    List<java.util.Map<String, String>> links2 = page2.getList("link");
    String next2 =
        links2.stream()
            .filter(l -> "next".equals(l.get("relation")))
            .findFirst()
            .orElseThrow()
            .get("url");
    String cursor2 = URI.create(next2).getRawQuery().substring("_cursor=".length());
    JsonPath page3 =
        clinician()
            .queryParam("_cursor", cursor2)
            .get(FHIR + "/Patient")
            .then()
            .statusCode(200)
            .body("entry", hasSize(1))
            .extract()
            .jsonPath();
    List<java.util.Map<String, String>> links3 = page3.getList("link");
    assertThat(links3.stream().noneMatch(l -> "next".equals(l.get("relation")))).isTrue();

    // ids distintos entre páginas
    List<String> ids1 = page1.getList("entry.resource.id");
    List<String> ids2 = page2.getList("entry.resource.id");
    List<String> ids3 = page3.getList("entry.resource.id");
    assertThat(ids1).doesNotContainAnyElementsOf(ids2).doesNotContainAnyElementsOf(ids3);

    // cursor adulterado → 400
    clinician()
        .queryParam("_cursor", cursor.substring(0, cursor.length() - 2) + "zz")
        .get(FHIR + "/Patient")
        .then()
        .statusCode(400)
        .body("issue[0].expression[0]", equalTo("_cursor"));
  }

  @Test
  void unknownParameterAndBadValuesAreRejected() {
    clinician()
        .queryParam("foo", "bar")
        .get(FHIR + "/Patient")
        .then()
        .statusCode(400)
        .body("issue[0].code", equalTo("invalid"));
    clinician().queryParam("birthdate", "not-a-date").get(FHIR + "/Patient").then().statusCode(400);
    clinician().queryParam("_count", "0").get(FHIR + "/Patient").then().statusCode(400);
    clinician().queryParam("name:nope", "x").get(FHIR + "/Patient").then().statusCode(400);
  }
}
