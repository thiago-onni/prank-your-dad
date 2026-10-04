package br.gov.sus.nexus.fhir.projection;

import static br.gov.sus.nexus.fhir.FhirTestSupport.FHIR;
import static br.gov.sus.nexus.fhir.FhirTestSupport.TENANT_A;
import static br.gov.sus.nexus.fhir.FhirTestSupport.as;
import static br.gov.sus.nexus.fhir.FhirTestSupport.await;
import static br.gov.sus.nexus.fhir.FhirTestSupport.clinician;
import static br.gov.sus.nexus.fhir.FhirTestSupport.fixture;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Consumidor de projeção com conector em memória e core simulado por WireMock. */
@QuarkusTest
@QuarkusTestResource(CoreWireMockResource.class)
class KafkaProjectionConsumerTest {

  @Inject
  @Connector("smallrye-in-memory")
  InMemoryConnector connector;

  @Inject ProjectionEventHandler handler;
  @Inject ProjectionInboxRepository inbox;

  @BeforeEach
  void stubCore() {
    WireMockServer wm = CoreWireMockResource.server();
    wm.resetAll();
    wm.stubFor(
        post(urlEqualTo("/auth/token"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"tok-123\",\"expires_in\":300}")));
    wm.stubFor(
        get(urlEqualTo("/api/v1/appointments/apt_01J0000000000000000000APT7"))
            .withHeader("Authorization", equalTo("Bearer tok-123"))
            .withHeader("X-Tenant-Id", equalTo(TENANT_A))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(fixture("canonical-appointment.json").replace("APT1", "APT7"))));
    as("core", "system/*.write", TENANT_A)
        .contentType("application/json")
        .body(fixture("canonical-health-unit.json"))
        .post("/internal/projections/health-unit")
        .then()
        .statusCode(Matchers.anyOf(Matchers.equalTo(200), Matchers.equalTo(201)));
  }

  @Test
  void appointmentEventFetchesCanonicalAndProjectsOnce() {
    String event =
        fixture("event-appointment.json").replace("EVT1", "EVK1").replace("APT1", "APT7");
    connector.source("schedule-appointment").send(event);
    await(() -> inbox.alreadyProcessed(TENANT_A, "evt_01J0000000000000000000EVK1"), 15_000);

    clinician()
        .get(FHIR + "/Appointment/01J0000000000000000000APT7")
        .then()
        .statusCode(200)
        .body("participant[1].actor.type", Matchers.equalTo("Location"))
        .body("participant[1].actor.identifier.value", Matchers.equalTo("2112345"));
    CoreWireMockResource.server()
        .verify(
            1, getRequestedFor(urlEqualTo("/api/v1/appointments/apt_01J0000000000000000000APT7")));

    // mesmo event_id → ignorado pelo inbox (sem nova chamada ao core)
    ProjectionEventHandler.Outcome again =
        handler.handle(ProjectionEventHandler.TOPIC_APPOINTMENT, event);
    assertThat(again.skipped()).isTrue();
    CoreWireMockResource.server()
        .verify(
            1, getRequestedFor(urlEqualTo("/api/v1/appointments/apt_01J0000000000000000000APT7")));

    // Provenance com o event_id
    as("auditor", "user/Provenance.read", TENANT_A)
        .queryParam("target", "Appointment/01J0000000000000000000APT7")
        .get(FHIR + "/Provenance")
        .then()
        .statusCode(200)
        .body(
            "entry.resource.extension.flatten().findAll { it.url == '"
                + br.gov.sus.nexus.fhir.FhirConstants.EXT_EVENT_ID
                + "' }.valueString",
            Matchers.hasItem("evt_01J0000000000000000000EVK1"));
  }

  @Test
  void encounterEventIsProjectedFromEnvelopeWithSensitivity() {
    connector.source("aps-encounter").send(fixture("event-encounter.json"));
    await(() -> inbox.alreadyProcessed(TENANT_A, "evt_01J0000000000000000000EVT2"), 15_000);
    clinician()
        .get(FHIR + "/Encounter/01J0000000000000000000ENC2")
        .then()
        .statusCode(200)
        .body("class.code", Matchers.equalTo("HH"))
        .body("subject.reference", Matchers.equalTo("Patient/01HZX4Y5K6M7N8P9Q0R1S2T3U4"))
        .body("meta.security.code", Matchers.hasItem("highly_restricted"));
    // escopo restrito não enxerga o atendimento highly_restricted
    as("r", "user/Encounter.rs", TENANT_A)
        .get(FHIR + "/Encounter/01J0000000000000000000ENC2")
        .then()
        .statusCode(403);
  }

  @Test
  void handlerRejectsInvalidEnvelopesAndUnknownTopics() {
    assertThatThrownBy(() -> handler.handle(ProjectionEventHandler.TOPIC_TASK, "{not json"))
        .isInstanceOf(ProjectionException.class);
    assertThatThrownBy(
            () -> handler.handle(ProjectionEventHandler.TOPIC_TASK, "{\"event_type\":\"x\"}"))
        .isInstanceOf(ProjectionException.class);
    assertThatThrownBy(() -> handler.handle("sus.unknown.v1", fixture("event-appointment.json")))
        .isInstanceOf(ProjectionException.class);
    // evento sem o id esperado em data
    assertThatThrownBy(
            () ->
                handler.handle(
                    ProjectionEventHandler.TOPIC_TASK,
                    fixture("event-appointment.json").replace("EVT1", "EVT9")))
        .isInstanceOf(ProjectionException.class)
        .hasMessageContaining("task_id");
    // core indisponível → exceção (nack), nada gravado no inbox
    CoreWireMockResource.server()
        .stubFor(
            get(urlEqualTo("/api/v1/tasks/task_01J0000000000000000000TSK9"))
                .willReturn(aResponse().withStatus(503)));
    String event =
        fixture("event-appointment.json")
            .replace("EVT1", "EVT8")
            .replace(
                "\"appointment_id\": \"apt_01J0000000000000000000APT1\"",
                "\"task_id\": \"task_01J0000000000000000000TSK9\"");
    assertThatThrownBy(() -> handler.handle(ProjectionEventHandler.TOPIC_TASK, event))
        .isInstanceOf(RuntimeException.class);
    assertThat(inbox.alreadyProcessed(TENANT_A, "evt_01J0000000000000000000EVT8")).isFalse();
  }
}
