package br.gov.sus.nexus.core.platform;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.core.platform.events.OutboxRelay;
import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Outbox;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

/** Relay de desenvolvimento: outbox → canal por aggregate_type, chave por cidadão, published_at. */
@QuarkusTest
class OutboxRelayTest {

  @Inject OutboxRelay relay;
  @Inject Bus bus;

  @Test
  void relaysPendingEventsToTheChannelOfTheAggregate() throws Exception {
    bus.relayAndDeliver(); // esvazia pendências anteriores
    String id =
        integration(TENANT_A)
            .body(Registration.of("Relay Teste", LocalDate.of(1999, 9, 9)).build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");

    int published = relay.relayOnce();
    assertThat(published).isGreaterThanOrEqualTo(1);

    List<? extends Message<String>> received = bus.sink("citizen-out").received();
    Message<String> mine =
        received.stream().filter(m -> m.getPayload().contains(id)).findFirst().orElseThrow();
    JsonNode payload = Outbox.MAPPER.readTree(mine.getPayload());
    assertThat(payload.get("event_type").asText()).isEqualTo("sus.identity.citizen.created");
    OutgoingKafkaRecordMetadata<?> meta =
        mine.getMetadata(OutgoingKafkaRecordMetadata.class).orElseThrow();
    assertThat(meta.getKey()).isEqualTo(id);
    assertThat(new String(meta.getHeaders().lastHeader("ce_type").value()))
        .isEqualTo("sus.identity.citizen.created");
    assertThat(new String(meta.getHeaders().lastHeader("tenant_id").value())).isEqualTo(TENANT_A);

    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "select count(*) from platform.event_outbox where aggregate_id = ?"
                    + " and published_at is null")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isZero();
      }
    }
    assertThat(relay.relayOnce()).isZero();
    bus.sink("citizen-out").clear();
  }
}
