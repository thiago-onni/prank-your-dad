package br.gov.sus.nexus.core.terminology;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Upserts em lote usados pelos conectores (contrato incorporado ao core-municipal.yaml). */
@QuarkusTest
class IngestionUpsertTest {

  static final Map<String, Object> SOURCE =
      Map.of(
          "system",
          "CNES",
          "connector",
          "connector-cnes",
          "source_record_id",
          "tbEstabelecimento202609.csv");

  @Test
  void healthUnitsBatchIsIdempotentAndCountsRejected() {
    String key = "hu-batch-" + System.nanoTime();
    Map<String, Object> batch =
        Map.of(
            "source",
            SOURCE,
            "competence",
            "202609",
            "items",
            List.of(
                Map.of(
                    "cnes",
                    "7000001",
                    "name",
                    "UBS Lote Um",
                    "kind_code",
                    "01",
                    "city_ibge",
                    "3143302",
                    "attributes",
                    Map.of("turno", "integral")),
                Map.of("cnes", "7000002", "name", "UBS Lote Dois", "active", true),
                Map.of("cnes", "12", "name", "Inválida")));
    integration(TENANT_A)
        .header("Idempotency-Key", key)
        .body(batch)
        .post("/api/v1/reference/health-units/upsert")
        .then()
        .statusCode(200)
        .body("created", equalTo(2))
        .body("updated", equalTo(0))
        .body("unchanged", equalTo(0))
        .body("rejected", equalTo(1));
    // mesmo lote, outra chave: nada muda
    integration(TENANT_A)
        .body(batch)
        .post("/api/v1/reference/health-units/upsert")
        .then()
        .statusCode(200)
        .body("created", equalTo(0))
        .body("unchanged", equalTo(2))
        .body("rejected", equalTo(1));
    Map<String, Object> changed =
        Map.of(
            "source",
            SOURCE,
            "items",
            List.of(Map.of("cnes", "7000001", "name", "UBS Lote Um Renomeada")));
    integration(TENANT_A)
        .body(changed)
        .post("/api/v1/reference/health-units/upsert")
        .then()
        .statusCode(200)
        .body("updated", equalTo(1));
    aps(TENANT_A)
        .get("/api/v1/reference/health-units?cnes=7000001")
        .then()
        .body("items", hasSize(1))
        .body("items[0].name", equalTo("UBS Lote Um Renomeada"));
    aps(TENANT_A).post("/api/v1/reference/health-units/upsert").then().statusCode(403);
  }

  @Test
  void codesBatchUpsertsByCompetence() {
    Map<String, Object> batch =
        Map.of(
            "source",
            Map.of(
                "system",
                "SIGTAP",
                "connector",
                "connector-terminology",
                "source_record_id",
                "sigtap-202609.zip"),
            "competence",
            "202609",
            "version",
            "layout-2024.1",
            "items",
            List.of(
                Map.of(
                    "code",
                    "0999999991",
                    "display",
                    "Procedimento de teste um",
                    "attributes",
                    Map.of("group", "09")),
                Map.of("code", "0999999992", "display", "Procedimento de teste dois"),
                Map.of("code", "", "display", "sem código")));
    integration(TENANT_A)
        .body(batch)
        .post("/api/v1/terminology/SIGTAP/codes/upsert")
        .then()
        .statusCode(200)
        .body("created", equalTo(2))
        .body("rejected", equalTo(1));
    integration(TENANT_A)
        .body(batch)
        .post("/api/v1/terminology/SIGTAP/codes/upsert")
        .then()
        .statusCode(200)
        .body("unchanged", equalTo(2));
    integration(TENANT_A)
        .body(
            Map.of(
                "source",
                    Map.of(
                        "system",
                        "SIGTAP",
                        "connector",
                        "connector-terminology",
                        "source_record_id",
                        "sigtap-202610.zip"),
                "competence", "202609",
                "items",
                    List.of(
                        Map.of(
                            "code",
                            "0999999991",
                            "display",
                            "Procedimento de teste um (rev.)",
                            "competence_to",
                            "202612"))))
        .post("/api/v1/terminology/SIGTAP/codes/upsert")
        .then()
        .statusCode(200)
        .body("updated", equalTo(1));
    aps(TENANT_A)
        .get("/api/v1/terminology/SIGTAP/codes?code=0999999991&competence=202610")
        .then()
        .body("items", hasSize(1))
        .body("items[0].display", equalTo("Procedimento de teste um (rev.)"))
        .body("items[0].competence_to", equalTo("202612"));
    integration(TENANT_A)
        .body(batch)
        .post("/api/v1/terminology/XPTO/codes/upsert")
        .then()
        .statusCode(422);
  }
}
