package br.gov.sus.nexus.core.journey;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.gestor;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Fixtures;
import br.gov.sus.nexus.core.support.Outbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Ponta a ponta: POST cidadão → POST agendamento → noshow → outbox relay → consumidores da timeline
 * → GET timeline/summary. Também filtragem por sensibilidade (ACS) e reatribuição por merge.
 */
@QuarkusTest
class JourneyFlowTest {

  @Inject Bus bus;

  static Map<String, Object> appointment(
      String recordId, String citizenId, String status, OffsetDateTime start) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(
        "source",
        Map.of(
            "system",
            "SISREG",
            "connector",
            "connector-agenda",
            "source_record_id",
            recordId,
            "cnes",
            "1234567"));
    m.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    m.put("status", status);
    m.put("kind", "regulated");
    m.put("service_code", "0301010064");
    m.put("code_system", "SIGTAP");
    m.put("health_unit_cnes", "1234567");
    m.put("scheduled_start", start.toString());
    m.put("care_line", "hipertensao");
    return m;
  }

  @Test
  void timelineAndSummaryEndToEnd() throws Exception {
    integration(TENANT_A)
        .body(Map.of("cnes", "1234567", "name", "UBS Centro"))
        .put("/api/v1/reference/health-units")
        .then()
        .statusCode(200);
    String citizenId =
        integration(TENANT_A)
            .body(
                Registration.of("Jornada Completa Lima", LocalDate.of(1988, 8, 8))
                    .mother("Mãe Jornada")
                    .cns(Fixtures.randomProvisionalCns())
                    .phone("(38) 99999-1234")
                    .territory("1234567", "0000123456", "03")
                    .build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");
    String recordId = "JOR-" + System.nanoTime();
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
    String aptId =
        integration(TENANT_A)
            .body(appointment(recordId, citizenId, "booked", start))
            .post("/api/v1/appointments")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    integration(TENANT_A)
        .body(appointment(recordId, citizenId, "noshow", start))
        .post("/api/v1/appointments")
        .then()
        .statusCode(200);
    integration(TENANT_A)
        .body(
            appointment(
                "JOR-NEXT-" + System.nanoTime(),
                citizenId,
                "booked",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(5)))
        .post("/api/v1/appointments")
        .then()
        .statusCode(201);

    bus.relayAndDeliver();
    Await.until(
        "timeline com 5+ eventos",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/citizens/" + citizenId + "/timeline")
                    .jsonPath()
                    .getList("items")
                    .size()
                >= 5);

    Response timeline = aps(TENANT_A).get("/api/v1/citizens/" + citizenId + "/timeline");
    timeline.then().statusCode(200);
    List<Map<String, Object>> items = timeline.jsonPath().getList("items");
    assertThat(items)
        .extracting(i -> (String) i.get("event_type"))
        .contains(
            "sus.identity.citizen.created",
            "sus.schedule.appointment.created",
            "sus.schedule.appointment.no_show",
            "sus.task.created");
    List<OffsetDateTime> occurred =
        items.stream().map(i -> OffsetDateTime.parse((String) i.get("occurred_at"))).toList();
    for (int i = 1; i < occurred.size(); i++) {
      assertThat(occurred.get(i - 1)).isAfterOrEqualTo(occurred.get(i));
    }
    Map<String, Object> scheduleEvent =
        items.stream()
            .filter(i -> "sus.schedule.appointment.created".equals(i.get("event_type")))
            .findFirst()
            .orElseThrow();
    assertThat(scheduleEvent.get("health_unit_name")).isEqualTo("UBS Centro");
    assertThat(scheduleEvent.get("cnes")).isEqualTo("1234567");
    assertThat(scheduleEvent.get("sensitivity")).isEqualTo("internal");

    String noShowEventId =
        Outbox.rowsFor(aptId).stream()
            .filter(r -> r.eventType().equals("sus.schedule.appointment.no_show"))
            .findFirst()
            .orElseThrow()
            .id();
    Map<String, Object> taskEvent =
        items.stream()
            .filter(i -> "sus.task.created".equals(i.get("event_type")))
            .findFirst()
            .orElseThrow();
    @SuppressWarnings("unchecked")
    List<String> chain = (List<String>) taskEvent.get("correlation_chain");
    assertThat(chain).contains(noShowEventId);
    assertThat(taskEvent.get("detail_ref").toString()).startsWith("/api/v1/tasks/task_");

    // filtros e paginação keyset
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/timeline?domain=schedule&status=noshow")
        .then()
        .body("items", hasSize(1))
        .body("items[0].event_type", equalTo("sus.schedule.appointment.no_show"));
    Response page1 = aps(TENANT_A).get("/api/v1/citizens/" + citizenId + "/timeline?limit=2");
    page1.then().body("items", hasSize(2));
    String cursor = page1.path("next_cursor");
    assertThat(cursor).isNotNull();
    Response page2 =
        aps(TENANT_A).get("/api/v1/citizens/" + citizenId + "/timeline?limit=2&cursor=" + cursor);
    List<String> ids1 = page1.jsonPath().getList("items.id");
    List<String> ids2 = page2.jsonPath().getList("items.id");
    assertThat(ids2).doesNotContainAnyElementsOf(ids1).isNotEmpty();

    // sem finalidade → 400 e access_log de deny; com finalidade → access_log allow
    Api.as(TENANT_A, "dra.ana", "profissional_aps")
        .header("X-Purpose-Of-Use", "")
        .get("/api/v1/citizens/" + citizenId + "/timeline")
        .then()
        .statusCode(400);
    Api.dpo(TENANT_A)
        .get("/api/v1/audit/access?citizen_id=" + citizenId)
        .then()
        .statusCode(200)
        .body(
            "items.findAll { it.resource_type == 'timeline' }.size()",
            org.hamcrest.Matchers.greaterThanOrEqualTo(2));

    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/summary")
        .then()
        .statusCode(200)
        .body("citizen_id", equalTo(citizenId))
        .body("open_tasks", equalTo(1))
        .body("contact_valid", equalTo(true))
        .body("care_lines", hasSize(1))
        .body("care_lines[0]", equalTo("hipertensao"))
        .body("next_appointment_at", org.hamcrest.Matchers.notNullValue());
  }

  @Test
  void acsDoesNotSeeRestrictedClinicalEvents() throws Exception {
    String citizenId =
        integration(TENANT_A)
            .body(
                Registration.of("Sensível Clínico", LocalDate.of(1970, 2, 2))
                    .territory("1234567", "0000123456", "03")
                    .build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");
    bus.relayAndDeliver();
    try (Connection c = Api.adminConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "insert into journey.timeline_event (id, tenant_id, citizen_id, original_citizen_id,"
                    + " domain, event_type, event_id, occurred_at, source_system, status, sensitivity,"
                    + " summary, detail_ref) values (?, ?, ?, ?, ?, ?, ?, now(), 'ESUS_APS_PEC', 'final',"
                    + " ?, 'Atendimento APS', '/api/v1/encounters/x')")) {
      insert(ps, citizenId, "aps", "sus.aps.encounter.created", "restricted");
      insert(ps, citizenId, "exam", "sus.exam.result.available", "highly_restricted");
      insert(ps, citizenId, "schedule", "sus.schedule.appointment.confirmed", "internal");
    }
    Await.until(
        "timeline da identidade projetada",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/citizens/" + citizenId + "/timeline")
                    .jsonPath()
                    .getList("items")
                    .size()
                >= 4);

    List<String> apsTypes =
        aps(TENANT_A)
            .get("/api/v1/citizens/" + citizenId + "/timeline")
            .jsonPath()
            .getList("items.event_type");
    assertThat(apsTypes)
        .contains(
            "sus.aps.encounter.created",
            "sus.exam.result.available",
            "sus.schedule.appointment.confirmed");

    List<String> acsTypes =
        Api.as(TENANT_A, "acs.rita", "acs")
            .get("/api/v1/citizens/" + citizenId + "/timeline")
            .jsonPath()
            .getList("items.event_type");
    assertThat(acsTypes)
        .contains("sus.schedule.appointment.confirmed", "sus.identity.citizen.created")
        .doesNotContain("sus.aps.encounter.created", "sus.exam.result.available");
  }

  @Test
  void mergeReassignsTimelineEventsToSurvivingCitizen() throws Exception {
    String cns = Fixtures.randomProvisionalCns();
    String a =
        integration(TENANT_A)
            .body(Registration.of("Fusão Timeline", LocalDate.of(1965, 6, 6)).cns(cns).build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");
    Response conflict =
        integration(TENANT_A)
            .body(Registration.of("Fusão Timeline", LocalDate.of(1966, 6, 6)).cns(cns).build())
            .post("/api/v1/citizens");
    conflict.then().statusCode(202);
    String b = conflict.path("municipal_citizen_id");
    String caseId = conflict.path("merge_case_id");
    bus.relayAndDeliver();
    Await.until(
        "eventos de ambos projetados",
        () ->
            !aps(TENANT_A)
                    .get("/api/v1/citizens/" + b + "/timeline")
                    .jsonPath()
                    .getList("items")
                    .isEmpty()
                && !aps(TENANT_A)
                    .get("/api/v1/citizens/" + a + "/timeline")
                    .jsonPath()
                    .getList("items")
                    .isEmpty());
    // tarefa mpi_review também foi aberta via evento case_opened
    Await.until(
        "mpi_review aberta",
        () ->
            !aps(TENANT_A)
                .get("/api/v1/tasks?task_type=mpi_review&citizen_id=" + a)
                .jsonPath()
                .getList("items")
                .isEmpty());

    gestor(TENANT_A)
        .body(
            Map.of(
                "surviving_citizen_id",
                a,
                "reason",
                "mesma pessoa, data de nascimento digitada errado"))
        .post("/api/v1/mpi/cases/" + caseId + "/merge")
        .then()
        .statusCode(200);
    bus.relayAndDeliver();
    Await.until(
        "eventos reatribuídos",
        () ->
            aps(TENANT_A)
                .get("/api/v1/citizens/" + b + "/timeline")
                .jsonPath()
                .getList("items")
                .isEmpty());
    List<String> types =
        aps(TENANT_A)
            .get("/api/v1/citizens/" + a + "/timeline")
            .jsonPath()
            .getList("items.event_type");
    assertThat(types).contains("sus.identity.merge.merged");
    assertThat(types.stream().filter("sus.identity.citizen.created"::equals).count()).isEqualTo(2);
    Await.until(
        "tarefa de revisão concluída",
        () ->
            "completed"
                .equals(
                    aps(TENANT_A)
                        .get("/api/v1/tasks?task_type=mpi_review&citizen_id=" + a)
                        .path("items[0].status")));
  }

  private static void insert(
      PreparedStatement ps, String citizenId, String domain, String type, String sensitivity)
      throws Exception {
    ps.setString(1, Ulid.generate(Ulid.TIMELINE_EVENT));
    ps.setString(2, TENANT_A);
    ps.setString(3, citizenId);
    ps.setString(4, citizenId);
    ps.setString(5, domain);
    ps.setString(6, type);
    ps.setString(7, Ulid.generate(Ulid.EVENT));
    ps.setString(8, sensitivity);
    ps.executeUpdate();
  }
}
