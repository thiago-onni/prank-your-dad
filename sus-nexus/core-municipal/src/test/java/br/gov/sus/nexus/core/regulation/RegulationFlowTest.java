package br.gov.sus.nexus.core.regulation;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.TENANT_B;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.gestor;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Envelopes;
import br.gov.sus.nexus.core.support.Fixtures;
import br.gov.sus.nexus.core.support.Outbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fila regulatória: registro → pendências (REG-005) → under_review → authorized → scheduled com
 * vínculo de agenda → no_show (tarefa). Filtros, resumo da fila, capacidade, eventos validados
 * contra os schemas, {@code actor_kind} nunca {@code agent}, isolamento de tenant e ingestão.
 */
@QuarkusTest
public class RegulationFlowTest {

  static final String SERVICE = "0401010010";
  static final String UBS = "1234567";
  static final String PROVIDER = "7654321";

  @Inject Bus bus;

  static LocalDate randomBirthdate() {
    long n = System.nanoTime();
    return LocalDate.of(
        1950 + (int) (n % 50), 1 + (int) ((n / 50) % 12), 1 + (int) ((n / 600) % 28));
  }

  public static RequestSpecification regulador(String tenant) {
    return Api.as(tenant, "reg.carla", "regulador");
  }

  static RequestSpecification agente(String tenant) {
    return Api.as(tenant, "agent-regulacao", "agente_ia");
  }

  static Map<String, Object> source(String system, String recordId) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("system", system);
    s.put("connector", "connector-" + system.toLowerCase());
    s.put("source_record_id", recordId);
    s.put("cnes", UBS);
    return s;
  }

  public static Map<String, Object> request(
      String recordId, String citizenId, String kind, String status, String priority) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", source("SISREG", recordId));
    m.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    m.put("kind", kind);
    m.put("status", status);
    m.put("priority", priority);
    m.put("requested_at", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString());
    m.put("requested_service_code", SERVICE);
    m.put("code_system", "SIGTAP");
    m.put("specialty", "ortopedia");
    m.put("requesting_cnes", UBS);
    m.put("requesting_professional_id", "prof_1");
    m.put("justification_present", false);
    m.put("attached_documents_count", 0);
    m.put("cid_code", "M54.5");
    return m;
  }

  public static Map<String, Object> status(
      String recordId, String status, Map<String, Object> extra) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", source("SISREG", recordId));
    m.put("status", status);
    m.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    m.putAll(extra);
    return m;
  }

  public static String newCitizen(String name) {
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
  void requestLifecycleWithIssuesSchedulingNoShowAndEvents() throws Exception {
    integration(TENANT_A)
        .body(Map.of("cnes", UBS, "name", "UBS Centro"))
        .put("/api/v1/reference/health-units")
        .then()
        .statusCode(200);
    String citizenId = newCitizen("Regulação Fluxo Silva");
    String recordId = "REG-" + System.nanoTime();

    // registro: pendências incomplete (justificativa ausente + sem documentos em procedimento)
    Response created =
        integration(TENANT_A)
            .body(request(recordId, citizenId, "procedure", "requested", "priority"))
            .post("/api/v1/regulation/requests");
    created.then().statusCode(201).body("id", notNullValue()).body("status", equalTo("requested"));
    String id = created.path("id");
    assertThat(id).startsWith("reg_");
    List<String> kinds = created.jsonPath().getList("issues.findAll { it.status == 'open' }.kind");
    assertThat(kinds).contains("clinical_justification", "missing_document");
    assertThat(created.jsonPath().getString("sla_due_at")).isNotNull();
    OffsetDateTime requestedAt = OffsetDateTime.parse(created.path("requested_at"));
    OffsetDateTime slaDue = OffsetDateTime.parse(created.path("sla_due_at"));
    assertThat(slaDue).isEqualTo(requestedAt.plusDays(30)); // priority → 30 dias
    assertThat((Integer) created.path("waiting_days")).isEqualTo(1);
    assertThat(created.jsonPath().getString("cid_code")).isNull();

    regulador(TENANT_A)
        .get("/api/v1/regulation/requests?issue=incomplete")
        .then()
        .statusCode(200)
        .body("items.id", hasItem(id));

    // atualização pela origem: completa documentação e vai para análise
    Map<String, Object> updated =
        request(recordId, citizenId, "procedure", "under_review", "priority");
    updated.put("justification_present", true);
    updated.put("attached_documents_count", 2);
    integration(TENANT_A)
        .body(updated)
        .post("/api/v1/regulation/requests")
        .then()
        .statusCode(200)
        .body("status", equalTo("under_review"))
        .body("issues.findAll { it.status == 'open' }", hasSize(0));
    regulador(TENANT_A)
        .get("/api/v1/regulation/requests?issue=incomplete")
        .then()
        .body("items.id", not(hasItem(id)));

    // decisão registrada a partir do sistema oficial
    regulador(TENANT_A)
        .body(
            status(
                recordId,
                "authorized",
                Map.of(
                    "regulator_id", "reg-77", "reason", "autorizado", "provider_cnes", PROVIDER)))
        .post("/api/v1/regulation/requests/" + id + "/status")
        .then()
        .statusCode(200)
        .body("status", equalTo("authorized"))
        .body("regulator_id", equalTo("reg-77"))
        .body("provider_cnes", equalTo(PROVIDER))
        .body("status_history", hasSize(3))
        .body("status_history[2].actor", equalTo("reg-77"));

    // agendamento no sistema de agenda → vínculo pelo source_record_id
    String aptRecord = "AGD-" + System.nanoTime();
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3);
    Map<String, Object> apt = new LinkedHashMap<>();
    apt.put("source", source("SISREG", aptRecord));
    apt.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    apt.put("status", "booked");
    apt.put("kind", "regulated");
    apt.put("service_code", SERVICE);
    apt.put("code_system", "SIGTAP");
    apt.put("health_unit_cnes", PROVIDER);
    apt.put("scheduled_start", start.toString());
    apt.put("regulation_request_id", id);
    String aptId =
        integration(TENANT_A)
            .body(apt)
            .post("/api/v1/appointments")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    integration(TENANT_A)
        .body(status(recordId, "scheduled", Map.of("appointment_source_record_id", aptRecord)))
        .post("/api/v1/regulation/requests/by-source/SISREG/" + recordId + "/status")
        .then()
        .statusCode(200)
        .body("status", equalTo("scheduled"))
        .body("appointment_id", equalTo(aptId))
        .body("scheduled_at", notNullValue());

    // falta → tarefa no_show_recovery
    integration(TENANT_A)
        .body(status(recordId, "no_show", Map.of()))
        .post("/api/v1/regulation/requests/" + id + "/status")
        .then()
        .statusCode(200)
        .body("status", equalTo("no_show"));
    aps(TENANT_A)
        .get("/api/v1/tasks?task_type=no_show_recovery&citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(1))
        .body("items[0].origin.id", equalTo(id))
        .body("items[0].assignee.kind", equalTo("team"));

    // eventos validados contra os dois schemas; actor_kind nunca 'agent'
    List<Outbox.Row> rows = Outbox.rowsFor(id);
    List<Outbox.Row> requestRows =
        rows.stream().filter(r -> r.eventType().startsWith("sus.regulation.request.")).toList();
    List<Outbox.Row> statusRows =
        rows.stream().filter(r -> r.eventType().startsWith("sus.regulation.status.")).toList();
    assertThat(requestRows)
        .extracting(Outbox.Row::eventType)
        .first()
        .isEqualTo("sus.regulation.request.created");
    assertThat(statusRows)
        .extracting(r -> r.payload().get("data").get("status").asText())
        .containsExactly("under_review", "authorized", "scheduled", "no_show");
    Outbox.assertValid(requestRows, "contracts/events/regulation/request.v1.schema.json");
    Outbox.assertValid(statusRows, "contracts/events/regulation/status.v1.schema.json");
    for (Outbox.Row r : statusRows) {
      assertThat(r.payload().get("data").get("actor_kind").asText())
          .isIn(Set.of("regulator", "requester", "provider", "system", "workflow", "connector"));
      assertThat(r.payload().get("privacy").get("classification").asText()).isEqualTo("restricted");
    }
    for (Outbox.Row r : rows) {
      assertThat(r.payload().toString()).doesNotContain("M54.5");
    }

    // timeline do cidadão recebe regulação (domínio regulation, restricted, sem CID)
    bus.relayAndDeliver();
    Await.until(
        "regulação na timeline (2 eventos de pedido + 4 de status)",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/citizens/" + citizenId + "/timeline?domain=regulation")
                    .jsonPath()
                    .getList("items")
                    .size()
                >= 6);
    Response timeline =
        aps(TENANT_A).get("/api/v1/citizens/" + citizenId + "/timeline?domain=regulation");
    assertThat(timeline.jsonPath().getList("items.event_type", String.class))
        .contains("sus.regulation.request.created");
    assertThat(timeline.jsonPath().getList("items.sensitivity", String.class))
        .containsOnly("restricted");
    assertThat(timeline.asString()).doesNotContain("M54.5");

    // isolamento de tenant
    regulador(TENANT_B).get("/api/v1/regulation/requests/" + id).then().statusCode(404);
    regulador(TENANT_B)
        .get("/api/v1/regulation/requests?citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(0));
  }

  @Test
  void agentsCannotChangeStatusButMayRegisterIssuesWithApproval() {
    String citizenId = newCitizen("Regulação Agente Lima");
    String recordId = "REG-AG-" + System.nanoTime();
    String id =
        integration(TENANT_A)
            .body(request(recordId, citizenId, "consultation", "requested", "elective"))
            .post("/api/v1/regulation/requests")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    agente(TENANT_A)
        .body(status(recordId, "authorized", Map.of()))
        .post("/api/v1/regulation/requests/" + id + "/status")
        .then()
        .statusCode(403);
    agente(TENANT_A)
        .body(request(recordId, citizenId, "consultation", "authorized", "urgent"))
        .post("/api/v1/regulation/requests")
        .then()
        .statusCode(403);

    // pendência de agente (aprovação humana já registrada no ai-service)
    agente(TENANT_A)
        .body(
            Map.of(
                "kind", "missing_document",
                "description", "Falta exame de imagem prévio",
                "origin", Map.of("kind", "agent", "id", "agent_regulacao", "version", "1.2")))
        .post("/api/v1/regulation/requests/" + id + "/issues")
        .then()
        .statusCode(201)
        .body("issues.find { it.origin.kind == 'agent' }.kind", equalTo("missing_document"))
        .body("issues.find { it.origin.kind == 'agent' }.status", equalTo("open"));
    // humano não pode se passar por agente; agente não pode omitir a origem
    regulador(TENANT_A)
        .body(Map.of("kind", "other", "description", "x", "origin", Map.of("kind", "agent")))
        .post("/api/v1/regulation/requests/" + id + "/issues")
        .then()
        .statusCode(403);
    agente(TENANT_A)
        .body(Map.of("kind", "other", "description", "x"))
        .post("/api/v1/regulation/requests/" + id + "/issues")
        .then()
        .statusCode(422);
    regulador(TENANT_A)
        .body(Map.of("kind", "clinical_justification", "description", "Justificativa insuficiente"))
        .post("/api/v1/regulation/requests/" + id + "/issues")
        .then()
        .statusCode(201)
        .body("issues.find { it.origin.kind == 'user' }.origin.id", equalTo("reg.carla"));
    // prioridade continua a do sistema oficial
    regulador(TENANT_A)
        .get("/api/v1/regulation/requests/" + id)
        .then()
        .body("priority", equalTo("elective"))
        .body("status", equalTo("requested"));
  }

  @Test
  void duplicateCapacityQueueSummaryAndSorting() {
    String citizenId = newCitizen("Regulação Fila Souza");
    String service = "0205020038";
    String first = "REG-Q1-" + System.nanoTime();
    Map<String, Object> r1 = request(first, citizenId, "exam", "requested", "elective");
    r1.put("requested_service_code", service);
    r1.put("specialty", "cardiologia");
    r1.put("justification_present", true);
    r1.put("requested_at", OffsetDateTime.now(ZoneOffset.UTC).minusDays(40).toString());
    String id1 =
        integration(TENANT_A)
            .body(r1)
            .post("/api/v1/regulation/requests")
            .then()
            .statusCode(201)
            .body("issues.findAll { it.status == 'open' }", hasSize(0))
            .extract()
            .path("id");

    // mesmo cidadão + serviço aberto → duplicate no segundo pedido
    Map<String, Object> r2 =
        request("REG-Q2-" + System.nanoTime(), citizenId, "exam", "requested", "urgent");
    r2.put("requested_service_code", service);
    r2.put("specialty", "cardiologia");
    r2.put("justification_present", true);
    r2.put("requested_at", OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).toString());
    String id2 =
        integration(TENANT_A)
            .body(r2)
            .post("/api/v1/regulation/requests")
            .then()
            .statusCode(201)
            .body("issues.findAll { it.status == 'open' }.kind", hasItem("duplicate"))
            .extract()
            .path("id");
    regulador(TENANT_A)
        .get("/api/v1/regulation/requests?issue=duplicate&citizen_id=" + citizenId)
        .then()
        .body("items.id", hasItem(id2));

    // capacidade: oferta esgotada → no_capacity; oferta liberada → resolvida
    String competence =
        OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMM"));
    integration(TENANT_A)
        .body(
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "provider_cnes", PROVIDER,
                        "service_code", service,
                        "competence", competence,
                        "offered", 10,
                        "used", 10),
                    Map.of(
                        "provider_cnes",
                        "12",
                        "service_code",
                        service,
                        "competence",
                        competence,
                        "offered",
                        1))))
        .post("/api/v1/regulation/capacity")
        .then()
        .statusCode(200)
        .body("created", equalTo(1))
        .body("rejected", equalTo(1));
    regulador(TENANT_A)
        .get("/api/v1/regulation/requests/" + id1)
        .then()
        .body("issues.findAll { it.status == 'open' }.kind", hasItem("no_capacity"));
    regulador(TENANT_A)
        .get("/api/v1/regulation/requests?issue=no_capacity&service_code=" + service)
        .then()
        .body("items.id", hasItem(id1));
    gestor(TENANT_A)
        .body(
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "provider_cnes", PROVIDER,
                        "service_code", service,
                        "competence", competence,
                        "offered", 12,
                        "used", 10))))
        .post("/api/v1/regulation/capacity")
        .then()
        .statusCode(200)
        .body("updated", equalTo(1));
    regulador(TENANT_A)
        .get("/api/v1/regulation/capacity?service_code=" + service)
        .then()
        .body("items", hasSize(1))
        .body("items[0].available", equalTo(2));
    regulador(TENANT_A)
        .get("/api/v1/regulation/requests/" + id1)
        .then()
        .body("issues.findAll { it.status == 'open' }.kind", not(hasItem("no_capacity")));

    // ordenação: waiting_time_desc = mais antigo primeiro; priority_desc = urgente primeiro
    List<String> byWaiting =
        regulador(TENANT_A)
            .get("/api/v1/regulation/requests?service_code=" + service + "&citizen_id=" + citizenId)
            .jsonPath()
            .getList("items.id");
    assertThat(byWaiting).containsExactly(id1, id2);
    List<String> byPriority =
        regulador(TENANT_A)
            .get("/api/v1/regulation/requests?sort=priority_desc&citizen_id=" + citizenId)
            .jsonPath()
            .getList("items.id");
    assertThat(byPriority).containsExactly(id2, id1);
    Response page =
        regulador(TENANT_A).get("/api/v1/regulation/requests?limit=1&citizen_id=" + citizenId);
    assertThat(page.jsonPath().getList("items")).hasSize(1);
    String cursor = page.path("next_cursor");
    assertThat(cursor).isNotNull();
    regulador(TENANT_A)
        .get("/api/v1/regulation/requests?limit=1&citizen_id=" + citizenId + "&cursor=" + cursor)
        .then()
        .body("items[0].id", equalTo(id2));

    // resumo da fila (gestor)
    Response summary =
        gestor(TENANT_A).get("/api/v1/regulation/queues/summary?group_by=service_code");
    summary.then().statusCode(200);
    Map<String, Object> item =
        summary.jsonPath().<Map<String, Object>>getList("items").stream()
            .filter(i -> service.equals(i.get("group_key")))
            .findFirst()
            .orElseThrow();
    assertThat(((Number) item.get("open_requests")).intValue()).isEqualTo(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> byPrio = (Map<String, Object>) item.get("by_priority");
    assertThat(((Number) byPrio.get("urgent")).intValue()).isEqualTo(1);
    assertThat(((Number) item.get("avg_waiting_days")).doubleValue()).isGreaterThan(20.0);
    assertThat(((Number) item.get("p90_waiting_days")).doubleValue()).isGreaterThan(30.0);
    assertThat(((Number) item.get("with_issues")).intValue()).isGreaterThanOrEqualTo(1);
    assertThat(((Number) item.get("capacity_available")).intValue()).isEqualTo(2);
    gestor(TENANT_A)
        .get("/api/v1/regulation/queues/summary?group_by=specialty")
        .then()
        .statusCode(200);
    gestor(TENANT_A).get("/api/v1/regulation/queues/summary?group_by=x").then().statusCode(422);
    aps(TENANT_A).get("/api/v1/regulation/queues/summary").then().statusCode(403);

    // resumo operacional do cidadão
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/summary")
        .then()
        .body("open_regulation_requests", equalTo(2));
  }

  @Test
  void ingestionConsumerHandlesRegistrationAndStatusEnvelopesOnce() {
    String citizenId = newCitizen("Regulação Kafka Pereira");
    String recordId = "REG-K-" + System.nanoTime();
    Map<String, Object> reg = request(recordId, citizenId, "consultation", "requested", "elective");
    String payload = Envelopes.build(TENANT_A, "sus.ingest.regulation.request", reg, null);
    bus.send("ingest-regulation-in", payload);
    bus.send("ingest-regulation-in", payload);
    Await.until(
        "pedido criado pela ingestão",
        () ->
            regulador(TENANT_A)
                    .get("/api/v1/regulation/requests?citizen_id=" + citizenId)
                    .jsonPath()
                    .getList("items")
                    .size()
                == 1);
    String id =
        regulador(TENANT_A)
            .get("/api/v1/regulation/requests?citizen_id=" + citizenId)
            .path("items[0].id");

    Map<String, Object> change =
        status(recordId, "denied", Map.of("regulator_id", "reg-9", "reason", "fora de protocolo"));
    bus.send(
        "ingest-regulation-in",
        Envelopes.build(TENANT_A, "sus.ingest.regulation.status", change, null));
    Await.until(
        "status aplicado pela ingestão",
        () ->
            "denied"
                .equals(
                    regulador(TENANT_A).get("/api/v1/regulation/requests/" + id).path("status")));
    regulador(TENANT_A)
        .get("/api/v1/regulation/requests/" + id)
        .then()
        .body("decision_reason", equalTo("fora de protocolo"))
        .body("status_history", hasSize(greaterThanOrEqualTo(2)));
  }
}
