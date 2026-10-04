package br.gov.sus.nexus.core.production;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.TENANT_B;
import static br.gov.sus.nexus.core.support.Api.as;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.core.production.infrastructure.CompetenceDeadlineJob;
import br.gov.sus.nexus.core.production.infrastructure.FileExportStorage;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Envelopes;
import br.gov.sus.nexus.core.support.Fixtures;
import br.gov.sus.nexus.core.support.Outbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Produção (PRO-001..010): registro válido → {@code validated}; inválido por CBO/sexo/idade/
 * quantidade/instrumento/duplicidade/competência → {@code pending} com pendências e {@code
 * rule_version} + tarefa na fila {@code auditoria}; correção humana com justificativa revalida
 * (agente → 403); lote só com validados; aprovação exige papel e justificativa; exportação BPA-Mag
 * de referência com SHA-256; retornos (transmitido, rejeitado reabre pendência, pago); painel;
 * prazos e alertas D-5/D-1; ingestão; isolamento de tenant; eventos contra os 4 schemas; produção
 * fora da timeline do cidadão.
 */
@QuarkusTest
class ProductionFlowTest {

  static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
  static final String RULE_VERSION = "production-validation/1";
  static final Random RANDOM = new Random();

  @Inject Bus bus;
  @Inject CompetenceDeadlineJob deadlineJob;

  static String competence() {
    YearMonth ym = YearMonth.now(SP);
    return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
  }

  static LocalDate attendance() {
    return YearMonth.now(SP).atDay(1);
  }

  /** Ator fake com finalidade {@code production_audit}. */
  static RequestSpecification actor(String tenant, String user, String roles) {
    return RestAssured.given()
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Test-User", user)
        .header("X-Test-Roles", roles)
        .header("X-Purpose-Of-Use", "production_audit");
  }

  static RequestSpecification auditor(String tenant) {
    return actor(tenant, "auditora.carla", "auditor");
  }

  static RequestSpecification gestor(String tenant) {
    return actor(tenant, "gestor.joao", "gestor");
  }

  static RequestSpecification agent(String tenant) {
    return actor(tenant, "agent-auditoria", "agente_ia");
  }

  static RequestSpecification connector(String tenant) {
    return actor(tenant, "connector-producao", "operador_integracao");
  }

  /** CNES aleatório cadastrado e ativo (isola os lotes de cada teste). */
  static String newUnit(String tenant) {
    String cnes = String.format("%07d", 3_000_000 + RANDOM.nextInt(6_000_000));
    integration(tenant)
        .body(Map.of("cnes", cnes, "name", "Unidade Produção " + cnes))
        .put("/api/v1/reference/health-units")
        .then()
        .statusCode(200);
    return cnes;
  }

  record Citizen(String id, String cns) {}

  static final String[] SYLLABLES = {
    "ba", "ce", "di", "fo", "gu", "la", "me", "ni", "po", "ru", "sa", "te", "vi", "xo", "za", "lu",
    "ma", "ri", "to", "ne"
  };

  /** Nome aleatório (sem semelhança fonética entre testes → sem match probabilístico no MPI). */
  static String randomName() {
    StringBuilder sb = new StringBuilder();
    for (int w = 0; w < 3; w++) {
      StringBuilder word = new StringBuilder();
      for (int i = 0; i < 3 + RANDOM.nextInt(2); i++) {
        word.append(SYLLABLES[RANDOM.nextInt(SYLLABLES.length)]);
      }
      sb.append(w == 0 ? "" : " ")
          .append(Character.toUpperCase(word.charAt(0)))
          .append(word.substring(1));
    }
    return sb.toString();
  }

  static Citizen newCitizen(String tenant, String sex, LocalDate birthdate) {
    String cns = Fixtures.randomProvisionalCns();
    String id =
        integration(tenant)
            .body(Registration.of(randomName(), birthdate).sex(sex).cns(cns).build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");
    return new Citizen(id, cns);
  }

  static Map<String, Object> record(
      String sourceId,
      String kind,
      String cnes,
      String cbo,
      String procedure,
      int quantity,
      Citizen citizen) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(
        "source",
        Map.of(
            "system", "ESUS_APS_PEC",
            "connector", "connector-producao",
            "source_record_id", sourceId,
            "source_record_version", "1"));
    m.put("kind", kind);
    m.put("competence", competence());
    m.put("cnes", cnes);
    m.put("professional_cns", PROFESSIONAL_CNS);
    m.put("professional_cbo", cbo);
    m.put("procedure_code", procedure);
    m.put("quantity", quantity);
    if (citizen != null) {
      m.put("citizen_ref", Map.of("identifier_system", "CNS", "identifier_value", citizen.cns()));
    }
    m.put("attendance_date", attendance().toString());
    m.put("character_of_care", "elective");
    return m;
  }

  static final String PROFESSIONAL_CNS = Fixtures.randomDefinitiveCns();

  static Response post(Map<String, Object> body) {
    return connector(TENANT_A).body(body).post("/api/v1/production/records");
  }

  static String fulfilledAppointment(String citizenId, String cnes) {
    String sourceId = "APT-PROD-" + System.nanoTime();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(
        "source",
        Map.of(
            "system",
            "AGENDA",
            "connector",
            "connector-agenda",
            "source_record_id",
            sourceId,
            "cnes",
            cnes));
    m.put("citizen_ref", Map.of("municipal_citizen_id", citizenId));
    m.put("status", "fulfilled");
    m.put("kind", "direct");
    m.put("service_code", "0301010064");
    m.put("code_system", "SIGTAP");
    m.put("health_unit_cnes", cnes);
    OffsetDateTime start = attendance().atTime(10, 0).atZone(SP).toOffsetDateTime();
    m.put("scheduled_start", start.toString());
    m.put("scheduled_end", start.plusMinutes(20).toString());
    integration(TENANT_A).body(m).post("/api/v1/appointments").then().statusCode(201);
    return sourceId;
  }

  @Test
  void validRecordsBatchApprovalExportOutcomesAndSummary() throws Exception {
    String cnes = newUnit(TENANT_A);
    Citizen maria = newCitizen(TENANT_A, "female", LocalDate.of(1980, 5, 10));
    Citizen joana = newCitizen(TENANT_A, "female", LocalDate.of(1975, 2, 3));
    String aptSource = fulfilledAppointment(maria.id(), cnes);

    // 1) registro BPA-I válido com agendamento realizado (evidência) → validated, sem pendências
    Map<String, Object> first =
        record("PROD-OK-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010064", 1, maria);
    first.put("appointment_ref", Map.of("system", "AGENDA", "source_record_id", aptSource));
    first.put("cid_code", "I10");
    Response created = post(first);
    created
        .then()
        .statusCode(201)
        .body("id", startsWith("prod_"))
        .body("status", equalTo("validated"))
        .body("rule_version", equalTo(RULE_VERSION))
        .body("issues", hasSize(0))
        .body("citizen_id", equalTo(maria.id()))
        .body("appointment_id", startsWith("apt_"))
        .body("professional_cns_masked", startsWith("***"))
        .body("citizen_identifier_masked", startsWith("***"))
        .body("estimated_value", equalTo(10.0f))
        .body("health_unit_name", startsWith("Unidade Produção"))
        .body("procedure_display", containsString("Consulta"));
    assertThat(created.asString()).doesNotContain(maria.cns()).doesNotContain(PROFESSIONAL_CNS);
    String okId = created.path("id");

    // reenvio idêntico → 200 inalterado
    post(first)
        .then()
        .statusCode(200)
        .body("id", equalTo(okId))
        .body("status", equalTo("validated"));

    // 2) sem evidência na agenda → aviso evidence_missing, ainda validated
    Response second =
        post(
            record(
                "PROD-WARN-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010064", 1, joana));
    second
        .then()
        .statusCode(201)
        .body("status", equalTo("validated"))
        .body("issues", hasSize(1))
        .body("issues[0].rule_id", equalTo("evidence_missing"))
        .body("issues[0].severity", equalTo("warning"))
        .body("issues[0].rule_version", equalTo(RULE_VERSION));
    String warnId = second.path("id");

    // 3) lote (somente validados), aprovação humana obrigatória
    Response batch =
        auditor(TENANT_A)
            .body(Map.of("competence", competence(), "cnes", cnes, "kind", "bpa_i"))
            .post("/api/v1/production/batches");
    batch
        .then()
        .statusCode(201)
        .body("id", startsWith("pbat_"))
        .body("status", equalTo("draft"))
        .body("records_count", equalTo(2))
        .body("record_ids", hasItem(okId))
        .body("record_ids", hasItem(warnId))
        .body("estimated_value", equalTo(20.0f));
    String batchId = batch.path("id");
    // nada mais elegível
    auditor(TENANT_A)
        .body(Map.of("competence", competence(), "cnes", cnes, "kind", "bpa_i"))
        .post("/api/v1/production/batches")
        .then()
        .statusCode(422);
    // exportar sem aprovação → 409
    auditor(TENANT_A)
        .body(Map.of())
        .post("/api/v1/production/batches/" + batchId + "/export")
        .then()
        .statusCode(409);
    // agente de IA não aprova (nem com papel de auditor no token); outros papéis também não
    agent(TENANT_A)
        .body(Map.of("justification", "aprovação automática pelo agente"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(403);
    as(TENANT_A, "agent-auditoria", "auditor,agente_ia")
        .body(Map.of("justification", "aprovação automática pelo agente"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(403);
    as(TENANT_A, "dra.ana", "profissional_aps")
        .body(Map.of("justification", "aprovação indevida por papel clínico"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(403);
    gestor(TENANT_A)
        .body(Map.of("justification", "ok"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(400);
    gestor(TENANT_A)
        .body(Map.of())
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(400);
    gestor(TENANT_A)
        .body(Map.of("justification", "Conferido com o relatório de produção da UBS"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(200)
        .body("status", equalTo("approved"))
        .body("approved_by", equalTo("gestor.joao"))
        .body("approval_justification", startsWith("Conferido"));
    gestor(TENANT_A)
        .body(Map.of("justification", "Conferido com o relatório de produção da UBS"))
        .post("/api/v1/production/batches/" + batchId + "/approve")
        .then()
        .statusCode(409);
    // registro em lote aprovado não pode mais ser corrigido no barramento
    auditor(TENANT_A)
        .body(
            Map.of(
                "justification",
                "tentativa de correção após aprovação do lote",
                "changes",
                Map.of("quantity", 1, "cid_code", "E11")))
        .post("/api/v1/production/records/" + okId + "/corrections")
        .then()
        .statusCode(409);

    // 4) exportação: agente → 403; gestor (sem papel de auditor) → 403; auditor → arquivo + sha256
    agent(TENANT_A)
        .body(Map.of())
        .post("/api/v1/production/batches/" + batchId + "/export")
        .then()
        .statusCode(403);
    as(TENANT_A, "agent-auditoria", "auditor,agente_ia")
        .body(Map.of())
        .post("/api/v1/production/batches/" + batchId + "/export")
        .then()
        .statusCode(403);
    gestor(TENANT_A)
        .body(Map.of())
        .post("/api/v1/production/batches/" + batchId + "/export")
        .then()
        .statusCode(403);
    Response exported =
        auditor(TENANT_A).body(Map.of()).post("/api/v1/production/batches/" + batchId + "/export");
    exported
        .then()
        .statusCode(200)
        .body("status", equalTo("exported"))
        .body("export.layout", equalTo("bpa_mag_ref_v1"))
        .body("export.lines", equalTo(2))
        .body("export.lines_missing_identifiers", equalTo(0))
        .body("export.file_ref", startsWith("file:"));
    String sha = exported.path("export.sha256");
    assertThat(sha).matches("^[a-f0-9]{64}$");
    byte[] file = Files.readAllBytes(Path.of(URI.create(exported.<String>path("export.file_ref"))));
    assertThat(FileExportStorage.sha256(file)).isEqualTo(sha);
    assertThat(exported.<Integer>path("export.size_bytes")).isEqualTo(file.length);
    String content = new String(file, StandardCharsets.US_ASCII);
    String[] lines = content.split("\r\n");
    assertThat(lines).hasSize(3);
    assertThat(lines[0]).startsWith("01#BPA#" + competence() + "000002000001");
    assertThat(lines[1]).startsWith("03" + cnes + competence() + PROFESSIONAL_CNS + "225142");
    assertThat(content).contains("0301010064").contains(maria.cns()).contains(joana.cns());
    auditor(TENANT_A)
        .get("/api/v1/production/records/" + okId)
        .then()
        .statusCode(200)
        .body("status", equalTo("exported"))
        .body("batch_id", equalTo(batchId))
        .body("history.action", hasItem("exported"));
    // reenvio da origem após exportação → 409 (corrigir no sistema oficial)
    Map<String, Object> changed = new LinkedHashMap<>(first);
    changed.put("quantity", 2);
    post(changed).then().statusCode(409);

    // 5) retornos oficiais: transmitido (lote), rejeitado (reabre pendência), pago
    connector(TENANT_A)
        .body(
            Map.of(
                "source",
                source("SIA", "RET-T-" + batchId),
                "outcome",
                "transmitted",
                "batch_id",
                batchId,
                "processed_at",
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "protocol_number",
                "PROT-123"))
        .post("/api/v1/production/outcomes")
        .then()
        .statusCode(200)
        .body("affected", hasSize(2))
        .body("affected.status", everyItem(equalTo("transmitted")));
    auditor(TENANT_A)
        .get("/api/v1/production/batches/" + batchId)
        .then()
        .body("status", equalTo("transmitted"))
        .body("protocol_number", equalTo("PROT-123"));
    Map<String, Object> rejection = new LinkedHashMap<>();
    rejection.put("source", source("SIA", "RET-R-" + warnId));
    rejection.put("outcome", "rejected");
    rejection.put("production_record_id", warnId);
    rejection.put("processed_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    rejection.put("reason_code", "0175");
    rejection.put("reason", "Procedimento incompatível com o serviço/classificação do CNES");
    connector(TENANT_A)
        .body(rejection)
        .post("/api/v1/production/outcomes")
        .then()
        .statusCode(200)
        .body("affected[0].status", equalTo("rejected"));
    Response rejected = auditor(TENANT_A).get("/api/v1/production/records/" + warnId);
    rejected
        .then()
        .body("status", equalTo("rejected"))
        .body("outcome_reason_code", equalTo("0175"))
        .body("issues.find { it.rule_id == 'official_rejection' }.status", equalTo("open"))
        .body(
            "issues.find { it.rule_id == 'official_rejection' }.origin", equalTo("official_return"))
        .body("issues.find { it.rule_id == 'official_rejection' }.task_id", startsWith("task_"));
    String rejectionTask =
        rejected.path("issues.find { it.rule_id == 'official_rejection' }.task_id");
    gestor(TENANT_A)
        .get("/api/v1/tasks/" + rejectionTask)
        .then()
        .body("task_type", equalTo("production_issue"))
        .body("assignee.kind", equalTo("queue"))
        .body("assignee.id", equalTo("auditoria"))
        .body("priority", equalTo("urgent"))
        .body("origin.id", equalTo("production-preaudit:" + warnId));
    // repetição do mesmo retorno → idempotente
    connector(TENANT_A)
        .body(rejection)
        .post("/api/v1/production/outcomes")
        .then()
        .statusCode(200)
        .body("unchanged", equalTo(true));
    // pago exige registro e valor
    connector(TENANT_A)
        .body(
            Map.of(
                "source",
                source("SIA", "RET-P-" + batchId),
                "outcome",
                "paid",
                "batch_id",
                batchId,
                "paid_amount",
                10,
                "processed_at",
                OffsetDateTime.now(ZoneOffset.UTC).toString()))
        .post("/api/v1/production/outcomes")
        .then()
        .statusCode(422);
    connector(TENANT_A)
        .body(
            Map.of(
                "source",
                source("SIA", "RET-P-" + okId),
                "outcome",
                "paid",
                "production_record_id",
                okId,
                "paid_amount",
                10.0,
                "approved_quantity",
                1,
                "processed_at",
                OffsetDateTime.now(ZoneOffset.UTC).toString()))
        .post("/api/v1/production/outcomes")
        .then()
        .statusCode(200)
        .body("affected[0].status", equalTo("paid"));
    auditor(TENANT_A)
        .get("/api/v1/production/records/" + okId)
        .then()
        .body("paid_amount", equalTo(10.0f))
        .body("approved_quantity", equalTo(1));
    // agente não registra retorno
    agent(TENANT_A)
        .body(
            Map.of(
                "source",
                source("SIA", "RET-X"),
                "outcome",
                "accepted",
                "production_record_id",
                okId,
                "processed_at",
                OffsetDateTime.now(ZoneOffset.UTC).toString()))
        .post("/api/v1/production/outcomes")
        .then()
        .statusCode(403);

    // 6) correção do rejeitado (reapresentação) → resolve o motivo oficial e revalida
    auditor(TENANT_A)
        .body(
            Map.of(
                "justification",
                "Ajuste do CBO conforme vínculo no CNES; reapresentar",
                "changes",
                Map.of("professional_cbo", "225170")))
        .post("/api/v1/production/records/" + warnId + "/corrections")
        .then()
        .statusCode(200)
        .body("status", equalTo("validated"))
        .body("correction_count", equalTo(1))
        .body("batch_id", org.hamcrest.Matchers.nullValue())
        .body("issues.find { it.rule_id == 'official_rejection' }.status", equalTo("resolved"))
        .body("history.action", hasItem("corrected"))
        .body(
            "history.find { it.action == 'corrected' }.justification", startsWith("Ajuste do CBO"));
    gestor(TENANT_A)
        .get("/api/v1/tasks/" + rejectionTask)
        .then()
        .body("status", equalTo("completed"));

    // 7) painel da competência (PRO-009)
    Response summary =
        gestor(TENANT_A)
            .get("/api/v1/production/summary?competence=" + competence() + "&cnes=" + cnes);
    summary
        .then()
        .statusCode(200)
        .body("totals.records", equalTo(2))
        .body("totals.paid", equalTo(1))
        .body("totals.validated", equalTo(1))
        .body("totals.corrected", equalTo(1))
        .body("totals.rejected", equalTo(0))
        .body("values.estimated", equalTo(20.0f))
        .body("values.paid", equalTo(10.0f))
        .body("values.avoidable_loss_estimated", equalTo(0.0f))
        .body("deadline_at", notNullValue())
        .body("by_kind[0].kind", equalTo("bpa_i"));
    agent(TENANT_A)
        .get("/api/v1/production/summary?competence=" + competence())
        .then()
        .statusCode(403);

    // 8) eventos validados contra os contratos; nenhum CNS em claro
    assertEvents(List.of(okId, warnId), List.of(batchId), maria.cns(), joana.cns());

    // 9) produção não aparece na timeline do cidadão (dado administrativo)
    bus.relayAndDeliver();
    as(TENANT_A, "dra.ana", "profissional_aps")
        .get("/api/v1/citizens/" + maria.id() + "/timeline")
        .then()
        .statusCode(200)
        .body("items.domain", not(hasItem("production")));
  }

  static Map<String, Object> source(String system, String id) {
    return Map.of("system", system, "connector", "connector-sia", "source_record_id", id);
  }

  static void assertEvents(List<String> recordIds, List<String> batchIds, String... secrets)
      throws Exception {
    List<Outbox.Row> recordRows = new ArrayList<>();
    List<Outbox.Row> issueRows = new ArrayList<>();
    List<Outbox.Row> outcomeRows = new ArrayList<>();
    for (String id : recordIds) {
      for (Outbox.Row row : Outbox.rowsFor(id)) {
        if (row.eventType().startsWith("sus.production.record.")) {
          recordRows.add(row);
        } else if (row.eventType().startsWith("sus.production.validation.")) {
          issueRows.add(row);
        } else if (row.eventType().startsWith("sus.production.outcome.")) {
          outcomeRows.add(row);
        }
        for (String secret : secrets) {
          assertThat(row.payload().toString()).doesNotContain(secret);
        }
        assertThat(row.payload().toString()).doesNotContain(PROFESSIONAL_CNS);
        assertThat(row.payload().has("subject")).isFalse();
      }
    }
    List<Outbox.Row> batchRows = new ArrayList<>();
    for (String id : batchIds) {
      batchRows.addAll(Outbox.rowsFor(id));
    }
    assertThat(recordRows)
        .extracting(Outbox.Row::eventType)
        .contains("sus.production.record.created");
    Outbox.assertValid(recordRows, "contracts/events/production/record.v1.schema.json");
    if (!issueRows.isEmpty()) {
      Outbox.assertValid(issueRows, "contracts/events/production/validation.v1.schema.json");
    }
    if (!outcomeRows.isEmpty()) {
      Outbox.assertValid(outcomeRows, "contracts/events/production/outcome.v1.schema.json");
    }
    if (!batchRows.isEmpty()) {
      Outbox.assertValid(batchRows, "contracts/events/production/submission.v1.schema.json");
    }
  }

  @Test
  void invalidRecordsArePendingWithVersionedIssuesAndCorrectionRevalidates() throws Exception {
    String cnes = newUnit(TENANT_A);
    Citizen woman = newCitizen(TENANT_A, "female", LocalDate.of(1970, 1, 15));
    Citizen man = newCitizen(TENANT_A, "male", LocalDate.of(1965, 7, 20));
    Citizen young = newCitizen(TENANT_A, "female", LocalDate.now(SP).minusYears(20));

    // CBO incompatível (enfermeiro em consulta médica)
    Response cbo =
        post(
            record(
                "PROD-CBO-" + System.nanoTime(), "bpa_i", cnes, "223505", "0301010064", 1, woman));
    cbo.then()
        .statusCode(201)
        .body("status", equalTo("pending"))
        .body("issues.find { it.rule_id == 'cbo_incompatible' }.severity", equalTo("error"))
        .body(
            "issues.find { it.rule_id == 'cbo_incompatible' }.rule_version", equalTo(RULE_VERSION))
        .body("issues.find { it.rule_id == 'cbo_incompatible' }.field", equalTo("professional_cbo"))
        .body("issues.find { it.rule_id == 'cbo_incompatible' }.task_id", startsWith("task_"));
    String cboId = cbo.path("id");
    String taskId = cbo.path("issues.find { it.rule_id == 'cbo_incompatible' }.task_id");
    gestor(TENANT_A)
        .get("/api/v1/tasks/" + taskId)
        .then()
        .body("task_type", equalTo("production_issue"))
        .body("assignee.id", equalTo("auditoria"))
        .body("priority", equalTo("high"))
        .body("citizen_id", org.hamcrest.Matchers.nullValue())
        .body("origin.kind", equalTo("rule"))
        .body("origin.version", equalTo(RULE_VERSION));

    // sexo (mamografia em homem), idade (mamografia aos 20 anos), quantidade, instrumento
    post(record("PROD-SEX-" + System.nanoTime(), "bpa_i", cnes, "225320", "0204030153", 1, man))
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", hasItem("sex_incompatible"));
    post(record("PROD-AGE-" + System.nanoTime(), "bpa_i", cnes, "225320", "0204030153", 1, young))
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", hasItem("age_incompatible"))
        .body("issues.rule_id", not(hasItem("sex_incompatible")));
    post(record("PROD-QTD-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010030", 3, woman))
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", hasItem("quantity_exceeded"))
        .body("issues.rule_id", hasItem("cbo_incompatible"));
    post(record("PROD-INS-" + System.nanoTime(), "bpa_i", cnes, "223505", "0101010010", 1, woman))
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", hasItem("instrument_incompatible"));
    // BPA-C: sem cidadão, quantidade agregada, instrumento correto → validado
    post(record("PROD-BPAC-" + System.nanoTime(), "bpa_c", cnes, "515105", "0101010010", 25, null))
        .then()
        .statusCode(201)
        .body("status", equalTo("validated"))
        .body("issues", hasSize(0))
        .body("estimated_value", equalTo(0.0f));
    // BPA-I sem cidadão → citizen_unresolved; CNS com DV inválido → citizen_identifier_invalid
    Map<String, Object> noCitizen =
        record("PROD-NOCIT-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010064", 1, null);
    post(noCitizen)
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", hasItem("citizen_unresolved"));
    Map<String, Object> badCns =
        record("PROD-BADCNS-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010064", 1, null);
    badCns.put(
        "citizen_ref", Map.of("identifier_system", "CNS", "identifier_value", "123456789012345"));
    post(badCns)
        .then()
        .body("issues.rule_id", hasItem("citizen_identifier_invalid"))
        .body("citizen_identifier_masked", equalTo("***********2345"));
    // competência encerrada (prazo vencido) e atendimento fora da competência
    Map<String, Object> closed =
        record("PROD-CLOSED-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010064", 1, woman);
    closed.put("competence", "202401");
    closed.put("attendance_date", "2024-01-15");
    post(closed)
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", hasItem("competence_closed"))
        .body("issues.rule_id", not(hasItem("attendance_outside_competence")));
    // procedimento inexistente e CNES não cadastrado
    Map<String, Object> unknown =
        record(
            "PROD-UNK-" + System.nanoTime(), "bpa_i", "7654321", "999999", "0999999999", 1, woman);
    post(unknown)
        .then()
        .body("issues.rule_id", hasItem("procedure_invalid"))
        .body("issues.rule_id", hasItem("cnes_not_registered"))
        .body("issues.rule_id", hasItem("cbo_unknown"));

    // duplicidade: mesmo cidadão + procedimento + data + CNES
    Response original =
        post(
            record(
                "PROD-DUP1-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010064", 1, man));
    original.then().body("status", equalTo("validated"));
    Response duplicate =
        post(
            record(
                "PROD-DUP2-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010064", 1, man));
    duplicate
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", hasItem("duplicate"));
    auditor(TENANT_A)
        .get("/api/v1/production/records/" + original.path("id"))
        .then()
        .body("status", equalTo("validated"));

    // AIH: sem número e sem episódio → erro + aviso; com episódio ADT vinculado → sem aviso
    Map<String, Object> aih =
        record("PROD-AIH-" + System.nanoTime(), "aih", cnes, "225125", "0303140151", 1, man);
    post(aih)
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", hasItem("aih_number_missing"))
        .body("issues.rule_id", hasItem("hospital_episode_missing"));
    String hisId = "HEP-PROD-" + System.nanoTime();
    Map<String, Object> admit = new LinkedHashMap<>();
    admit.put(
        "source",
        Map.of("system", "HIS_X", "connector", "connector-his", "source_record_id", hisId));
    admit.put("citizen_ref", Map.of("municipal_citizen_id", man.id()));
    admit.put("hospital_cnes", cnes);
    admit.put("episode_class", "inpatient");
    admit.put("movement", "admit");
    admit.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC).minusDays(3).toString());
    integration(TENANT_A).body(admit).post("/api/v1/hospital/episodes").then().statusCode(201);
    Map<String, Object> aihOk =
        record("PROD-AIH2-" + System.nanoTime(), "aih", cnes, "225125", "0303140151", 1, man);
    aihOk.put("aih_number", "3126100012345");
    aihOk.put("hospital_episode_ref", Map.of("system", "HIS_X", "source_record_id", hisId));
    aihOk.put("attendance_date", attendance().plusDays(1).toString());
    post(aihOk)
        .then()
        .body("status", equalTo("validated"))
        .body("hospital_episode_id", startsWith("hep_"))
        .body("estimated_value", equalTo(563.17f));

    // listagem de pendências: agente de IA lê; filtros por regra/competência/CNES
    agent(TENANT_A)
        .get(
            "/api/v1/production/issues?rule=sex_incompatible&competence="
                + competence()
                + "&cnes="
                + cnes)
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].record_status", equalTo("pending"))
        .body("items[0].kind", equalTo("bpa_i"))
        .body("items[0].procedure_code", equalTo("0204030153"));
    agent(TENANT_A)
        .get("/api/v1/production/issues?severity=error&cnes=" + cnes)
        .then()
        .statusCode(200)
        .body("items.severity", everyItem(equalTo("error")));
    // ... mas não lê registros nem corrige
    agent(TENANT_A).get("/api/v1/production/records/" + cboId).then().statusCode(403);
    agent(TENANT_A)
        .body(
            Map.of(
                "justification",
                "sugestão do agente de auditoria",
                "changes",
                Map.of("professional_cbo", "225142")))
        .post("/api/v1/production/records/" + cboId + "/corrections")
        .then()
        .statusCode(403);
    as(TENANT_A, "agent-auditoria", "auditor,agente_ia")
        .body(
            Map.of(
                "justification",
                "sugestão do agente de auditoria",
                "changes",
                Map.of("professional_cbo", "225142")))
        .post("/api/v1/production/records/" + cboId + "/corrections")
        .then()
        .statusCode(403);
    // correção exige justificativa
    auditor(TENANT_A)
        .body(Map.of("changes", Map.of("professional_cbo", "225142")))
        .post("/api/v1/production/records/" + cboId + "/corrections")
        .then()
        .statusCode(400);
    auditor(TENANT_A)
        .body(Map.of("justification", "curta", "changes", Map.of("professional_cbo", "225142")))
        .post("/api/v1/production/records/" + cboId + "/corrections")
        .then()
        .statusCode(400);

    // lote só com validados: o pendente não entra
    Response batch =
        auditor(TENANT_A)
            .body(Map.of("competence", competence(), "cnes", cnes, "kind", "bpa_i"))
            .post("/api/v1/production/batches");
    batch.then().statusCode(201).body("record_ids", not(hasItem(cboId)));
    String batchId = batch.path("id");
    assertThat(batch.<List<String>>path("record_ids")).contains(original.<String>path("id"));

    // correção humana revalida → validated; pendência resolvida; tarefa concluída
    auditor(TENANT_A)
        .body(
            Map.of(
                "justification",
                "CBO corrigido para médico da ESF conforme escala",
                "changes",
                Map.of("professional_cbo", "225142")))
        .post("/api/v1/production/records/" + cboId + "/corrections")
        .then()
        .statusCode(200)
        .body("status", equalTo("validated"))
        .body("correction_count", equalTo(1))
        .body("issues.find { it.rule_id == 'cbo_incompatible' }.status", equalTo("resolved"))
        .body("history.action", hasItem("corrected"))
        .body("history.action", hasItem("validated"));
    gestor(TENANT_A).get("/api/v1/tasks/" + taskId).then().body("status", equalTo("completed"));

    // aviso dispensado com justificativa; erro não é dispensável
    Response warn =
        post(
            record(
                "PROD-WAIVE-" + System.nanoTime(),
                "bpa_i",
                cnes,
                "225125",
                "0301010048",
                1,
                woman));
    warn.then()
        .body("status", equalTo("validated"))
        .body("issues[0].rule_id", equalTo("evidence_missing"));
    String warnIssue = warn.path("issues[0].id");
    auditor(TENANT_A)
        .body(
            Map.of(
                "justification", "Atendimento registrado em papel; prontuário conferido",
                "changes", Map.of("character_of_care", "urgency"),
                "waive_issue_ids", List.of(warnIssue)))
        .post("/api/v1/production/records/" + warn.path("id") + "/corrections")
        .then()
        .statusCode(200)
        .body("issues.find { it.id == '" + warnIssue + "' }.status", equalTo("waived"))
        .body("issues.findAll { it.status == 'open' }", hasSize(0));
    String sexIssue =
        agent(TENANT_A)
            .get("/api/v1/production/issues?rule=sex_incompatible&cnes=" + cnes)
            .path("items[0].id");
    String sexRecord =
        agent(TENANT_A)
            .get("/api/v1/production/issues?rule=sex_incompatible&cnes=" + cnes)
            .path("items[0].production_record_id");
    auditor(TENANT_A)
        .body(
            Map.of(
                "justification", "tentativa de dispensar erro de sexo",
                "changes", Map.of("quantity", 1),
                "waive_issue_ids", List.of(sexIssue)))
        .post("/api/v1/production/records/" + sexRecord + "/corrections")
        .then()
        .statusCode(422);

    // correção de registro em lote rascunho: sai do lote
    String inBatch = original.path("id");
    auditor(TENANT_A)
        .body(
            Map.of(
                "justification",
                "CID informado após revisão do prontuário",
                "changes",
                Map.of("cid_code", "I10")))
        .post("/api/v1/production/records/" + inBatch + "/corrections")
        .then()
        .statusCode(200)
        .body("batch_id", org.hamcrest.Matchers.nullValue());
    auditor(TENANT_A)
        .get("/api/v1/production/batches/" + batchId)
        .then()
        .body("record_ids", not(hasItem(inBatch)));

    // eventos contra os schemas
    assertEvents(
        List.of(cboId, duplicate.path("id"), warn.path("id")),
        List.of(batchId),
        woman.cns(),
        man.cns());
  }

  @Test
  void tenantIsolation() {
    String cnes = newUnit(TENANT_A);
    Citizen c = newCitizen(TENANT_A, "female", LocalDate.of(1990, 3, 3));
    String id =
        post(record("PROD-TEN-" + System.nanoTime(), "bpa_i", cnes, "223505", "0301010064", 1, c))
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    auditor(TENANT_B).get("/api/v1/production/records/" + id).then().statusCode(404);
    auditor(TENANT_B)
        .get("/api/v1/production/issues?cnes=" + cnes)
        .then()
        .statusCode(200)
        .body("items", hasSize(0));
    gestor(TENANT_B)
        .get("/api/v1/production/summary?competence=" + competence() + "&cnes=" + cnes)
        .then()
        .body("totals.records", equalTo(0));
    auditor(TENANT_B)
        .body(
            Map.of(
                "justification",
                "correção a partir de outro município",
                "changes",
                Map.of("professional_cbo", "225142")))
        .post("/api/v1/production/records/" + id + "/corrections")
        .then()
        .statusCode(404);
  }

  @Test
  void ingestionConsumerRegistersRecordsAndOutcomes() {
    String cnes = newUnit(TENANT_A);
    Citizen c = newCitizen(TENANT_A, "female", LocalDate.of(1988, 8, 8));
    String sourceId = "PROD-ING-" + System.nanoTime();
    Map<String, Object> data = record(sourceId, "bpa_i", cnes, "225142", "0301010064", 1, c);
    String eventId = br.gov.sus.nexus.core.platform.ids.Ulid.generate("evt_");
    String envelope =
        Envelopes.build(TENANT_A, "sus.ingest.production.record", data, null, eventId, null);
    bus.send("ingest-production-in", envelope);
    bus.send("ingest-production-in", envelope); // duplicata: event_inbox
    Await.until(
        "registro ingerido",
        () ->
            auditor(TENANT_A)
                    .get("/api/v1/production/records/by-source/ESUS_APS_PEC/" + sourceId)
                    .statusCode()
                == 200);
    auditor(TENANT_A)
        .get("/api/v1/production/records/by-source/ESUS_APS_PEC/" + sourceId)
        .then()
        .body("status", equalTo("validated"))
        .body("citizen_id", equalTo(c.id()));
    auditor(TENANT_A)
        .get("/api/v1/production/records?cnes=" + cnes)
        .then()
        .body("items", hasSize(1));

    // relay → consumidor do workflow (sem Temporal no teste: no-op idempotente)
    bus.relayAndDeliver();
  }

  @Test
  void deadlinesAndCompetenceAlerts() {
    String cnes = newUnit(TENANT_A);
    Citizen c = newCitizen(TENANT_A, "male", LocalDate.of(1960, 1, 1));
    post(record("PROD-DL-" + System.nanoTime(), "bpa_i", cnes, "223505", "0301010064", 1, c))
        .then()
        .body("status", equalTo("pending"));

    YearMonth next = YearMonth.now(SP).plusMonths(1);
    Response deadlines =
        gestor(TENANT_A)
            .get("/api/v1/production/deadlines?from=" + competence() + "&to=" + competence());
    deadlines
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].competence", equalTo(competence()))
        .body("items[0].configured_by", equalTo("global"))
        .body("items[0].alert_days", equalTo(List.of(5, 1)))
        .body("items[0].pending_records", org.hamcrest.Matchers.greaterThanOrEqualTo(1));
    OffsetDateTime deadline = OffsetDateTime.parse(deadlines.path("items[0].deadline_at"));
    assertThat(deadline.atZoneSameInstant(SP).toLocalDate()).isEqualTo(next.atDay(10));
    gestor(TENANT_A)
        .get("/api/v1/production/deadlines?from=202401&to=202401")
        .then()
        .body("items[0].status", equalTo("closed"));

    // D-5 (3 dias antes) → uma tarefa; repetir não duplica; D-1 (12 h antes) → urgente
    assertThat(deadlineJob.runOnce(deadline.toInstant().minus(Duration.ofDays(3))))
        .isGreaterThanOrEqualTo(1);
    assertThat(deadlineJob.runOnce(deadline.toInstant().minus(Duration.ofDays(3)))).isZero();
    gestor(TENANT_A)
        .get(
            "/api/v1/tasks?task_type=production_issue&assignee_kind=queue&assignee_id=auditoria&limit=200")
        .then()
        .body("items.origin.id", hasItem("production-deadline:" + competence() + ":D-5"));
    assertThat(deadlineJob.runOnce(deadline.toInstant().minus(Duration.ofHours(12))))
        .isGreaterThanOrEqualTo(1);
    gestor(TENANT_A)
        .get(
            "/api/v1/tasks?task_type=production_issue&assignee_kind=queue&assignee_id=auditoria&limit=200")
        .then()
        .body(
            "items.find { it.origin.id == 'production-deadline:"
                + competence()
                + ":D-1' }.priority",
            equalTo("urgent"));
    assertThat(deadlineJob.runOnce(deadline.toInstant().plus(Duration.ofDays(1)))).isZero();
  }
}
