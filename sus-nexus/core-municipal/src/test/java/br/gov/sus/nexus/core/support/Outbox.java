package br.gov.sus.nexus.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Leitura do outbox e validação de envelopes contra os contratos de evento. */
public final class Outbox {

  public static final ObjectMapper MAPPER = new ObjectMapper();

  public record Row(String id, String eventType, JsonNode payload, JsonNode headers) {}

  private Outbox() {}

  public static List<Row> rowsFor(String aggregateId) throws Exception {
    List<Row> out = new ArrayList<>();
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "select id, event_type, payload::text, headers::text from platform.event_outbox"
                    + " where aggregate_id = ? order by created_at, id")) {
      ps.setString(1, aggregateId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new Row(
                  rs.getString(1),
                  rs.getString(2),
                  MAPPER.readTree(rs.getString(3)),
                  MAPPER.readTree(rs.getString(4))));
        }
      }
    }
    return out;
  }

  public static JsonSchema schema(String resource) throws Exception {
    try (InputStream in = Outbox.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as("schema %s copiado para test resources", resource).isNotNull();
      JsonNode node = MAPPER.readTree(in);
      SchemaValidatorsConfig config =
          SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node, config);
    }
  }

  /** Valida envelope + data de cada linha contra o schema de dados informado. */
  public static void assertValid(List<Row> rows, String dataSchema) throws Exception {
    JsonSchema envelope = schema("contracts/events/envelope.schema.json");
    JsonSchema data = schema(dataSchema);
    for (Row row : rows) {
      assertThat(envelope.validate(row.payload())).as("envelope %s", row.eventType()).isEmpty();
      assertThat(data.validate(row.payload().get("data"))).as("data %s", row.eventType()).isEmpty();
    }
  }
}
