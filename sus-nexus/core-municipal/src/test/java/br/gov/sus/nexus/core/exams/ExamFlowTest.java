package br.gov.sus.nexus.core.exams;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.TENANT_B;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.core.regulation.RegulationFlowTest;
import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Envelopes;
import br.gov.sus.nexus.core.support.Fixtures;
import br.gov.sus.nexus.core.support.Outbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pedido de exame: not_scheduled após o prazo → scheduled → performed → resultado crítico (tarefa
 * urgente SEM conteúdo, EXA-008) → documento assinado com access_log → tempos de ciclo. Eventos
 * validados, timeline (ACS não vê laudo), ingestão e isolamento de tenant.
 */
@QuarkusTest
class ExamFlowTest {

  static final String UBS = "1234567";
  static final String LAB = "7654321";

  @Inject Bus bus;

  static LocalDate randomBirthdate() {
    long n = System.nanoTime();
    return LocalDate.of(
        1950 + (int) (n % 50), 1 + (int) ((n / 50) % 12), 1 + (int) ((n / 600) % 28));
  }

  static Map<String, Object> source(String system, String recordId) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("system", system);
    s.put("connector", "connector-" + system.toLowerCase());
    s.put("source_record_id", recordId);
    return s;
  }

  static Map<String, Object> order(String recordId, String citizenId, OffsetDateTime requestedAt) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", source("ESUS_APS_PEC", recordId));
    m.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    m.put("status", "requested");
    m.put("requested_at", requestedAt.toString());
    m.put("exam_code", "0202010473");
    m.put("code_system", "SIGTAP");
    m.put("exam_description", "Hemoglobina glicada");
    m.put("category", "laboratory");
    m.put("priority", "routine");
    m.put("requesting_cnes", UBS);
    m.put("requesting_professional_id", "prof_42");
    m.put("care_line", "diabetes");
    return m;
  }

  static Map<String, Object> status(String recordId, String status, Map<String, Object> extra) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", source("LIS_X", recordId));
    m.put("status", status);
    m.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    m.putAll(extra);
    return m;
  }

  static Map<String, Object> result(String recordId, boolean critical, String documentRef) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", source("LIS_X", recordId));
    m.put("reported_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    m.put("status", "final");
    m.put("critical", critical);
    m.put("performer_cnes", LAB);
    if (documentRef != null) {
      m.put("document_ref", documentRef);
      m.put("document_content_type", "application/pdf");
      m.put("document_sha256", "a".repeat(64));
    }
    m.put(
        "observations",
        List.of(
            Map.of(
                "code", "4548-4",
                "code_system", "LOINC",
                "value", 11.7,
                "unit", "%",
                "abnormal", true,
                "value_text_masked", "TEXTO-LIVRE-PROIBIDO")));
    return m;
  }

  static String newCitizen(String name) {
    return integration(TENANT_A)
        .body(
            Registration.of(name + " " + System.nanoTime() % 100000, randomBirthdate())
                .cns(Fixtures.randomProvisionalCns())
                .territory(UBS, "0000123456", "01")
                .build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(201)
        .extract()
        .path("municipal_citizen_id");
  }

  @Test
  void examCycleWithCriticalResultDocumentLinkAndCycleTimes() throws Exception {
    integration(TENANT_A)
        .body(Map.of("cnes", UBS, "name", "UBS Centro"))
        .put("/api/v1/reference/health-units")
        .then()
        .statusCode(200);
    String citizenId = newCitizen("Exame Fluxo Alves");
    String recordId = "EXO-" + System.nanoTime();

    // pedido antigo sem agendamento → not_scheduled (EXA-004)
    Response created =
        integration(TENANT_A)
            .body(order(recordId, citizenId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(20)))
            .post("/api/v1/exams/orders");
    created
        .then()
        .statusCode(201)
        .body("status", equalTo("requested"))
        .body("issues", hasItem("not_scheduled"));
    String id = created.path("id");
    assertThat(id).startsWith("exo_");
    aps(TENANT_A)
        .get("/api/v1/exams/orders?issue=not_scheduled&citizen_id=" + citizenId)
        .then()
        .body("items.id", hasItem(id));
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/summary")
        .then()
        .body("pending_exams", equalTo(1));

    // agendado → scheduled_at; realizado → performed_at
    OffsetDateTime scheduledAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
    aps(TENANT_A)
        .body(status(recordId, "scheduled", Map.of("scheduled_at", scheduledAt.toString())))
        .post("/api/v1/exams/orders/" + id + "/status")
        .then()
        .statusCode(200)
        .body("status", equalTo("scheduled"))
        .body("scheduled_at", notNullValue())
        .body("issues", not(hasItem("not_scheduled")));
    integration(TENANT_A)
        .body(status(recordId, "performed", Map.of("performer_cnes", LAB)))
        .post("/api/v1/exams/orders/" + id + "/status")
        .then()
        .statusCode(200)
        .body("status", equalTo("performed"))
        .body("performed_at", notNullValue())
        .body("performer_cnes", equalTo(LAB));

    // laudo crítico via by-source (conector LIS só conhece o id de origem do pedido)
    Response reported =
        integration(TENANT_A)
            .body(result(recordId, true, "s3://laudos/" + recordId + ".pdf"))
            .post("/api/v1/exams/orders/by-source/ESUS_APS_PEC/" + recordId + "/results");
    reported
        .then()
        .statusCode(201)
        .body("status", equalTo("reported"))
        .body("reported_at", notNullValue())
        .body("results", hasSize(1))
        .body("results[0].critical", equalTo(true))
        .body("results[0].has_document", equalTo(true))
        .body("results[0].observations_count", equalTo(1))
        .body("results[0].followup_task_id", startsWith("task_"))
        .body("issues", hasItem("critical"));
    String resultId = reported.path("results[0].id");
    String taskId = reported.path("results[0].followup_task_id");
    assertThat(reported.asString()).doesNotContain("11.7").doesNotContain("TEXTO-LIVRE");

    // EXA-008: tarefa urgente ao profissional solicitante, sem conteúdo do resultado
    Response task = aps(TENANT_A).get("/api/v1/tasks/" + taskId);
    task.then()
        .statusCode(200)
        .body("task_type", equalTo("exam_result_followup"))
        .body("priority", equalTo("urgent"))
        .body("assignee.kind", equalTo("user"))
        .body("assignee.id", equalTo("prof_42"))
        .body("citizen_id", equalTo(citizenId));
    assertThat(task.asString())
        .doesNotContain("11.7")
        .doesNotContain("4548-4")
        .doesNotContain("TEXTO-LIVRE");

    // documento: URL assinada curta + access_log com finalidade; 404 sem documento
    Response doc =
        aps(TENANT_A).get("/api/v1/exams/orders/" + id + "/results/" + resultId + "/document");
    doc.then().statusCode(200).body("content_type", equalTo("application/pdf"));
    String url = doc.path("url");
    assertThat(url)
        .startsWith("http://localhost:9000/exam-documents/")
        .contains("&sig=")
        .contains("&exp=");
    OffsetDateTime expires = OffsetDateTime.parse(doc.path("expires_at"));
    assertThat(expires)
        .isAfter(OffsetDateTime.now().plusMinutes(4))
        .isBefore(OffsetDateTime.now().plusMinutes(6));
    Api.as(TENANT_A, "dra.ana", "profissional_aps")
        .header("X-Purpose-Of-Use", "")
        .get("/api/v1/exams/orders/" + id + "/results/" + resultId + "/document")
        .then()
        .statusCode(400);
    Api.as(TENANT_A, "acs.rita", "acs")
        .get("/api/v1/exams/orders/" + id + "/results/" + resultId + "/document")
        .then()
        .statusCode(403);
    Api.dpo(TENANT_A)
        .get("/api/v1/audit/access?citizen_id=" + citizenId)
        .then()
        .body(
            "items.findAll { it.resource_type == 'exam_result' && it.action == 'document_link' }.size()",
            equalTo(1))
        .body(
            "items.find { it.resource_type == 'exam_result' }.purpose",
            equalTo("care_coordination"))
        .body("items.find { it.resource_type == 'exam_result' }.resource_id", equalTo(resultId));
    String secondResultId =
        integration(TENANT_A)
            .body(result(recordId, false, null))
            .post("/api/v1/exams/orders/" + id + "/results")
            .then()
            .statusCode(201)
            .extract()
            .path("results[1].id");
    aps(TENANT_A)
        .get("/api/v1/exams/orders/" + id + "/results/" + secondResultId + "/document")
        .then()
        .statusCode(404);

    // tempos de ciclo (EXA-010): retorno ainda aberto
    Response full = aps(TENANT_A).get("/api/v1/exams/orders/" + id);
    full.then()
        .statusCode(200)
        .body("cycle_times.request_to_schedule", notNullValue())
        .body("cycle_times.schedule_to_perform", notNullValue())
        .body("cycle_times.perform_to_report", notNullValue())
        .body("cycle_times.report_to_followup", nullValue())
        .body("requesting_unit_name", equalTo("UBS Centro"))
        .body("status_history", hasSize(4));
    assertThat(((Number) full.path("cycle_times.request_to_schedule")).doubleValue())
        .isGreaterThan(400.0);
    aps(TENANT_A)
        .body(Map.of("action", "complete", "outcome", "retorno realizado"))
        .post("/api/v1/tasks/" + taskId + "/transition")
        .then()
        .statusCode(200);
    aps(TENANT_A)
        .get("/api/v1/exams/orders/" + id)
        .then()
        .body("cycle_times.report_to_followup", notNullValue())
        .body("issues", not(hasItem("critical")));

    // eventos: ordem (internal) e resultado (restricted, data_ref = document_ref, sem valores)
    List<Outbox.Row> rows = Outbox.rowsFor(id);
    List<Outbox.Row> orderRows =
        rows.stream().filter(r -> r.eventType().startsWith("sus.exam.order.")).toList();
    List<Outbox.Row> resultRows =
        rows.stream().filter(r -> r.eventType().startsWith("sus.exam.result.")).toList();
    assertThat(orderRows)
        .extracting(Outbox.Row::eventType)
        .containsExactly(
            "sus.exam.order.created",
            "sus.exam.order.status_changed",
            "sus.exam.order.status_changed",
            "sus.exam.order.status_changed");
    assertThat(resultRows)
        .extracting(Outbox.Row::eventType)
        .containsExactly(
            "sus.exam.result.available",
            "sus.exam.result.critical_flagged",
            "sus.exam.result.available");
    Outbox.assertValid(orderRows, "contracts/events/exam/order.v1.schema.json");
    Outbox.assertValid(resultRows, "contracts/events/exam/result.v1.schema.json");
    assertThat(resultRows.get(0).payload().get("data_ref").asText())
        .isEqualTo("s3://laudos/" + recordId + ".pdf");
    assertThat(resultRows.get(0).payload().get("privacy").get("classification").asText())
        .isEqualTo("restricted");
    assertThat(resultRows.get(1).payload().get("trace").get("causation_id").asText())
        .isEqualTo(resultRows.get(0).id());
    for (Outbox.Row r : resultRows) {
      assertThat(r.payload().toString()).doesNotContain("11.7").doesNotContain("4548-4");
    }

    // timeline: profissional vê pedido e laudo; ACS não vê sus.exam.result.* (restricted em exam)
    bus.relayAndDeliver();
    Await.until(
        "exame na timeline (4 eventos de pedido + 3 de resultado)",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/citizens/" + citizenId + "/timeline?domain=exam")
                    .jsonPath()
                    .getList("items")
                    .size()
                >= 7);
    Response timeline =
        aps(TENANT_A).get("/api/v1/citizens/" + citizenId + "/timeline?domain=exam");
    assertThat(timeline.jsonPath().getList("items.event_type", String.class))
        .contains("sus.exam.order.created", "sus.exam.result.critical_flagged");
    assertThat(timeline.asString()).doesNotContain("11.7").doesNotContain("s3://laudos");
    Map<String, Object> resultEvent =
        timeline.jsonPath().<Map<String, Object>>getList("items").stream()
            .filter(i -> "sus.exam.result.available".equals(i.get("event_type")))
            .findFirst()
            .orElseThrow();
    assertThat(resultEvent.get("sensitivity")).isEqualTo("restricted");
    assertThat(resultEvent.get("detail_ref")).isEqualTo("/api/v1/exams/orders/" + id);
    List<String> acsTypes =
        Api.as(TENANT_A, "acs.rita", "acs")
            .get("/api/v1/citizens/" + citizenId + "/timeline?domain=exam")
            .jsonPath()
            .getList("items.event_type");
    assertThat(acsTypes)
        .contains("sus.exam.order.created")
        .doesNotContain("sus.exam.result.available", "sus.exam.result.critical_flagged");

    // isolamento de tenant
    aps(TENANT_B).get("/api/v1/exams/orders/" + id).then().statusCode(404);
    aps(TENANT_B)
        .get("/api/v1/exams/orders?citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(0));
    integration(TENANT_B)
        .body(result(recordId, false, null))
        .post("/api/v1/exams/orders/by-source/ESUS_APS_PEC/" + recordId + "/results")
        .then()
        .statusCode(404);
  }

  @Test
  void orderLinksRegulationAndAppointmentBySourceRecord() {
    String citizenId = newCitizen("Exame Vínculo Nunes");
    String regRecord = "REG-X-" + System.nanoTime();
    Map<String, Object> reg =
        RegulationFlowTest.request(regRecord, citizenId, "exam", "authorized", "elective");
    reg.put("requested_service_code", "0202010473");
    reg.put("justification_present", true);
    String regId =
        integration(TENANT_A)
            .body(reg)
            .post("/api/v1/regulation/requests")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    String aptRecord = "AGD-X-" + System.nanoTime();
    Map<String, Object> apt = new LinkedHashMap<>();
    apt.put("source", source("SISREG", aptRecord));
    apt.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    apt.put("status", "booked");
    apt.put("kind", "regulated");
    apt.put("service_code", "0202010473");
    apt.put("health_unit_cnes", LAB);
    apt.put("scheduled_start", OffsetDateTime.now(ZoneOffset.UTC).plusDays(4).toString());
    String aptId =
        integration(TENANT_A)
            .body(apt)
            .post("/api/v1/appointments")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    Map<String, Object> body =
        order("EXO-X-" + System.nanoTime(), citizenId, OffsetDateTime.now(ZoneOffset.UTC));
    body.put("source", source("SISREG", "EXO-SRC-" + System.nanoTime()));
    body.put("regulation_source_record_id", regRecord);
    body.put("appointment_source_record_id", aptRecord);
    integration(TENANT_A)
        .body(body)
        .post("/api/v1/exams/orders")
        .then()
        .statusCode(201)
        .body("regulation_request_id", equalTo(regId))
        .body("appointment_id", equalTo(aptId))
        .body("scheduled_at", notNullValue())
        .body("issues", hasSize(0));
    // reenvio idêntico → 200 sem mudança
    integration(TENANT_A).body(body).post("/api/v1/exams/orders").then().statusCode(200);
  }

  @Test
  void ingestionConsumerHandlesOrderStatusAndResultEnvelopes() {
    String citizenId = newCitizen("Exame Kafka Dias");
    String recordId = "EXO-K-" + System.nanoTime();
    Map<String, Object> body = order(recordId, citizenId, OffsetDateTime.now(ZoneOffset.UTC));
    String payload = Envelopes.build(TENANT_A, "sus.ingest.exam.order", body, null);
    bus.send("ingest-exam-in", payload);
    bus.send("ingest-exam-in", payload);
    Await.until(
        "pedido criado pela ingestão",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/exams/orders?citizen_id=" + citizenId)
                    .jsonPath()
                    .getList("items")
                    .size()
                == 1);
    String id =
        aps(TENANT_A).get("/api/v1/exams/orders?citizen_id=" + citizenId).path("items[0].id");

    Map<String, Object> change = status(recordId, "performed", Map.of());
    change.put("source", source("ESUS_APS_PEC", recordId));
    bus.send("ingest-exam-in", Envelopes.build(TENANT_A, "sus.ingest.exam.status", change, null));
    Await.until(
        "status aplicado pela ingestão",
        () -> "performed".equals(aps(TENANT_A).get("/api/v1/exams/orders/" + id).path("status")));

    Map<String, Object> res = result(recordId, false, "s3://laudos/k.pdf");
    res.put("source", source("ESUS_APS_PEC", recordId));
    bus.send("ingest-exam-in", Envelopes.build(TENANT_A, "sus.ingest.exam.result", res, null));
    Await.until(
        "laudo aplicado pela ingestão",
        () -> "reported".equals(aps(TENANT_A).get("/api/v1/exams/orders/" + id).path("status")));
    aps(TENANT_A)
        .get("/api/v1/exams/orders/" + id)
        .then()
        .body("results", hasSize(1))
        .body("results[0].critical", equalTo(false))
        .body("status_history", hasSize(greaterThanOrEqualTo(3)));
  }
}
