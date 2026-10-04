package br.gov.sus.nexus.core.ingestion;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Envelopes;
import br.gov.sus.nexus.core.support.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ingestão via Kafka (in-memory): mesma porta de aplicação dos endpoints POST, idempotente. */
@QuarkusTest
class IngestConsumerTest {

  @Inject Bus bus;

  @Test
  void pecAndAgendaEnvelopesAreProcessedOnceThroughTheApplicationServices() {
    String cns = Fixtures.randomProvisionalCns();
    Map<String, Object> registration =
        Registration.of("Ingestão Kafka Souza", LocalDate.of(1990, 4, 4))
            .mother("Mãe Ingestão")
            .cns(cns)
            .territory("1234567", "0000123456", "02")
            .build();
    String payload = Envelopes.build(TENANT_A, "sus.ingest.pec.citizen", registration, null);
    bus.send("ingest-pec-in", payload);
    bus.send("ingest-pec-in", payload); // duplicata: processada uma vez

    Await.until(
        "cidadão criado pela ingestão",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/citizens?identifier=CNS|" + cns)
                    .jsonPath()
                    .getList("items")
                    .size()
                == 1);
    String citizenId =
        aps(TENANT_A).get("/api/v1/citizens?identifier=CNS|" + cns).path("items[0].id");
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId)
        .then()
        .body("display_name", equalTo("Ingestão Kafka Souza"))
        .body("attribute_provenance.legal_name.source_system", equalTo("ESUS_APS_PEC"));

    Map<String, Object> appointment = new LinkedHashMap<>();
    appointment.put(
        "source",
        Map.of(
            "system", "ESUS_APS_PEC",
            "connector", "connector-pec",
            "source_record_id", "AGENDA-" + System.nanoTime()));
    appointment.put("citizen_ref", Map.of("identifier_system", "CNS", "identifier_value", cns));
    appointment.put("status", "booked");
    appointment.put("kind", "direct");
    appointment.put("service_code", "0301010064");
    appointment.put("code_system", "SIGTAP");
    appointment.put("health_unit_cnes", "1234567");
    appointment.put("scheduled_start", OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).toString());
    String aptPayload =
        Envelopes.build(TENANT_A, "sus.ingest.agenda.appointment", appointment, null);
    bus.send("ingest-agenda-in", aptPayload);
    bus.send("ingest-agenda-in", aptPayload);

    Await.until(
        "agendamento criado pela ingestão",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/appointments?citizen_id=" + citizenId)
                    .jsonPath()
                    .getList("items")
                    .size()
                == 1);
    aps(TENANT_A)
        .get("/api/v1/appointments?citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(1))
        .body("items[0].source_system", equalTo("ESUS_APS_PEC"));

    // envelope de outro tenant não vaza para o tenant A
    String otherCns = Fixtures.randomProvisionalCns();
    String other =
        Envelopes.build(
            Api.TENANT_B,
            "sus.ingest.pec.citizen",
            Registration.of("Outro Tenant", LocalDate.of(1991, 1, 1)).cns(otherCns).build(),
            null);
    bus.send("ingest-pec-in", other);
    Await.until(
        "cidadão do tenant B criado",
        () ->
            !aps(Api.TENANT_B)
                .get("/api/v1/citizens?identifier=CNS|" + otherCns)
                .jsonPath()
                .getList("items")
                .isEmpty());
    assertThat(
            aps(TENANT_A)
                .get("/api/v1/citizens?identifier=CNS|" + otherCns)
                .jsonPath()
                .getList("items"))
        .isEmpty();
  }
}
