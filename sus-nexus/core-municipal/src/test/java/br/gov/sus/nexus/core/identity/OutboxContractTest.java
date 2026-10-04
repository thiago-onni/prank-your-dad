package br.gov.sus.nexus.core.identity;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** O outbox deve conter envelopes válidos contra contracts/events/*.schema.json. */
@QuarkusTest
class OutboxContractTest {

  static final ObjectMapper MAPPER = new ObjectMapper();

  record OutboxRow(String id, String eventType, JsonNode payload, JsonNode headers) {}

  @Test
  void citizenCreatedAndLinkedEventsAreValidAgainstEnvelopeAndDataSchemas() throws Exception {
    String cns = Fixtures.randomProvisionalCns();
    Response first =
        integration(TENANT_A)
            .header("X-Correlation-Id", "corr_outbox_test_1")
            .body(
                Registration.of("Evento Válido Souza", LocalDate.of(1992, 2, 2))
                    .mother("Mãe do Evento")
                    .cns(cns)
                    .territory("1234567", "0000123456", "03")
                    .build())
            .post("/api/v1/citizens");
    first.then().statusCode(201);
    String id = first.path("municipal_citizen_id");

    integration(TENANT_A)
        .body(
            Registration.of("Evento Válido Souza", LocalDate.of(1992, 2, 2))
                .source("SISREG", "connector-sisreg", "SISREG-" + System.nanoTime())
                .cns(cns)
                .build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(200);

    List<OutboxRow> rows = outboxFor(id);
    assertThat(rows)
        .extracting(OutboxRow::eventType)
        .containsExactly("sus.identity.citizen.created", "sus.identity.citizen.linked");

    JsonSchema envelope = schema("contracts/events/envelope.schema.json");
    JsonSchema citizenData = schema("contracts/events/identity/citizen.v1.schema.json");

    for (OutboxRow row : rows) {
      Set<ValidationMessage> envelopeErrors = envelope.validate(row.payload());
      assertThat(envelopeErrors).as("envelope %s", row.eventType()).isEmpty();
      Set<ValidationMessage> dataErrors = citizenData.validate(row.payload().get("data"));
      assertThat(dataErrors).as("data %s", row.eventType()).isEmpty();

      assertThat(row.payload().get("event_id").asText()).isEqualTo(row.id());
      assertThat(row.payload().get("tenant").get("municipality_id").asText()).isEqualTo(TENANT_A);
      assertThat(row.payload().get("subject").get("municipal_citizen_id").asText()).isEqualTo(id);
      JsonNode identifiers = row.payload().get("subject").get("identifiers");
      assertThat(identifiers).isNotNull();
      assertThat(identifiers.get(0).get("value_masked").asText())
          .isEqualTo("***********" + cns.substring(11));
      assertThat(identifiers.get(0).get("value_hash").asText()).matches("^[a-f0-9]{64}$");
      // nunca CNS em claro em lugar algum do payload
      assertThat(row.payload().toString()).doesNotContain(cns);
      assertThat(row.payload().get("trace").get("schema_version").asText()).isEqualTo("1.0.0");

      assertThat(row.headers().get("ce_id").asText()).isEqualTo(row.id());
      assertThat(row.headers().get("ce_type").asText()).isEqualTo(row.eventType());
      assertThat(row.headers().get("tenant_id").asText()).isEqualTo(TENANT_A);
      assertThat(row.headers().has("correlation_id")).isTrue();
    }
    assertThat(rows.get(0).payload().get("trace").get("correlation_id").asText())
        .isEqualTo("corr_outbox_test_1");
    assertThat(rows.get(0).payload().get("data").get("action").asText()).isEqualTo("created");
    assertThat(rows.get(0).payload().get("data").get("territory").get("health_unit_cnes").asText())
        .isEqualTo("1234567");
    assertThat(rows.get(1).payload().get("data").get("match").get("method").asText())
        .isEqualTo("deterministic_cns");
  }

  @Test
  void mergeCaseEventsAreValidAgainstMergeSchema() throws Exception {
    String cns = Fixtures.randomProvisionalCns();
    integration(TENANT_A)
        .body(Registration.of("Conflito Evento", LocalDate.of(1970, 1, 1)).cns(cns).build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(201);
    Response conflict =
        integration(TENANT_A)
            .body(Registration.of("Conflito Evento", LocalDate.of(1971, 1, 1)).cns(cns).build())
            .post("/api/v1/citizens");
    conflict.then().statusCode(202);
    String caseId = conflict.path("merge_case_id");

    List<OutboxRow> rows = outboxFor(caseId);
    assertThat(rows)
        .extracting(OutboxRow::eventType)
        .containsExactly("sus.identity.merge.case_opened");
    JsonSchema envelope = schema("contracts/events/envelope.schema.json");
    JsonSchema mergeData = schema("contracts/events/identity/merge.v1.schema.json");
    assertThat(envelope.validate(rows.get(0).payload())).isEmpty();
    assertThat(mergeData.validate(rows.get(0).payload().get("data"))).isEmpty();
    assertThat(rows.get(0).payload().get("data").get("case_id").asText()).isEqualTo(caseId);
  }

  static JsonSchema schema(String resource) throws Exception {
    try (InputStream in = OutboxContractTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as("schema %s copiado para test resources", resource).isNotNull();
      JsonNode node = MAPPER.readTree(in);
      SchemaValidatorsConfig config =
          SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node, config);
    }
  }

  static List<OutboxRow> outboxFor(String aggregateId) throws Exception {
    List<OutboxRow> out = new ArrayList<>();
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "select id, event_type, payload::text, headers::text from platform.event_outbox"
                    + " where aggregate_id = ? order by created_at, id")) {
      ps.setString(1, aggregateId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new OutboxRow(
                  rs.getString(1),
                  rs.getString(2),
                  MAPPER.readTree(rs.getString(3)),
                  MAPPER.readTree(rs.getString(4))));
        }
      }
    }
    return out;
  }
}
