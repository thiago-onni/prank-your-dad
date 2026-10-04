package br.gov.sus.nexus.core.scheduling;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Fixtures;
import br.gov.sus.nexus.core.support.Outbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Agenda: registro por vínculo de origem, histórico, duplicidade (AGE-004) e no-show (AGE-006). */
@QuarkusTest
class AppointmentFlowTest {

  static String citizen(String cns, String name) {
    return integration(TENANT_A)
        .body(
            Registration.of(name, LocalDate.of(1980, 5, 5))
                .mother("Mãe " + name)
                .cns(cns)
                .territory("1234567", "0000123456", "03")
                .build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(201)
        .extract()
        .path("municipal_citizen_id");
  }

  static Map<String, Object> appointment(
      String recordId, Map<String, Object> citizenRef, String status, OffsetDateTime start) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(
        "source",
        Map.of(
            "system", "SISREG",
            "connector", "connector-agenda",
            "source_record_id", recordId,
            "source_record_version", "1",
            "cnes", "1234567"));
    m.put("citizen_ref", citizenRef);
    m.put("status", status);
    m.put("kind", "regulated");
    m.put("service_code", "0301010064");
    m.put("code_system", "SIGTAP");
    m.put("health_unit_cnes", "1234567");
    m.put("professional_id", "prof_x");
    m.put("scheduled_start", start.toString());
    m.put("scheduled_end", start.plusMinutes(30).toString());
    m.put("care_line", "hipertensao");
    return m;
  }

  @Test
  void registerUpdateHistoryAndEvents() throws Exception {
    String cns = Fixtures.randomProvisionalCns();
    String citizenId = citizen(cns, "Agenda Teste Silva");
    String recordId = "SISREG-" + System.nanoTime();
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(10);

    Response created =
        integration(TENANT_A)
            .header("Idempotency-Key", "apt-" + recordId)
            .body(
                appointment(
                    recordId,
                    Map.of("identifier_system", "CNS", "identifier_value", cns),
                    "booked",
                    start))
            .post("/api/v1/appointments");
    created
        .then()
        .statusCode(201)
        .body("id", startsWith("apt_"))
        .body("citizen_id", equalTo(citizenId))
        .body("status", equalTo("booked"))
        .body("status_history", hasSize(1));
    String id = created.path("id");

    // replay idempotente (mesma chave + mesmo corpo)
    integration(TENANT_A)
        .header("Idempotency-Key", "apt-" + recordId)
        .body(
            appointment(
                recordId,
                Map.of("identifier_system", "CNS", "identifier_value", cns),
                "booked",
                start))
        .post("/api/v1/appointments")
        .then()
        .statusCode(201)
        .header("Idempotent-Replayed", equalTo("true"));

    // mesma origem, novo status → 200 + histórico
    integration(TENANT_A)
        .body(appointment(recordId, Map.of("municipal_citizen_id", citizenId), "confirmed", start))
        .post("/api/v1/appointments")
        .then()
        .statusCode(200)
        .body("id", equalTo(id))
        .body("status", equalTo("confirmed"))
        .body("status_history", hasSize(2));

    // reagendamento
    integration(TENANT_A)
        .body(
            appointment(
                recordId,
                Map.of("municipal_citizen_id", citizenId),
                "confirmed",
                start.plusDays(1)))
        .post("/api/v1/appointments")
        .then()
        .statusCode(200)
        .body("scheduled_start", startsWith(start.plusDays(1).toLocalDate().toString()));

    // sem mudança → 200 sem novo evento
    integration(TENANT_A)
        .body(
            appointment(
                recordId,
                Map.of("municipal_citizen_id", citizenId),
                "confirmed",
                start.plusDays(1)))
        .post("/api/v1/appointments")
        .then()
        .statusCode(200);

    aps(TENANT_A)
        .get("/api/v1/appointments?citizen_id=" + citizenId)
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].id", equalTo(id));
    aps(TENANT_A)
        .get("/api/v1/appointments?cnes=1234567&status=confirmed")
        .then()
        .statusCode(200)
        .body("items.size()", greaterThanOrEqualTo(1));
    aps(Api.TENANT_B)
        .get("/api/v1/appointments?citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(0));

    List<Outbox.Row> rows = Outbox.rowsFor(id);
    assertThat(rows)
        .extracting(Outbox.Row::eventType)
        .containsExactly(
            "sus.schedule.appointment.created",
            "sus.schedule.appointment.confirmed",
            "sus.schedule.appointment.rescheduled");
    Outbox.assertValid(rows, "contracts/events/schedule/appointment.v1.schema.json");
    assertThat(rows.get(1).payload().get("data").get("previous_status").asText())
        .isEqualTo("booked");
    assertThat(rows.get(0).payload().get("subject").get("municipal_citizen_id").asText())
        .isEqualTo(citizenId);
    assertThat(rows.get(0).payload().toString()).doesNotContain(cns);
  }

  @Test
  void unknownCitizenIsRejectedWith422() {
    integration(TENANT_A)
        .body(
            appointment(
                "SISREG-x" + System.nanoTime(),
                Map.of("municipal_citizen_id", "cit_00000000000000000000000000"),
                "booked",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)))
        .post("/api/v1/appointments")
        .then()
        .statusCode(422);
  }

  @Test
  void duplicateInWindowIsDetected() throws Exception {
    String citizenId = citizen(Fixtures.randomProvisionalCns(), "Duplicado Agenda");
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(20);
    String first = "DUP-A-" + System.nanoTime();
    String second = "DUP-B-" + System.nanoTime();
    integration(TENANT_A)
        .body(appointment(first, Map.of("municipal_citizen_id", citizenId), "booked", start))
        .post("/api/v1/appointments")
        .then()
        .statusCode(201);
    Response dup =
        integration(TENANT_A)
            .body(
                appointment(
                    second,
                    Map.of("municipal_citizen_id", citizenId),
                    "booked",
                    start.plusHours(30)))
            .post("/api/v1/appointments");
    dup.then().statusCode(201);
    String secondId = dup.path("id");

    Api.as(TENANT_A, "agendador.lia", "agendador")
        .get("/api/v1/appointments/duplicates")
        .then()
        .statusCode(200)
        .body("items.find { it.citizen_id == '" + citizenId + "' }.appointments", hasSize(2))
        .body(
            "items.find { it.citizen_id == '" + citizenId + "' }.service_code",
            equalTo("0301010064"));

    List<Outbox.Row> rows = Outbox.rowsFor(secondId);
    assertThat(rows)
        .extracting(Outbox.Row::eventType)
        .containsExactly(
            "sus.schedule.appointment.created", "sus.schedule.appointment.duplicate_detected");
    Outbox.assertValid(rows, "contracts/events/schedule/appointment.v1.schema.json");

    // fora da janela (72 h) não é duplicidade
    integration(TENANT_A)
        .body(
            appointment(
                "DUP-C-" + System.nanoTime(),
                Map.of("municipal_citizen_id", citizenId),
                "booked",
                start.plusDays(10)))
        .post("/api/v1/appointments")
        .then()
        .statusCode(201);
    assertThat(
            Api.as(TENANT_A, "agendador.lia", "agendador")
                .get("/api/v1/appointments/duplicates")
                .jsonPath()
                .getList("items.findAll { it.citizen_id == '" + citizenId + "' }"))
        .hasSize(1);
  }

  @Test
  void noShowOpensRecoveryTaskForTheCitizensTeam() throws Exception {
    String citizenId = citizen(Fixtures.randomProvisionalCns(), "Faltoso Teste");
    String recordId = "NOSHOW-" + System.nanoTime();
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
    String id =
        integration(TENANT_A)
            .body(
                appointment(
                    recordId, Map.of("municipal_citizen_id", citizenId), "confirmed", start))
            .post("/api/v1/appointments")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    integration(TENANT_A)
        .body(appointment(recordId, Map.of("municipal_citizen_id", citizenId), "noshow", start))
        .post("/api/v1/appointments")
        .then()
        .statusCode(200)
        .body("status", equalTo("noshow"));

    Response tasks =
        aps(TENANT_A).get("/api/v1/tasks?citizen_id=" + citizenId + "&task_type=no_show_recovery");
    tasks
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].status", equalTo("assigned"))
        .body("items[0].priority", equalTo("high"))
        .body("items[0].assignee.kind", equalTo("team"))
        .body("items[0].assignee.id", equalTo("0000123456"))
        .body("items[0].origin.kind", equalTo("rule"))
        .body("items[0].origin.id", equalTo(id))
        .body("items[0].sla_policy_id", equalTo("sla_no_show_recovery"));
    String taskId = tasks.path("items[0].id");

    // reenvio do noshow não duplica a tarefa
    integration(TENANT_A)
        .body(appointment(recordId, Map.of("municipal_citizen_id", citizenId), "noshow", start))
        .post("/api/v1/appointments")
        .then()
        .statusCode(200);
    aps(TENANT_A)
        .get("/api/v1/tasks?citizen_id=" + citizenId + "&task_type=no_show_recovery")
        .then()
        .body("items", hasSize(1));

    List<Outbox.Row> aptRows = Outbox.rowsFor(id);
    assertThat(aptRows)
        .extracting(Outbox.Row::eventType)
        .containsExactly("sus.schedule.appointment.created", "sus.schedule.appointment.no_show");
    String noShowEventId = aptRows.get(1).id();
    List<Outbox.Row> taskRows = Outbox.rowsFor(taskId);
    assertThat(taskRows).extracting(Outbox.Row::eventType).containsExactly("sus.task.created");
    Outbox.assertValid(taskRows, "contracts/events/task/task.v1.schema.json");
    assertThat(taskRows.get(0).payload().get("trace").get("causation_id").asText())
        .isEqualTo(noShowEventId);
  }
}
