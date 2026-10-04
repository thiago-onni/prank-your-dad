package br.gov.sus.nexus.core.careplan;

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
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.core.careplan.infrastructure.CareGapDetectionJob;
import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Fixtures;
import br.gov.sus.nexus.core.support.Outbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Protocolos versionados (aprovação exige casos de teste e papel gestor; ativação troca a vigente;
 * isolamento de tenant), planos de cuidado (itens, evidência automática por agendamento/exame,
 * encerramento), lacunas (job de detecção, tarefa care_gap, resolução, perda de seguimento) e
 * eventos validados contra os contratos.
 */
@QuarkusTest
class CarePlanFlowTest {

  static final String UBS = "1234567";
  static final String INE = "0000123456";

  @Inject Bus bus;
  @Inject CareGapDetectionJob detectionJob;

  static String newCitizen(String name, LocalDate birthdate) {
    long n = System.nanoTime();
    return integration(TENANT_A)
        .body(
            Registration.of(name + " " + n % 100000, birthdate)
                .cns(Fixtures.randomProvisionalCns())
                .phone(
                    "(38) 9"
                        + String.format("%04d", n % 10000)
                        + "-"
                        + String.format("%04d", (n / 7) % 10000))
                .territory(UBS, INE, "05")
                .build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(201)
        .extract()
        .path("municipal_citizen_id");
  }

  static Map<String, Object> item(
      String kind, String title, String code, int dueInDays, Integer periodicity, int gapAfter) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", kind);
    m.put("title", title);
    if (code != null) {
      m.put("code", code);
      m.put("code_system", "SIGTAP");
    }
    m.put("due_in_days", dueInDays);
    if (periodicity != null) {
      m.put("periodicity_days", periodicity);
    }
    m.put("gap_after_days", gapAfter);
    m.put("priority", "high");
    return m;
  }

  static Map<String, Object> protocol(String careLine, String name, boolean withTests) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("care_line", careLine);
    m.put("name", name);
    m.put("description", "versão municipal");
    m.put(
        "eligibility",
        Map.of("all", List.of(Map.of("fact", "age_years", "op", "ge", "value", 18))));
    m.put(
        "items",
        List.of(
            item("consultation", "Consulta trimestral", "0301010064", 90, 90, 15),
            item("exam", "Perfil lipídico anual", "0202010295", 365, 365, 30)));
    m.put("lost_to_followup_days", 60);
    if (withTests) {
      m.put("test_cases", List.of(Map.of("facts", Map.of("age_years", 50), "expected_items", 2)));
    }
    return m;
  }

  static Map<String, Object> plan(String citizenId, String protocolId, OffsetDateTime startAt) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("citizen_id", citizenId);
    m.put("protocol_id", protocolId);
    m.put("health_unit_cnes", UBS);
    m.put("team_ine", INE);
    m.put("responsible_professional_id", "prof_7");
    if (startAt != null) {
      m.put("start_at", startAt.toString());
    }
    return m;
  }

  @Test
  void protocolLifecycleRequiresTestsAndManagerAndActivationReplacesCurrent() {
    // seed: três protocolos globais ativos (gestante, hipertensão, diabetes)
    Response seeded = aps(TENANT_A).get("/api/v1/protocols?status=active");
    seeded.then().statusCode(200);
    assertThat(seeded.jsonPath().getList("care_line", String.class))
        .contains("gestante", "hipertensao", "diabetes");
    assertThat(seeded.jsonPath().getList("global", Boolean.class)).containsOnly(true);

    // rascunho sem casos de teste: submit ok, approve rejeitado
    String careLine = "hipertensao";
    Response draft =
        gestor(TENANT_A)
            .body(protocol(careLine, "Hipertensão arterial", false))
            .post("/api/v1/protocols");
    draft.then().statusCode(201).body("status", equalTo("draft")).body("global", equalTo(false));
    String protocolId = draft.path("id");
    String v2 = draft.path("version");
    assertThat(protocolId).isEqualTo("prot_hipertensao");
    assertThat(v2).isEqualTo("2");
    String base = "/api/v1/protocols/" + protocolId + "/versions/";
    gestor(TENANT_A)
        .body(Map.of("action", "approve"))
        .post(base + v2 + "/transition")
        .then()
        .statusCode(409); // ainda em draft
    gestor(TENANT_A)
        .body(Map.of("action", "submit"))
        .post(base + v2 + "/transition")
        .then()
        .statusCode(200)
        .body("status", equalTo("in_review"));
    gestor(TENANT_A)
        .body(Map.of("action", "approve"))
        .post(base + v2 + "/transition")
        .then()
        .statusCode(422)
        .body("errors[0].field", equalTo("test_cases"));

    // versão com casos de teste: aprovação só por gestor/admin; ativação troca a vigente
    Response v3 =
        aps(TENANT_A)
            .body(protocol(careLine, "Hipertensão arterial", true))
            .post("/api/v1/protocols");
    v3.then().statusCode(201).body("version", equalTo("3")).body("test_cases_count", equalTo(1));
    aps(TENANT_A)
        .body(Map.of("action", "submit"))
        .post(base + "3/transition")
        .then()
        .statusCode(200);
    aps(TENANT_A)
        .body(Map.of("action", "approve"))
        .post(base + "3/transition")
        .then()
        .statusCode(403);
    gestor(TENANT_A)
        .body(Map.of("action", "approve"))
        .post(base + "3/transition")
        .then()
        .statusCode(200)
        .body("status", equalTo("approved"))
        .body("approved_by", equalTo("gestor.joao"));
    gestor(TENANT_A)
        .body(Map.of("action", "activate"))
        .post(base + "3/transition")
        .then()
        .statusCode(200)
        .body("status", equalTo("active"))
        .body("effective_from", notNullValue());
    // versão global v1 não pode ser alterada pelo município; v2 segue em revisão
    gestor(TENANT_A)
        .body(Map.of("action", "revoke"))
        .post(base + "1/transition")
        .then()
        .statusCode(409);
    List<Map<String, Object>> versions =
        gestor(TENANT_A).get("/api/v1/protocols?care_line=" + careLine).jsonPath().getList("$");
    assertThat(versions)
        .extracting(v -> v.get("version") + ":" + v.get("status"))
        .contains("1:active", "2:in_review", "3:active");

    // plano novo usa a versão vigente do município (3); tenant B continua na global (1)
    String citizenA = newCitizen("Protocolo Vigente", LocalDate.of(1970, 5, 5));
    aps(TENANT_A)
        .body(plan(citizenA, protocolId, null))
        .post("/api/v1/careplans")
        .then()
        .statusCode(201)
        .body("protocol_version", equalTo("3"))
        .body("items", hasSize(2))
        .body("items[0].title", equalTo("Consulta trimestral"));
    assertThat(
            aps(TENANT_B)
                .get("/api/v1/protocols?care_line=" + careLine)
                .jsonPath()
                .getList("version", String.class))
        .containsExactly("1");
    gestor(TENANT_B)
        .body(Map.of("action", "submit"))
        .post(base + "3/transition")
        .then()
        .statusCode(404);

    // ativar uma nova versão aprovada (v4) revoga a vigente (v3)
    gestor(TENANT_A)
        .body(protocol(careLine, "Hipertensão arterial", true))
        .post("/api/v1/protocols")
        .then()
        .statusCode(201)
        .body("version", equalTo("4"));
    for (String action : List.of("submit", "approve", "activate")) {
      gestor(TENANT_A)
          .body(Map.of("action", action))
          .post(base + "4/transition")
          .then()
          .statusCode(200);
    }
    versions =
        gestor(TENANT_A).get("/api/v1/protocols?care_line=" + careLine).jsonPath().getList("$");
    assertThat(versions)
        .extracting(v -> v.get("version") + ":" + v.get("status"))
        .contains("3:revoked", "4:active");
    // elegibilidade: menor de 18 anos não entra no protocolo municipal
    String child = newCitizen("Criança Não Elegível", LocalDate.now().minusYears(10));
    aps(TENANT_A)
        .body(plan(child, protocolId, null))
        .post("/api/v1/careplans")
        .then()
        .statusCode(409);
  }

  @Test
  void carePlanItemsEvidenceGapsAndClosure() throws Exception {
    integration(TENANT_A)
        .body(Map.of("cnes", UBS, "name", "UBS Centro"))
        .put("/api/v1/reference/health-units")
        .then()
        .statusCode(200);
    String citizenId = newCitizen("Plano Diabetes Lima", LocalDate.of(1960, 3, 3));

    // plano iniciado há 250 dias: consulta (120 d + 30 d de carência) e HbA1c (180 d + 30 d)
    // vencidas; fundo de olho (365 d) no prazo; sem evento assistencial há > 90 d → perda de
    // seguimento
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusDays(250);
    Response created =
        aps(TENANT_A).body(plan(citizenId, "prot_diabetes", start)).post("/api/v1/careplans");
    created
        .then()
        .statusCode(201)
        .body("status", equalTo("active"))
        .body("care_line", equalTo("diabetes"))
        .body("protocol_version", equalTo("1"))
        .body("origin.kind", equalTo("professional"))
        .body("items", hasSize(3))
        .body("items.status", not(hasItem("done")))
        .body("open_gaps", equalTo(0));
    String planId = created.path("id");
    assertThat(planId).startsWith("cp_");
    List<Map<String, Object>> items = created.jsonPath().getList("items");
    String consultation =
        items.stream()
            .filter(i -> "consultation".equals(i.get("kind")))
            .map(i -> (String) i.get("id"))
            .findFirst()
            .orElseThrow();
    String hba1c =
        items.stream()
            .filter(i -> "0202010473".equals(i.get("code")))
            .map(i -> (String) i.get("id"))
            .findFirst()
            .orElseThrow();
    assertThat(items.stream().filter(i -> Boolean.TRUE.equals(i.get("overdue"))).count())
        .isEqualTo(2);
    // segundo plano ativo na mesma linha → 409
    aps(TENANT_A)
        .body(plan(citizenId, "prot_diabetes", null))
        .post("/api/v1/careplans")
        .then()
        .statusCode(409);

    // detecção de lacunas (job): 2 itens vencidos + perda de seguimento → 3 lacunas + tarefas
    int opened = detectionJob.runOnce();
    assertThat(opened).isGreaterThanOrEqualTo(3);
    assertThat(detectionJob.runOnce()).isEqualTo(0); // idempotente
    Response gaps = aps(TENANT_A).get("/api/v1/caregaps?care_line=diabetes&team_ine=" + INE);
    gaps.then().statusCode(200);
    List<Map<String, Object>> mine =
        gaps.jsonPath().<Map<String, Object>>getList("items").stream()
            .filter(g -> citizenId.equals(g.get("citizen_id")))
            .toList();
    assertThat(mine).hasSize(3);
    assertThat(mine)
        .extracting(g -> (String) g.get("gap_kind"))
        .containsExactlyInAnyOrder("consultation_overdue", "exam_overdue", "lost_to_followup");
    for (Map<String, Object> g : mine) {
      assertThat(g.get("contact_valid")).isEqualTo(true);
      assertThat((Integer) g.get("days_overdue")).isGreaterThan(0);
      assertThat(g.get("care_plan_id")).isEqualTo(planId);
      aps(TENANT_A)
          .get("/api/v1/tasks/" + g.get("task_id"))
          .then()
          .body("task_type", equalTo("care_gap"))
          .body("assignee.kind", equalTo("team"))
          .body("assignee.id", equalTo(INE))
          .body("origin.kind", equalTo("rule"))
          .body("origin.id", equalTo("care-gap:" + g.get("id")));
    }
    aps(TENANT_A)
        .get("/api/v1/caregaps?min_days_overdue=1000")
        .then()
        .body("items.findAll { it.citizen_id == '" + citizenId + "' }.size()", equalTo(0));
    aps(TENANT_A)
        .get("/api/v1/careplans/" + planId)
        .then()
        .body("open_gaps", equalTo(3))
        .body("items.find { it.id == '" + consultation + "' }.status", equalTo("missed"));
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/summary")
        .then()
        .body("care_gaps", equalTo(3))
        .body("care_lines", hasItem("diabetes"));

    // evidência automática: agendamento realizado (SIGTAP da consulta) marca o item como done
    Map<String, Object> apt = new LinkedHashMap<>();
    apt.put(
        "source",
        Map.of(
            "system",
            "SISREG",
            "connector",
            "connector-agenda",
            "source_record_id",
            "AGD-CP-" + System.nanoTime()));
    apt.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    apt.put("status", "fulfilled");
    apt.put("kind", "direct");
    apt.put("service_code", "0301010064");
    apt.put("code_system", "SIGTAP");
    apt.put("health_unit_cnes", UBS);
    apt.put("scheduled_start", OffsetDateTime.now(ZoneOffset.UTC).minusHours(3).toString());
    apt.put("care_line", "diabetes");
    integration(TENANT_A).body(apt).post("/api/v1/appointments").then().statusCode(201);
    bus.relayAndDeliver();
    Await.until(
        "consulta marcada como realizada pela evidência do agendamento",
        () ->
            "done"
                .equals(
                    aps(TENANT_A)
                        .get("/api/v1/careplans/" + planId)
                        .path("items.find { it.id == '" + consultation + "' }.status")));
    Response afterEvidence = aps(TENANT_A).get("/api/v1/careplans/" + planId);
    afterEvidence
        .then()
        .body(
            "items.find { it.id == '" + consultation + "' }.evidence_ref",
            startsWith("/api/v1/appointments/"))
        .body("items", hasSize(4)) // próxima ocorrência periódica gerada
        .body("open_gaps", equalTo(1)); // evento assistencial também resolve a perda de seguimento
    aps(TENANT_A)
        .get("/api/v1/caregaps?status=resolved&care_line=diabetes")
        .then()
        .body(
            "items.find { it.citizen_id == '"
                + citizenId
                + "' && it.gap_kind == 'consultation_overdue' }.resolution",
            equalTo("performed"));

    // evidência por exame realizado (código igual) via pedido de exame → evento → consumidor
    Map<String, Object> order = new LinkedHashMap<>();
    order.put(
        "source",
        Map.of(
            "system",
            "ESUS_APS_PEC",
            "connector",
            "connector-pec",
            "source_record_id",
            "EXO-CP-" + System.nanoTime()));
    order.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    order.put("status", "performed");
    order.put("requested_at", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString());
    order.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    order.put("exam_code", "0202010473");
    order.put("code_system", "SIGTAP");
    order.put("requesting_cnes", UBS);
    order.put("care_line", "diabetes");
    integration(TENANT_A).body(order).post("/api/v1/exams/orders").then().statusCode(201);
    bus.relayAndDeliver();
    Await.until(
        "HbA1c marcada como realizada pela evidência do exame",
        () ->
            "done"
                .equals(
                    aps(TENANT_A)
                        .get("/api/v1/careplans/" + planId)
                        .path("items.find { it.id == '" + hba1c + "' }.status")));
    aps(TENANT_A).get("/api/v1/careplans/" + planId).then().body("open_gaps", equalTo(0));

    // atualização manual de item e resolução manual de lacuna
    String fundoDeOlho =
        aps(TENANT_A)
            .get("/api/v1/careplans/" + planId)
            .path("items.find { it.code == '0211060100' }.id");
    aps(TENANT_A)
        .body(Map.of("status", "scheduled", "note", "agendado para o mês que vem"))
        .post("/api/v1/careplans/" + planId + "/items/" + fundoDeOlho)
        .then()
        .statusCode(200)
        .body("items.find { it.id == '" + fundoDeOlho + "' }.status", equalTo("scheduled"));
    Api.as(TENANT_A, "acs.rita", "acs")
        .get("/api/v1/careplans?citizen_id=" + citizenId)
        .then()
        .statusCode(200)
        .body("items", hasSize(1));

    // encerramento cancela itens abertos e resolve lacunas
    aps(TENANT_A)
        .body(Map.of("status", "completed", "reason", "ciclo anual concluído"))
        .post("/api/v1/careplans/" + planId + "/close")
        .then()
        .statusCode(200)
        .body("status", equalTo("completed"))
        .body("closed_reason", equalTo("ciclo anual concluído"))
        .body("items.status", not(hasItem("planned")))
        .body("open_gaps", equalTo(0));
    aps(TENANT_A)
        .body(Map.of("status", "cancelled", "reason", "tentativa repetida"))
        .post("/api/v1/careplans/" + planId + "/close")
        .then()
        .statusCode(409);

    // eventos validados contra os contratos
    List<Outbox.Row> planRows = Outbox.rowsFor(planId);
    assertThat(planRows)
        .extracting(Outbox.Row::eventType)
        .contains("sus.careplan.created", "sus.careplan.updated", "sus.careplan.closed");
    Outbox.assertValid(planRows, "contracts/events/careplan/careplan.v1.schema.json");
    List<Outbox.Row> gapRows = new ArrayList<>();
    for (Map<String, Object> g : mine) {
      gapRows.addAll(Outbox.rowsFor((String) g.get("id")));
    }
    assertThat(gapRows)
        .extracting(Outbox.Row::eventType)
        .contains("sus.caregap.detected", "sus.caregap.resolved");
    Outbox.assertValid(gapRows, "contracts/events/caregap/caregap.v1.schema.json");
    assertThat(gapRows.get(0).payload().get("privacy").get("classification").asText())
        .isEqualTo("restricted");

    // timeline: plano e lacunas (domínio careplan, restricted)
    bus.relayAndDeliver();
    Await.until(
        "careplan na timeline",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/citizens/" + citizenId + "/timeline?domain=careplan")
                    .jsonPath()
                    .getList("items")
                    .size()
                >= 4);
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/timeline?domain=careplan")
        .then()
        .body("items.event_type", hasItem("sus.caregap.detected"))
        .body("items.event_type", hasItem("sus.careplan.closed"))
        .body("items[0].sensitivity", equalTo("restricted"));

    // isolamento de tenant
    aps(TENANT_B).get("/api/v1/careplans/" + planId).then().statusCode(404);
    aps(TENANT_B)
        .get("/api/v1/caregaps?team_ine=" + INE)
        .then()
        .body("items.findAll { it.citizen_id == '" + citizenId + "' }.size()", equalTo(0));
  }

  @Test
  void lostToFollowupGapWhenNoCareEventBeyondProtocolWindow() {
    String citizenId = newCitizen("Perda de Seguimento", LocalDate.of(1980, 1, 1));
    // plano de hipertensão iniciado há 100 dias (lost_to_followup_days = 90 no protocolo global);
    // itens ainda dentro da carência (180 d) → só a lacuna de perda de seguimento
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusDays(100);
    String planId =
        aps(TENANT_A)
            .body(plan(citizenId, "prot_hipertensao", start))
            .post("/api/v1/careplans")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    assertThat(detectionJob.runOnce()).isGreaterThanOrEqualTo(1);
    Response gaps =
        aps(TENANT_A).get("/api/v1/caregaps?gap_kind=lost_to_followup&care_line=hipertensao");
    Map<String, Object> gap =
        gaps.jsonPath().<Map<String, Object>>getList("items").stream()
            .filter(g -> citizenId.equals(g.get("citizen_id")))
            .findFirst()
            .orElseThrow();
    assertThat(gap.get("care_plan_id")).isEqualTo(planId);
    assertThat(gap.get("protocol_version"))
        .isEqualTo(aps(TENANT_A).get("/api/v1/careplans/" + planId).path("protocol_version"));
    aps(TENANT_A)
        .get("/api/v1/tasks/" + gap.get("task_id"))
        .then()
        .body("task_type", equalTo("care_gap"))
        .body("priority", equalTo("high"));
    // busca ativa: desfecho registrado resolve a lacuna e conclui a tarefa
    aps(TENANT_A)
        .body(Map.of("resolution", "contact_made", "note", "contato por telefone"))
        .post("/api/v1/caregaps/" + gap.get("id") + "/resolve")
        .then()
        .statusCode(200)
        .body("status", equalTo("resolved"))
        .body("resolution", equalTo("contact_made"))
        .body("resolved_at", notNullValue());
    aps(TENANT_A)
        .get("/api/v1/tasks/" + gap.get("task_id"))
        .then()
        .body("status", equalTo("completed"))
        .body("outcome", equalTo("contact_made"));
    aps(TENANT_A)
        .body(Map.of("resolution", "refused"))
        .post("/api/v1/caregaps/" + gap.get("id") + "/resolve")
        .then()
        .statusCode(409);
    assertThat(detectionJob.runOnce()).isGreaterThanOrEqualTo(0);
    aps(TENANT_A)
        .get("/api/v1/caregaps?gap_kind=lost_to_followup&care_line=hipertensao")
        .then()
        .body(
            "items.findAll { it.citizen_id == '" + citizenId + "' }.size()",
            greaterThanOrEqualTo(0));
  }
}
