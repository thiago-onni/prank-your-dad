package br.gov.sus.nexus.connectors.pec;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.core.IdempotencyKeys;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.retry.DeadLetterSink;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class PecConnectorTest {

  @Inject PecConnector connector;
  @Inject IntegrationMessageLedger ledger;
  @Inject DeadLetterSink dlq;
  @Inject PecConfig config;

  @BeforeEach
  void reset() {
    WireMockCoreResource.server.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo("/api/v1/citizens"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"municipal_citizen_id\":\"cit_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"classification\":\"new\",\"method\":\"cpf\"}")));
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo("/api/v1/appointments"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"apt_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"status\":\"booked\"}")));
  }

  @Test
  void descriptorCompletoEModoFile() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().supportedEntities())
        .containsExactly("citizen", "appointment");
    assertThat(config.mode()).isEqualTo("file");
  }

  @Test
  @SuppressWarnings("unchecked")
  void transformaCidadaoCsvComIdentificadoresCondicionais() {
    String csv =
        "co_seq_cidadao;no_cidadao;nu_cpf;nu_cns;dt_nascimento;no_mae;no_sexo;co_raca_cor;nu_telefone_celular;nu_cnes;dt_atualizado\n"
            + "1001;José da Silva;123.456.789-09;;1980-03-05;Maria;M;3;(38) 99999-0000;2206997;2026-01-01 10:00:00";
    RawMessage raw =
        new RawMessage(
            "1001",
            "2026-01-01 10:00:00",
            "citizen",
            PecRows.CSV,
            csv.getBytes(StandardCharsets.UTF_8),
            Map.of(),
            null);
    CanonicalBatch batch = connector.transform(raw);
    Map<String, Object> p = batch.records().get(0).payload();
    Map<String, Object> d = (Map<String, Object>) p.get("demographics");
    assertThat(d)
        .containsEntry("legal_name", "JOSE DA SILVA")
        .containsEntry("birthdate", "1980-03-05")
        .containsEntry("sex", "male")
        .containsEntry("race_color", "parda");
    List<Map<String, Object>> ids = (List<Map<String, Object>>) p.get("identifiers");
    // CPF presente, CNS ausente (posição pulada e compactada); identificador PEC sempre presente
    assertThat(ids).hasSize(2);
    assertThat(ids.get(0)).containsEntry("system", "CPF").containsEntry("value", "12345678909");
    assertThat(ids.get(1)).containsEntry("system", "PEC").containsEntry("value", "1001");
    assertThat((List<Map<String, Object>>) p.get("contacts"))
        .first()
        .satisfies(
            c ->
                assertThat(c)
                    .containsEntry("kind", "mobile")
                    .containsEntry("value", "38999990000"));
    assertThat((Map<String, Object>) p.get("source"))
        .containsEntry("system", "PEC")
        .containsEntry("source_record_version", "2026-01-01 10:00:00")
        .containsEntry("cnes", "2206997");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void transformaLinhaJdbcEmJson() {
    Map<String, Object> row =
        Map.of(
            "CO_SEQ_AGENDADO",
            5001L,
            "co_cidadao",
            1001L,
            "nu_cns_cidadao",
            "898001234567890",
            "dt_agendado",
            Timestamp.valueOf("2026-01-10 14:00:00"),
            "co_situacao_agendado",
            4,
            "nu_cnes",
            "2206997",
            "dt_atualizado",
            Timestamp.valueOf("2026-01-01 10:05:00"));
    Map<String, String> normalized = PecRows.normalize(row);
    assertThat(normalized)
        .containsEntry("co_seq_agendado", "5001")
        .containsEntry("dt_agendado", "2026-01-10 14:00:00");
    byte[] json =
        ("{\"co_seq_agendado\":\"5001\",\"co_cidadao\":\"1001\",\"nu_cns_cidadao\":\"898001234567890\",\"dt_agendado\":\"2026-01-10 14:00:00\",\"co_situacao_agendado\":\"4\",\"nu_cnes\":\"2206997\",\"dt_atualizado\":\"2026-01-01 10:05:00\"}")
            .getBytes(StandardCharsets.UTF_8);
    CanonicalBatch batch =
        connector.transform(
            new RawMessage(
                "5001", "2026-01-01 10:05:00", "appointment", PecRows.JSON, json, Map.of(), null));
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat(p)
        .containsEntry("status", "fulfilled")
        .containsEntry("kind", "direct")
        .containsEntry("scheduled_start", "2026-01-10T14:00:00-03:00")
        .containsEntry("health_unit_cnes", "2206997");
    assertThat((Map<String, Object>) p.get("citizen_ref"))
        .containsEntry("identifier_system", "CNS")
        .containsEntry("identifier_value", "898001234567890");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void pontaAPontaModoArquivoPublicaCidadaosEAgendamentos() throws IOException {
    Path in = Path.of(config.file().inputDir());
    Files.createDirectories(in);
    for (String f : List.of("cidadaos_20260101.csv", "agendamentos_20260101.csv")) {
      Path tmp = in.resolve(f + ".part");
      Files.write(tmp, resource("samples/" + f));
      Files.move(tmp, in.resolve(f), StandardCopyOption.ATOMIC_MOVE);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(ledger.count("citizen", IntegrationMessageStatus.PUBLISHED, null))
                  .isEqualTo(2);
              assertThat(ledger.count("appointment", IntegrationMessageStatus.PUBLISHED, null))
                  .isEqualTo(2);
              assertThat(ledger.count("citizen", IntegrationMessageStatus.DEAD_LETTERED, null))
                  .isEqualTo(1);
            });

    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/citizens"))
            .withHeader(
                "Idempotency-Key", equalTo(IdempotencyKeys.of("1001", "2026-01-01 10:00:00")))
            .withHeader("X-Tenant-Id", equalTo("ibge_3143302"))
            .withHeader("X-Correlation-Id", matching("corr_.+"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withRequestBody(matchingJsonPath("$.source.source_record_id", equalTo("1001")))
            .withRequestBody(
                matchingJsonPath("$.identifiers[?(@.system == 'CPF' && @.value == '12345678909')]"))
            .withRequestBody(
                matchingJsonPath(
                    "$.identifiers[?(@.system == 'CNS' && @.value == '898001234567890')]"))
            .withRequestBody(matchingJsonPath("$.territory.health_unit_cnes", equalTo("2206997"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/appointments"))
            .withHeader(
                "Idempotency-Key", equalTo(IdempotencyKeys.of("5002", "2026-01-01 11:05:00")))
            .withRequestBody(matchingJsonPath("$.status", equalTo("cancelled")))
            .withRequestBody(
                matchingJsonPath("$.cancellation_reason", equalTo("Paciente desmarcou")))
            .withRequestBody(matchingJsonPath("$.citizen_ref.identifier_system", equalTo("CNS"))));
    // cidadão 1003 sem data de nascimento → erro permanente de mapeamento → DLQ, sem chamada ao
    // core
    assertThat(dlq.open(100)).anySatisfy(dl -> assertThat(dl.stage()).isEqualTo("transform"));
    WireMockCoreResource.server.verify(2, postRequestedFor(urlEqualTo("/api/v1/citizens")));
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = PecConnectorTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
