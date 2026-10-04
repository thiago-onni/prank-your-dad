package br.gov.sus.nexus.connectors.sia;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import br.gov.sus.nexus.connectors.sdk.events.IngestEnvelopes;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessCommandHandler;
import br.gov.sus.nexus.connectors.sdk.retry.DeadLetter;
import br.gov.sus.nexus.connectors.sdk.retry.DeadLetterSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.apache.kafka.common.header.Header;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class SiaConnectorTest {

  /** Identificadores sintéticos das fixtures (CNS/CPF de teste) — nunca podem aparecer em log. */
  private static final List<String> PII =
      List.of(
          "898001234567891", "700123456789002", "52998224725", "529.982.247-25", "123456789012345");

  private static final String PRODUCTION_FILE = "producao_esus_202609.csv";
  private static final String SIA_RETURN = "retorno_sia_202609.csv";
  private static final String SIH_RETURN = "retorno_sih_202609.txt";

  @Inject SiaConnector connector;
  @Inject SiaRoutes routes;
  @Inject SiaConfig config;
  @Inject IntegrationMessageLedger ledger;
  @Inject DeadLetterSink dlq;
  @Inject ConnectorMetrics metrics;
  @Inject ReprocessCommandHandler commands;
  @Inject ObjectMapper mapper;
  @Inject @Any InMemoryConnector bus;

  private final List<String> logs = new ArrayList<>();
  private Handler capture;
  private static int cmdSeq;

  private static WireMockServer core() {
    return WireMockCoreResource.server;
  }

  @BeforeEach
  void setUp() throws IOException {
    core().resetAll();
    core()
        .stubFor(
            post(urlPathMatching("/api/v1/integration/.*"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    connector.registry().clear();
    commands.reset();
    sink().clear();
    for (String dir : List.of(config.file().productionDir(), config.file().returnsDir())) {
      Path p = Path.of(dir);
      if (Files.exists(p)) {
        try (Stream<Path> walk = Files.walk(p)) {
          walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
      }
      Files.createDirectories(p);
    }
    capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            String msg =
                record instanceof ExtLogRecord ext
                    ? ext.getFormattedMessage()
                    : record.getMessage();
            synchronized (logs) {
              logs.add(String.valueOf(msg));
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    Logger.getLogger("br.gov.sus.nexus").addHandler(capture);
    Logger.getLogger("org.apache.camel").addHandler(capture);
  }

  @AfterEach
  void tearDown() {
    Logger.getLogger("br.gov.sus.nexus").removeHandler(capture);
    Logger.getLogger("org.apache.camel").removeHandler(capture);
  }

  private InMemorySink<String> sink() {
    return bus.sink(ProductionEventPublisher.CHANNEL);
  }

  private List<JsonNode> envelopes() {
    List<JsonNode> out = new ArrayList<>();
    for (Message<String> m : sink().received()) {
      try {
        out.add(mapper.readTree(m.getPayload()));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return out;
  }

  private JsonNode envelopeBySource(String sourceRecordId) {
    return envelopes().stream()
        .filter(e -> sourceRecordId.equals(e.path("source").path("source_record_id").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("sem envelope para " + sourceRecordId));
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in =
        SiaConnectorTest.class.getClassLoader().getResourceAsStream("samples/" + name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }

  private static void drop(String dir, String name, byte[] content) throws IOException {
    Path tmp = Path.of(dir).resolve(name + ".part");
    Files.write(tmp, content);
    Files.move(tmp, Path.of(dir).resolve(name), StandardCopyOption.ATOMIC_MOVE);
  }

  private List<RawMessage> messages(String kind, String file) throws IOException {
    byte[] content = resource(file);
    return routes.toMessages(
        connector.layout().require(kind), file, "a".repeat(64), content, StandardCharsets.UTF_8);
  }

  private long count(String entity, IntegrationMessageStatus status) {
    return ledger.count(entity, status, null);
  }

  // ------------------------------------------------------------------------------------------

  @Test
  void descriptorELayoutVersionado() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().supportedEntities())
        .containsExactly("production_record", "production_outcome");
    assertThat(connector.layout().kinds())
        .extracting(SiaLayout.Kind::name)
        .containsExactly("producao", "retorno_sia_csv", "retorno_sih_txt");
    assertThat(connector.layout().require("retorno_sia_csv").pendingConfirmation()).isTrue();
    assertThat(connector.layout().require("retorno_sih_txt").pendingConfirmation()).isTrue();
    assertThat(connector.layout().require("producao").pendingConfirmation()).isFalse();
    assertThat(connector.layout().forFile("retorno", "RETORNO_SIH_202609.TXT"))
        .get()
        .extracting(SiaLayout.Kind::name)
        .isEqualTo("retorno_sih_txt");
    assertThat(connector.layout().forFile("producao", "retorno_sih_202609.txt"))
        .get()
        .extracting(SiaLayout.Kind::name)
        .isEqualTo("producao");
    assertThat(connector.layout().forFile("retorno", "qualquer.pdf")).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void transformaRegistroDeProducaoBpaIComCidadaoPorCns() throws IOException {
    List<RawMessage> raws = messages("producao", PRODUCTION_FILE);
    assertThat(raws).hasSize(5);
    RawMessage first = raws.get(0);
    assertThat(first.sourceRecordId()).isEqualTo("PRD-0001");
    assertThat(first.sourceRecordVersion()).isEqualTo("2026-09-10 08:00:00");
    assertThat(first.entityType()).isEqualTo(CanonicalBatch.PRODUCTION_RECORD);

    CanonicalBatch batch = connector.transform(first);
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat(p)
        .containsEntry("kind", "bpa_i")
        .containsEntry("competence", "202609")
        .containsEntry("cnes", "2206997")
        .containsEntry("professional_cns", "700123456789002")
        .containsEntry("professional_cbo", "225142")
        .containsEntry("procedure_code", "0301010064")
        .containsEntry("quantity", 1)
        .containsEntry("cid_code", "J11")
        .containsEntry("attendance_date", "2026-09-05")
        .containsEntry("character_of_care", "elective");
    assertThat((Map<String, Object>) p.get("source"))
        .containsEntry("system", "ESUS_APS_PEC")
        .containsEntry("connector", "connector-sia")
        .containsEntry("source_record_id", "PRD-0001")
        .containsEntry("cnes", "2206997");
    assertThat((Map<String, Object>) p.get("citizen_ref"))
        .containsEntry("identifier_system", "CNS")
        .containsEntry("identifier_value", "898001234567891");
    assertThat((Map<String, Object>) p.get("encounter_ref"))
        .containsEntry("system", "ESUS_APS_PEC")
        .containsEntry("source_record_id", "ATD-1001");
    assertThat(connector.validate(batch).isValid()).isTrue();

    // BPA-C sem cidadão e sem sistema na linha → source_system do layout
    CanonicalBatch bpac = connector.transform(raws.get(1));
    Map<String, Object> c = bpac.records().get(0).payload();
    assertThat(c).containsEntry("kind", "bpa_c").containsEntry("quantity", 12);
    assertThat(c).doesNotContainKey("citizen_ref");
    assertThat((Map<String, Object>) c.get("source")).containsEntry("system", "PRODUCAO_MUNICIPAL");
    assertThat(connector.validate(bpac).isValid()).isTrue();

    // APAC com CPF formatado
    Map<String, Object> apac = connector.transform(raws.get(2)).records().get(0).payload();
    assertThat(apac)
        .containsEntry("kind", "apac")
        .containsEntry("apac_number", "3126200012345")
        .containsEntry("cid_code", "C509");
    assertThat((Map<String, Object>) apac.get("citizen_ref"))
        .containsEntry("identifier_system", "CPF")
        .containsEntry("identifier_value", "52998224725");

    // BPA-I sem cidadão e CNS com dígito inválido → erros sem expor valores
    var noCitizen = connector.validate(connector.transform(raws.get(3)));
    assertThat(noCitizen.isValid()).isFalse();
    assertThat(noCitizen.errors()).extracting(e -> e.field()).contains("citizen_ref");
    var badCns = connector.validate(connector.transform(raws.get(4)));
    assertThat(badCns.isValid()).isFalse();
    assertThat(badCns.errors().toString()).doesNotContain("123456789012345");
  }

  @Test
  @SuppressWarnings("unchecked")
  void retornosSiaCsvESihLarguraFixa() throws IOException {
    List<RawMessage> sia =
        routes.toMessages(
            connector.layout().require("retorno_sia_csv"),
            SIA_RETURN,
            "b".repeat(64),
            resource(SIA_RETURN),
            StandardCharsets.UTF_8);
    assertThat(sia).hasSize(4);
    Map<String, Object> rejected = connector.transform(sia.get(0)).records().get(0).payload();
    assertThat(rejected)
        .containsEntry("outcome", "rejected")
        .containsEntry("reason_code", "0175")
        .containsEntry("processed_at", "2026-09-30T10:00:00-03:00")
        .doesNotContainKey("batch_id")
        .doesNotContainKey("production_record_id");
    assertThat((String) rejected.get("reason")).contains("incompatível"); // ISO-8859-1 decodificado
    assertThat((Map<String, Object>) rejected.get("record_source"))
        .containsEntry("system", "ESUS_APS_PEC")
        .containsEntry("source_record_id", "PRD-0001");
    assertThat((Map<String, Object>) rejected.get("source"))
        .containsEntry("system", "SIA")
        .containsEntry("source_record_id", "RET-1");

    CanonicalBatch paidBatch = connector.transform(sia.get(1));
    Map<String, Object> paid = paidBatch.records().get(0).payload();
    assertThat(paid)
        .containsEntry("outcome", "paid")
        .containsEntry("production_record_id", "prod_01JM9S346Q3D25VT4F5V37E3S3")
        .containsEntry("approved_quantity", 1)
        .containsEntry("protocol_number", "PROT-9")
        .doesNotContainKey("record_source");
    assertThat((BigDecimal) paid.get("paid_amount")).isEqualByComparingTo("10.50");
    assertThat(connector.validate(paidBatch).isValid()).isTrue();

    Map<String, Object> transmitted = connector.transform(sia.get(2)).records().get(0).payload();
    assertThat(transmitted)
        .containsEntry("outcome", "transmitted")
        .containsEntry("batch_id", "BATCH-202609-01");

    List<RawMessage> sih =
        routes.toMessages(
            connector.layout().require("retorno_sih_txt"),
            SIH_RETURN,
            "c".repeat(64),
            resource(SIH_RETURN),
            StandardCharsets.UTF_8);
    assertThat(sih).hasSize(2); // cabeçalho "01" e rodapé "99" ignorados
    CanonicalBatch sihPaid = connector.transform(sih.get(0));
    Map<String, Object> pg = sihPaid.records().get(0).payload();
    assertThat(pg)
        .containsEntry("outcome", "paid")
        .containsEntry("processed_at", "2026-09-30T00:00:00-03:00")
        .containsEntry("protocol_number", "PROTSIH000000001")
        .containsEntry("approved_quantity", 1);
    assertThat((BigDecimal) pg.get("paid_amount")).isEqualByComparingTo("1234.56");
    assertThat((Map<String, Object>) pg.get("record_source"))
        .containsEntry("system", "HIS")
        .containsEntry("source_record_id", "3126200098765");
    assertThat((Map<String, Object>) pg.get("source")).containsEntry("system", "SIH");
    assertThat(connector.validate(sihPaid).isValid()).isTrue();
    Map<String, Object> gl = connector.transform(sih.get(1)).records().get(0).payload();
    assertThat(gl)
        .containsEntry("outcome", "rejected")
        .containsEntry("reason_code", "0042")
        .containsEntry("reason", "AIH COM PERMANÊNCIA INCOMPATÍVEL");
  }

  @Test
  void pontaAPontaPublicaNoTopicoDeIngestaoComIdempotenciaDlqEReconciliacao() throws Exception {
    drop(config.file().productionDir(), PRODUCTION_FILE, resource(PRODUCTION_FILE));
    drop(config.file().returnsDir(), SIA_RETURN, resource(SIA_RETURN));
    drop(config.file().returnsDir(), SIH_RETURN, resource(SIH_RETURN));

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(
                      count(CanonicalBatch.PRODUCTION_RECORD, IntegrationMessageStatus.PUBLISHED))
                  .isEqualTo(3);
              assertThat(
                      count(CanonicalBatch.PRODUCTION_OUTCOME, IntegrationMessageStatus.PUBLISHED))
                  .isEqualTo(5);
              assertThat(
                      count(
                          CanonicalBatch.PRODUCTION_RECORD, IntegrationMessageStatus.DEAD_LETTERED))
                  .isEqualTo(2);
              assertThat(
                      count(
                          CanonicalBatch.PRODUCTION_OUTCOME,
                          IntegrationMessageStatus.DEAD_LETTERED))
                  .isEqualTo(1);
              assertThat(connector.registry().size()).isEqualTo(3);
            });
    assertThat(sink().received()).hasSize(8);

    // Envelope conforme contracts/events/envelope.schema.json, lido pelo ingest-production-in do
    // core
    JsonNode record = envelopeBySource("PRD-0001");
    assertThat(record.path("event_id").asText()).matches("^evt_[0-9A-HJKMNP-TV-Z]{26}$");
    assertThat(record.path("event_type").asText()).isEqualTo("sus.ingest.production.record");
    assertThat(record.path("event_version").asText()).isEqualTo("1.0");
    assertThat(record.path("tenant").path("municipality_id").asText()).isEqualTo("ibge_3143302");
    assertThat(record.path("source").path("system").asText()).isEqualTo("ESUS_APS_PEC");
    assertThat(record.path("source").path("connector").asText()).isEqualTo("connector-sia");
    assertThat(record.path("source").path("cnes").asText()).isEqualTo("2206997");
    assertThat(record.path("privacy").path("classification").asText())
        .isEqualTo("highly_restricted");
    assertThat(record.path("privacy").path("purpose").get(0).asText())
        .isEqualTo("production_audit");
    assertThat(record.path("trace").path("correlation_id").asText()).startsWith("corr_");
    assertThat(record.path("replay").asBoolean()).isFalse();
    assertThat(record.has("subject")).isFalse();
    JsonNode data = record.path("data");
    assertThat(data.path("kind").asText()).isEqualTo("bpa_i");
    assertThat(data.path("source").path("source_record_id").asText()).isEqualTo("PRD-0001");
    assertThat(data.path("citizen_ref").path("identifier_value").asText())
        .isEqualTo("898001234567891");
    JsonNode outcome = envelopeBySource("RET-1");
    assertThat(outcome.path("event_type").asText()).isEqualTo("sus.ingest.production.outcome");
    assertThat(outcome.path("data").path("outcome").asText()).isEqualTo("rejected");
    assertThat(outcome.path("occurred_at").asText()).startsWith("2026-09-30T10:00");

    // chave = source.source_record_id; cabeçalhos ce_*
    Message<String> first =
        sink().received().stream()
            .filter(m -> m.getPayload().contains(record.path("event_id").asText()))
            .findFirst()
            .orElseThrow();
    OutgoingKafkaRecordMetadata<?> meta =
        first.getMetadata(OutgoingKafkaRecordMetadata.class).orElseThrow();
    assertThat(meta.getKey()).isEqualTo("PRD-0001");
    Map<String, String> headers = new LinkedHashMap<>();
    for (Header h : meta.getHeaders()) {
      headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
    }
    assertThat(headers)
        .containsEntry("ce_id", record.path("event_id").asText())
        .containsEntry("ce_type", "sus.ingest.production.record")
        .containsEntry("ce_source", "connector-sia")
        .containsEntry("tenant_id", "ibge_3143302")
        .containsEntry("schema_version", "1.0.0")
        .containsEntry("replay", "false");

    // event_id determinístico por arquivo + linha
    String sha = sha256(resource(PRODUCTION_FILE));
    assertThat(record.path("event_id").asText())
        .isEqualTo(
            IngestEnvelopes.eventId(
                "connector-sia", "production_record", "producao|" + sha + "|1"));

    // DLQ: motivo sem CNS/CPF; ledger espelhado no core (dead_lettered) sem PII
    assertThat(dlq.open(100))
        .extracting(DeadLetter::reason)
        .allSatisfy(r -> PII.forEach(v -> assertThat(r).doesNotContain(v)));
    core()
        .verify(
            postRequestedFor(urlEqualTo("/api/v1/integration/messages"))
                .withRequestBody(matchingJsonPath("$.source_record_id", equalTo("PRD-0004")))
                .withRequestBody(matchingJsonPath("$.status", equalTo("dead_lettered")))
                .withRequestBody(matchingJsonPath("$.connector_id", equalTo("connector-sia"))));
    core()
        .verify(
            postRequestedFor(urlEqualTo("/api/v1/integration/messages"))
                .withRequestBody(matchingJsonPath("$.source_record_id", equalTo("RET-2")))
                .withRequestBody(matchingJsonPath("$.status", equalTo("published"))));
    for (LoggedRequest req : core().findAll(postRequestedFor(urlPathMatching("/api/v1/.*")))) {
      PII.forEach(v -> assertThat(req.getBodyAsString()).doesNotContain(v));
    }
    synchronized (logs) {
      assertThat(logs).isNotEmpty();
      for (String line : logs) PII.forEach(v -> assertThat(line).doesNotContain(v));
    }

    // marca d'água por arquivo: mesmo conteúdo renomeado não é relido
    drop(
        config.file().productionDir(), "producao_esus_202609_copia.csv", resource(PRODUCTION_FILE));
    Path copy = Path.of(config.file().productionDir()).resolve("producao_esus_202609_copia.csv");
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> !Files.exists(copy));
    assertThat(sink().received()).hasSize(8);
    assertThat(connector.registry().size()).isEqualTo(3);

    // reconciliação: linhas lidas × publicadas (gap = linhas na DLQ)
    ReconciliationReport report = routes.reconcile();
    ReconciliationReport.Entry prod =
        report.entries().stream()
            .filter(e -> e.entityType().equals("production_record"))
            .findFirst()
            .orElseThrow();
    assertThat(prod.sourceCount()).isEqualTo(5);
    assertThat(prod.busCount()).isEqualTo(3);
    assertThat(prod.gap()).isEqualTo(2);
    core()
        .verify(
            postRequestedFor(urlEqualTo("/api/v1/integration/reconciliation"))
                .withRequestBody(matchingJsonPath("$.entity_type", equalTo("production_outcome")))
                .withRequestBody(matchingJsonPath("$.source_count", equalTo("6")))
                .withRequestBody(matchingJsonPath("$.bus_count", equalTo("5"))));
    routes.heartbeat();
    core()
        .verify(
            postRequestedFor(urlEqualTo("/api/v1/integration/connectors/connector-sia/heartbeat"))
                .withRequestBody(matchingJsonPath("$.source_system", equalTo("SIA")))
                .withRequestBody(
                    matchingJsonPath("$.metrics.production_record.published_24h", equalTo("3"))));
  }

  @Test
  void comandoDeReprocessamentoDoCoreReexecutaLinhaDaDlqEIgnoraJaPublicada() throws Exception {
    drop(config.file().productionDir(), PRODUCTION_FILE, resource(PRODUCTION_FILE));
    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                count(CanonicalBatch.PRODUCTION_RECORD, IntegrationMessageStatus.PUBLISHED) == 3
                    && count(
                            CanonicalBatch.PRODUCTION_RECORD,
                            IntegrationMessageStatus.DEAD_LETTERED)
                        == 2);
    IntegrationMessage dead = message("PRD-0004");
    IntegrationMessage published = message("PRD-0001");
    int sent = sink().received().size();
    double deadBefore = reprocessCount("dead_lettered");
    double doneBefore = reprocessCount("already_done");

    bus.source("sia-integration-command").send(command(dead));
    bus.source("sia-integration-command").send(command(published));

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                reprocessCount("dead_lettered") > deadBefore
                    && reprocessCount("already_done") > doneBefore);
    // mesma mensagem reaberta e de volta à DLQ (linha continua inválida); nada novo publicado
    IntegrationMessage again = ledger.findById(dead.id()).orElseThrow();
    assertThat(again.status()).isEqualTo(IntegrationMessageStatus.DEAD_LETTERED);
    assertThat(again.rawRef()).isEqualTo(dead.rawRef());
    assertThat(sink().received()).hasSize(sent);
    assertThat(((InMemoryIntegrationMessageLedger) ledger).all()).hasSize(5);
    core()
        .verify(
            postRequestedFor(urlEqualTo("/api/v1/integration/messages"))
                .withRequestBody(matchingJsonPath("$.id", equalTo(published.id())))
                .withRequestBody(matchingJsonPath("$.status", equalTo("published"))));
  }

  private IntegrationMessage message(String sourceRecordId) {
    return ((InMemoryIntegrationMessageLedger) ledger)
        .all().stream()
            .filter(m -> sourceRecordId.equals(m.sourceRecordId()))
            .findFirst()
            .orElseThrow();
  }

  private double reprocessCount(String result) {
    return metrics.counterValue(
        ReprocessCommandHandler.METRIC, "connector_id", "connector-sia", "result", result);
  }

  private String command(IntegrationMessage m) throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", "requested");
    data.put("message_id", m.id());
    data.put("connector_id", "connector-sia");
    data.put("source_system", "SIA");
    data.put("source_record_id", m.sourceRecordId());
    data.put("raw_ref", m.rawRef());
    data.put("requested_by", "usr_operador_integracao");
    data.put("suppress_external_effects", true);
    return mapper.writeValueAsString(
        IngestEnvelopes.envelope(
            new IngestEnvelopes.Spec(
                IngestEnvelopes.eventId("sia-reprocess-test", String.valueOf(++cmdSeq)),
                "sus.integration.reprocess.requested",
                "ibge_3143302",
                Map.of(
                    "system",
                    "SIA",
                    "connector",
                    "core-municipal",
                    "source_record_id",
                    m.sourceRecordId()),
                data,
                "internal",
                List.of("integration_operations"),
                "corr_sia_reprocess",
                null,
                null,
                false)));
  }

  private static String sha256(byte[] content) {
    return br.gov.sus.nexus.connectors.sdk.util.Hashes.sha256Hex(content);
  }
}
