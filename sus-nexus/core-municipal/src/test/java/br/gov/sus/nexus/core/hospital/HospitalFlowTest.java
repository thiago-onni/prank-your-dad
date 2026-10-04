package br.gov.sus.nexus.core.hospital;

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
 * Episódio hospitalar: ADT (admissão, transferência) → alta por vínculo de origem com risco por
 * regra versionada, tarefa de contato pós-alta (prioridade por risco, equipe de referência, SLA) →
 * contrarreferência → desfecho do contato (abre plano de cuidado). CID sensível: só equipe/hospital
 * veem; eventos highly_restricted sem CID; timeline sem CID (ACS não vê); summary; reinternação;
 * óbito sem tarefa; ingestão; isolamento de tenant.
 */
@QuarkusTest
class HospitalFlowTest {

  static final String UBS = "1234567";
  static final String HOSPITAL = "2345678";
  static final String INE = "0000123456";

  @Inject Bus bus;

  static Map<String, Object> source(String system, String recordId) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("system", system);
    s.put("connector", "connector-" + system.toLowerCase());
    s.put("source_record_id", recordId);
    s.put("cnes", HOSPITAL);
    return s;
  }

  static Map<String, Object> movement(
      String recordId, String citizenId, String movement, OffsetDateTime occurredAt) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", source("HIS_X", recordId));
    m.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    m.put("hospital_cnes", HOSPITAL);
    m.put("episode_class", "inpatient");
    m.put("movement", movement);
    m.put("occurred_at", occurredAt.toString());
    m.put("ward", "Clínica Médica");
    m.put("bed", "CM-12");
    m.put("admission_source", "emergency");
    return m;
  }

  static Map<String, Object> discharge(
      String recordId, OffsetDateTime dischargedAt, String cid, List<String> careLines) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", source("HIS_X", recordId));
    m.put("discharged_at", dischargedAt.toString());
    m.put("disposition", "home");
    if (cid != null) {
      m.put("principal_diagnosis_cid", cid);
    }
    m.put("procedures_count", 2);
    m.put("followup_plan_present", true);
    m.put("followup_due_days", 7);
    m.put("care_lines", careLines);
    m.put("summary_document_ref", "s3://sumarios/" + recordId + ".pdf");
    m.put("summary_document_sha256", "b".repeat(64));
    return m;
  }

  /** Idade entre 41 e 65 anos (fora da regra de idade ≥ 75) e dados distintos (sem match MPI). */
  static LocalDate randomBirthdate() {
    long n = System.nanoTime();
    return LocalDate.of(
        1960 + (int) (n % 25), 1 + (int) ((n / 50) % 12), 1 + (int) ((n / 600) % 28));
  }

  static String newCitizen(String name) {
    long n = System.nanoTime();
    return integration(TENANT_A)
        .body(
            Registration.of(name + " " + n % 100000, randomBirthdate())
                .cns(Fixtures.randomProvisionalCns())
                .phone(
                    "(38) 9"
                        + String.format("%04d", n % 10000)
                        + "-"
                        + String.format("%04d", (n / 7) % 10000))
                .territory(UBS, INE, "02")
                .build())
        .post("/api/v1/citizens")
        .then()
        .statusCode(201)
        .extract()
        .path("municipal_citizen_id");
  }

  /** Admissão + alta em um passo (reuso por outros testes). */
  static Response admitAndDischarge(
      String citizenId, String recordId, int losDays, String cid, List<String> careLines) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    integration(TENANT_A)
        .body(movement(recordId, citizenId, "admit", now.minusDays(losDays)))
        .post("/api/v1/hospital/episodes")
        .then()
        .statusCode(201);
    Response r =
        integration(TENANT_A)
            .body(discharge(recordId, now, cid, careLines))
            .post("/api/v1/hospital/episodes/by-source/HIS_X/" + recordId + "/discharge");
    r.then().statusCode(200);
    return r;
  }

  @Test
  void admissionDischargeRiskTaskCounterReferralAndFollowup() throws Exception {
    integration(TENANT_A)
        .body(Map.of("cnes", HOSPITAL, "name", "Hospital Municipal"))
        .put("/api/v1/reference/health-units")
        .then()
        .statusCode(200);
    String citizenId = newCitizen("Alta Fluxo Souza");
    String recordId = "HEP-" + System.nanoTime();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    // admissão (cria) e transferência de leito (registra movimentação)
    Response admitted =
        integration(TENANT_A)
            .body(movement(recordId, citizenId, "admit", now.minusDays(9)))
            .post("/api/v1/hospital/episodes");
    admitted
        .then()
        .statusCode(201)
        .body("status", equalTo("admitted"))
        .body("hospital_name", equalTo("Hospital Municipal"))
        .body("movements", hasSize(1));
    String id = admitted.path("id");
    assertThat(id).startsWith("hep_");
    Map<String, Object> transfer = movement(recordId, citizenId, "transfer", now.minusDays(5));
    transfer.put("ward", "UTI");
    transfer.put("bed", "UTI-02");
    integration(TENANT_A)
        .body(transfer)
        .post("/api/v1/hospital/episodes")
        .then()
        .statusCode(200)
        .body("status", equalTo("in_progress"))
        .body("ward", equalTo("UTI"))
        .body("movements", hasSize(2))
        .body("movements[1].movement", equalTo("transfer"));

    // alta por vínculo de origem: LOS 9 → alto risco; CID F (saúde mental) → highly_restricted
    Response discharged =
        integration(TENANT_A)
            .body(discharge(recordId, now, "F32.1", List.of("Hipertensao", "hipertensao")))
            .post("/api/v1/hospital/episodes/by-source/HIS_X/" + recordId + "/discharge");
    discharged
        .then()
        .statusCode(200)
        .body("status", equalTo("discharged"))
        .body("length_of_stay_days", equalTo(9))
        .body("risk_level", equalTo("high"))
        .body("risk_rule_version", equalTo("post-discharge-risk/1"))
        .body("readmission_within_30d", equalTo(false))
        .body("reference_health_unit_cnes", equalTo(UBS))
        .body("reference_team_ine", equalTo(INE))
        .body("care_lines", hasSize(1))
        .body("care_lines[0]", equalTo("hipertensao"))
        .body("has_summary_document", equalTo(true))
        .body("followup.status", equalTo("pending"))
        .body("followup.task_id", startsWith("task_"))
        .body("followup.due_at", notNullValue())
        .body("principal_diagnosis_cid", nullValue())
        .body("movements", hasSize(3));
    assertThat(discharged.asString()).doesNotContain("F32");
    String taskId = discharged.path("followup.task_id");
    OffsetDateTime dueAt = OffsetDateTime.parse(discharged.path("followup.due_at"));
    assertThat(dueAt)
        .isAfter(OffsetDateTime.now().plusHours(23))
        .isBefore(OffsetDateTime.now().plusHours(25));

    // tarefa: prioridade pelo risco, equipe de referência, SLA de 24 h, sem CID na descrição
    Response task = aps(TENANT_A).get("/api/v1/tasks/" + taskId);
    task.then()
        .statusCode(200)
        .body("task_type", equalTo("post_discharge_followup"))
        .body("priority", equalTo("high"))
        .body("assignee.kind", equalTo("team"))
        .body("assignee.id", equalTo(INE))
        .body("sla_policy_id", equalTo("sla_post_discharge_followup_high"))
        .body("origin.kind", equalTo("workflow"))
        .body("origin.id", equalTo("discharge-followup:" + id))
        .body("citizen_id", equalTo(citizenId));
    assertThat(task.asString()).doesNotContain("F32");

    // CID: só papel clínico com vínculo; highly_restricted só equipe/hospital
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("principal_diagnosis_cid", nullValue());
    Api.as(TENANT_A, "dra.ana", "profissional_aps")
        .header("X-Test-Teams", INE)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("principal_diagnosis_cid", equalTo("F32.1"));
    Api.as(TENANT_A, "dr.hosp", "profissional_hospitalar")
        .header("X-Test-Cnes", HOSPITAL)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("principal_diagnosis_cid", equalTo("F32.1"));
    Api.as(TENANT_A, "dr.hosp", "profissional_hospitalar")
        .header("X-Test-Cnes", "9999999")
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("principal_diagnosis_cid", nullValue());
    Api.as(TENANT_A, "gestor.joao", "gestor")
        .header("X-Test-Teams", INE)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("principal_diagnosis_cid", nullValue());

    // listagem: UBS de referência e status do acompanhamento
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes?reference_cnes=" + UBS + "&followup_status=pending")
        .then()
        .body("items.id", hasItem(id));
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes?citizen_id=" + citizenId + "&status=discharged")
        .then()
        .body("items", hasSize(1));

    // contrarreferência (HOS-008)
    Map<String, Object> cr = new LinkedHashMap<>();
    cr.put("source", source("HIS_X", recordId + "-CR"));
    cr.put("received_at", now.toString());
    cr.put("target_health_unit_cnes", UBS);
    cr.put("document_ref", "s3://contrarreferencias/" + recordId + ".pdf");
    cr.put("recommendations_count", 3);
    integration(TENANT_A)
        .body(cr)
        .post("/api/v1/hospital/episodes/" + id + "/counter-referral")
        .then()
        .statusCode(201)
        .body("counter_referral.has_document", equalTo(true))
        .body("counter_referral.recommendations_count", equalTo(3));

    // eventos: ADT (admitted, transferred, discharged) e alta (completed, counter_referral)
    List<Outbox.Row> rows = Outbox.rowsFor(id);
    List<Outbox.Row> adt =
        rows.stream().filter(r -> r.eventType().startsWith("sus.hospital.adt.")).toList();
    List<Outbox.Row> dis =
        rows.stream().filter(r -> r.eventType().startsWith("sus.hospital.discharge.")).toList();
    assertThat(adt)
        .extracting(Outbox.Row::eventType)
        .containsExactly(
            "sus.hospital.adt.admitted",
            "sus.hospital.adt.transferred",
            "sus.hospital.adt.discharged");
    assertThat(dis)
        .extracting(Outbox.Row::eventType)
        .containsExactly(
            "sus.hospital.discharge.completed", "sus.hospital.discharge.counter_referral_received");
    Outbox.assertValid(adt, "contracts/events/hospital/adt.v1.schema.json");
    Outbox.assertValid(dis, "contracts/events/hospital/discharge.v1.schema.json");
    assertThat(adt.get(0).payload().get("privacy").get("classification").asText())
        .isEqualTo("restricted");
    assertThat(adt.get(2).payload().get("privacy").get("classification").asText())
        .isEqualTo("highly_restricted");
    assertThat(dis.get(0).payload().get("privacy").get("classification").asText())
        .isEqualTo("highly_restricted");
    assertThat(dis.get(0).payload().get("data_ref").asText())
        .isEqualTo("s3://sumarios/" + recordId + ".pdf");
    assertThat(dis.get(0).payload().get("data").get("risk_level").asText()).isEqualTo("high");
    assertThat(dis.get(0).payload().get("trace").get("causation_id").asText())
        .isEqualTo(adt.get(2).id());
    for (Outbox.Row r : rows) {
      assertThat(r.payload().toString()).doesNotContain("F32");
    }

    // desfecho do contato: conclui tarefa, abre plano (protocolo de hipertensão elegível)
    Response followup =
        aps(TENANT_A)
            .body(Map.of("outcome", "contact_made", "note", "contato telefônico realizado"))
            .post("/api/v1/hospital/episodes/" + id + "/followup");
    followup
        .then()
        .statusCode(200)
        .body("followup.status", equalTo("contacted"))
        .body("followup.outcome", equalTo("contact_made"))
        .body("followup.contacted_at", notNullValue())
        .body("followup.care_plan_id", startsWith("cp_"));
    String planId = followup.path("followup.care_plan_id");
    aps(TENANT_A).get("/api/v1/tasks/" + taskId).then().body("status", equalTo("completed"));
    aps(TENANT_A)
        .get("/api/v1/careplans/" + planId)
        .then()
        .statusCode(200)
        .body("care_line", equalTo("hipertensao"))
        .body("origin.kind", equalTo("hospital_discharge"))
        .body("origin.id", equalTo(id))
        .body("team_ine", equalTo(INE))
        .body("items", hasSize(2));
    aps(TENANT_A)
        .body(Map.of("outcome", "contact_made"))
        .post("/api/v1/hospital/episodes/" + id + "/followup")
        .then()
        .statusCode(409);

    // timeline: hospital (sem CID; highly_restricted) e careplan; ACS não vê; summary
    bus.relayAndDeliver();
    Await.until(
        "hospital na timeline",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/citizens/" + citizenId + "/timeline?domain=hospital")
                    .jsonPath()
                    .getList("items")
                    .size()
                >= 5);
    Response timeline =
        aps(TENANT_A).get("/api/v1/citizens/" + citizenId + "/timeline?domain=hospital,careplan");
    assertThat(timeline.asString()).doesNotContain("F32").doesNotContain("s3://");
    List<Map<String, Object>> items = timeline.jsonPath().getList("items");
    Map<String, Object> dischargeEvent =
        items.stream()
            .filter(i -> "sus.hospital.discharge.completed".equals(i.get("event_type")))
            .findFirst()
            .orElseThrow();
    assertThat(dischargeEvent.get("sensitivity")).isEqualTo("highly_restricted");
    assertThat(dischargeEvent.get("summary").toString()).contains("Alta hospitalar");
    assertThat(dischargeEvent.get("detail_ref")).isEqualTo("/api/v1/hospital/episodes/" + id);
    assertThat(dischargeEvent.get("health_unit_name")).isEqualTo("Hospital Municipal");
    assertThat(items)
        .extracting(i -> (String) i.get("event_type"))
        .contains("sus.hospital.adt.admitted", "sus.careplan.created");
    List<String> acsTypes =
        Api.as(TENANT_A, "acs.rita", "acs")
            .get("/api/v1/citizens/" + citizenId + "/timeline")
            .jsonPath()
            .getList("items.event_type");
    assertThat(acsTypes)
        .contains("sus.identity.citizen.created")
        .doesNotContain("sus.hospital.adt.admitted", "sus.hospital.discharge.completed");
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/summary")
        .then()
        .statusCode(200)
        .body("last_hospital_discharge_at", notNullValue())
        .body("care_lines", hasItem("hipertensao"))
        .body("care_gaps", equalTo(0))
        .body("contact_valid", equalTo(true));

    // isolamento de tenant
    aps(TENANT_B).get("/api/v1/hospital/episodes/" + id).then().statusCode(404);
    aps(TENANT_B)
        .get("/api/v1/hospital/episodes?citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(0));
    integration(TENANT_B)
        .body(discharge(recordId, now, null, List.of()))
        .post("/api/v1/hospital/episodes/by-source/HIS_X/" + recordId + "/discharge")
        .then()
        .statusCode(404);
  }

  @Test
  void readmissionWithin30DaysRaisesRiskAndDeathOpensNoFollowup() {
    String citizenId = newCitizen("Reinternação Costa");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String first = "HEP-R1-" + System.nanoTime();
    integration(TENANT_A)
        .body(movement(first, citizenId, "admit", now.minusDays(20)))
        .post("/api/v1/hospital/episodes")
        .then()
        .statusCode(201);
    Response firstDischarge =
        integration(TENANT_A)
            .body(discharge(first, now.minusDays(16), "I10", List.of()))
            .post("/api/v1/hospital/episodes/by-source/HIS_X/" + first + "/discharge");
    firstDischarge
        .then()
        .statusCode(200)
        .body("length_of_stay_days", equalTo(4))
        .body("risk_level", equalTo("medium"))
        .body("readmission_within_30d", equalTo(false));
    String firstId = firstDischarge.path("id");
    // CID não sensível: profissional da APS vinculado à UBS de referência vê o código
    Api.as(TENANT_A, "dra.ana", "profissional_aps")
        .header("X-Test-Cnes", UBS)
        .get("/api/v1/hospital/episodes/" + firstId)
        .then()
        .body("principal_diagnosis_cid", equalTo("I10"));
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes/" + firstId)
        .then()
        .body("principal_diagnosis_cid", nullValue());

    // segunda internação 5 dias depois, alta de 1 dia → reinternação → alto risco
    String second = "HEP-R2-" + System.nanoTime();
    Response readmitted = admitAndDischarge(citizenId, second, 1, null, List.of("diabetes"));
    readmitted
        .then()
        .body("readmission_within_30d", equalTo(true))
        .body("previous_episode_id", equalTo(firstId))
        .body("risk_level", equalTo("high"))
        .body("followup.status", equalTo("pending"));
    aps(TENANT_A)
        .get("/api/v1/tasks?task_type=post_discharge_followup&citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(2));

    // óbito via ADT: fluxo de alta sem tarefa de contato
    String third = "HEP-D-" + System.nanoTime();
    integration(TENANT_A)
        .body(movement(third, citizenId, "admit", now.minusHours(10)))
        .post("/api/v1/hospital/episodes")
        .then()
        .statusCode(201);
    Response death =
        integration(TENANT_A)
            .body(movement(third, citizenId, "death", now))
            .post("/api/v1/hospital/episodes");
    death
        .then()
        .statusCode(200)
        .body("status", equalTo("deceased"))
        .body("disposition", equalTo("deceased"))
        .body("followup.status", equalTo("closed"))
        .body("followup.outcome", equalTo("deceased"))
        .body("followup.task_id", nullValue());
    aps(TENANT_A)
        .get("/api/v1/tasks?task_type=post_discharge_followup&citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(2));
    // episódio encerrado não aceita nova movimentação nem alta
    integration(TENANT_A)
        .body(movement(third, citizenId, "transfer", now))
        .post("/api/v1/hospital/episodes")
        .then()
        .statusCode(409);
    // movimentação sem episódio conhecido → 404
    integration(TENANT_A)
        .body(movement("HEP-NONE-" + System.nanoTime(), citizenId, "transfer", now))
        .post("/api/v1/hospital/episodes")
        .then()
        .statusCode(404);
  }

  @Test
  void ingestionConsumerHandlesMovementDischargeAndCancel() {
    String citizenId = newCitizen("Hospital Kafka Dias");
    String recordId = "HEP-K-" + System.nanoTime();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String admit =
        Envelopes.build(
            TENANT_A,
            "sus.ingest.hospital.adt",
            movement(recordId, citizenId, "admit", now.minusDays(2)),
            null);
    bus.send("ingest-hospital-in", admit);
    bus.send("ingest-hospital-in", admit);
    Await.until(
        "episódio criado pela ingestão",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/hospital/episodes?citizen_id=" + citizenId)
                    .jsonPath()
                    .getList("items")
                    .size()
                == 1);
    String id =
        aps(TENANT_A).get("/api/v1/hospital/episodes?citizen_id=" + citizenId).path("items[0].id");
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("movements", hasSize(1))
        .body("status", equalTo("admitted"));

    bus.send(
        "ingest-hospital-in",
        Envelopes.build(
            TENANT_A,
            "sus.ingest.hospital.discharge",
            discharge(recordId, now, null, List.of("diabetes")),
            null));
    Await.until(
        "alta aplicada pela ingestão",
        () ->
            "discharged"
                .equals(aps(TENANT_A).get("/api/v1/hospital/episodes/" + id).path("status")));
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("length_of_stay_days", equalTo(2))
        .body("risk_level", equalTo("low"))
        .body("followup.status", equalTo("pending"))
        .body("movements", hasSize(greaterThanOrEqualTo(2)));
    String taskId = aps(TENANT_A).get("/api/v1/hospital/episodes/" + id).path("followup.task_id");
    aps(TENANT_A)
        .get("/api/v1/tasks/" + taskId)
        .then()
        .body("priority", equalTo("low"))
        .body("sla_policy_id", equalTo("sla_post_discharge_followup_low"));

    // cancelamento ADT de outro episódio: encerra sem alta e sem evento ADT
    String cancelled = "HEP-C-" + System.nanoTime();
    integration(TENANT_A)
        .body(movement(cancelled, citizenId, "admit", now.minusHours(1)))
        .post("/api/v1/hospital/episodes")
        .then()
        .statusCode(201);
    Response c =
        integration(TENANT_A)
            .body(movement(cancelled, citizenId, "cancel", now))
            .post("/api/v1/hospital/episodes");
    c.then().statusCode(200).body("status", equalTo("cancelled")).body("followup", nullValue());
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes?citizen_id=" + citizenId + "&status=cancelled")
        .then()
        .body("items", hasSize(1))
        .body("items[0].movements.movement", not(hasItem("discharge")));
  }
}
