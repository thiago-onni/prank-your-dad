package br.gov.sus.nexus.connectors.sisreg;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.core.IdempotencyKeys;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class SisregConnectorTest {

  private static final String STATUS_BY_SOURCE =
      "/api/v1/regulation/requests/by-source/SISREG/SOL-2026-0002/status";

  @Inject SisregConnector connector;
  @Inject SisregRoutes routes;
  @Inject IntegrationMessageLedger ledger;
  @Inject SisregConfig config;

  @BeforeEach
  void reset() {
    WireMockCoreResource.server.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    routes.registry().clear();
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo("/api/v1/regulation/requests"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"reg_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"status\":\"requested\"}")));
    WireMockCoreResource.server.stubFor(
        post(urlPathMatching("/api/v1/regulation/requests/by-source/.*/status"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"reg_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"status\":\"scheduled\"}")));
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo("/api/v1/regulation/capacity"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"created\":2,\"updated\":0,\"unchanged\":0,\"rejected\":0}")));
  }

  @Test
  void descriptorCompleto() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().supportedEntities())
        .containsExactly("regulation_request", "regulation_status", "provider_capacity");
    assertThat(connector.layout().kinds())
        .extracting(SisregLayout.Kind::name)
        .containsExactly("solicitacoes", "agendamentos", "devolucoes", "oferta");
    assertThat(connector.layout().forFile("Relatorio_Solicitacoes_jan.xlsx"))
        .get()
        .extracting(SisregLayout.Kind::entity)
        .isEqualTo("regulation_request");
  }

  @Test
  @SuppressWarnings("unchecked")
  void transformaSolicitacaoComLookupsVersionados() {
    String json =
        "{\"Código da Solicitação\":\"SOL-2026-0002\",\"CNS do Paciente\":\"898001234567891\","
            + "\"Tipo\":\"EXAME\",\"Código do Procedimento\":\"02.05.01.004-8\","
            + "\"CNES Solicitante\":\"2206997\",\"CNES Executante\":\"2131234\","
            + "\"Classificação de Risco\":\"Verde\",\"Situação\":\"Agendada\","
            + "\"Data da Solicitação\":\"03/01/2026\",\"Data do Agendamento\":\"20/01/2026 14:30\","
            + "\"Regulador\":\"REGULADOR 1\",\"Data da Situação\":\"10/01/2026 11:00:00\"}";
    RawMessage raw =
        new RawMessage(
            "SOL-2026-0002",
            null,
            CanonicalBatch.REGULATION_REQUEST,
            SisregConnector.JSON,
            json.getBytes(StandardCharsets.UTF_8),
            Map.of(SisregConnector.META_KIND, "solicitacoes"),
            null);
    CanonicalBatch batch = connector.transform(raw);
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat(p)
        .containsEntry("status", "scheduled")
        .containsEntry("priority", "priority")
        .containsEntry("kind", "exam")
        .containsEntry("requested_service_code", "0205010048")
        .containsEntry("code_system", "SIGTAP")
        .containsEntry("requesting_cnes", "2206997")
        .containsEntry("provider_cnes", "2131234")
        .containsEntry("requested_at", "2026-01-03T00:00:00-03:00")
        .containsEntry("scheduled_at", "2026-01-20T14:30:00-03:00")
        .containsEntry("occurred_at", "2026-01-10T11:00:00-03:00")
        .containsEntry("regulator_id", "REGULADOR 1");
    assertThat((Map<String, Object>) p.get("citizen_ref"))
        .containsEntry("identifier_system", "CNS")
        .containsEntry("identifier_value", "898001234567891");
    assertThat((Map<String, Object>) p.get("source"))
        .containsEntry("system", "SISREG")
        .containsEntry("source_record_id", "SOL-2026-0002");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void kindPorCodigoSigtapQuandoTipoAusente() {
    assertThat(SisregRules.kind("", "03.01.01.007-2")).isEqualTo("consultation");
    assertThat(SisregRules.kind(null, "0205010048")).isEqualTo("exam");
    assertThat(SisregRules.kind("", "0401010015")).isEqualTo("surgery");
    assertThat(SisregRules.kind("Internação", "0303010010")).isEqualTo("admission");
    assertThat(SisregRules.kind("", "0301070040")).isEqualTo("procedure");
  }

  @Test
  void situacaoDesconhecidaEmArquivoDeStatusEhErroPermanente() {
    String json =
        "{\"codigo_solicitacao\":\"X\",\"situacao\":\"ABDUZIDA\",\"data_devolucao\":\"01/01/2026\"}";
    RawMessage raw =
        new RawMessage(
            "X",
            null,
            CanonicalBatch.REGULATION_STATUS,
            SisregConnector.JSON,
            json.getBytes(StandardCharsets.UTF_8),
            Map.of(SisregConnector.META_KIND, "devolucoes"),
            null);
    assertThatThrownBy(() -> connector.transform(raw)).hasMessageContaining("ABDUZIDA");
  }

  @Test
  void leXlsxComDatasECelulasNumericas() throws IOException {
    byte[] xlsx = agendamentosXlsx();
    List<Map<String, String>> rows =
        new SpreadsheetReader(StandardCharsets.UTF_8, ';', 0).readXlsx(xlsx);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("Código da Solicitação", "SOL-2026-0002")
        .containsEntry("CNES Executante", "2131234")
        .containsEntry("Data do Agendamento", "20/01/2026 14:30:00");
    Map<String, String> canonical =
        connector.layout().require("agendamentos").canonicalize(rows.get(0));
    assertThat(canonical)
        .containsEntry("codigo_solicitacao", "SOL-2026-0002")
        .containsEntry("situacao", "AGENDADA")
        .containsEntry("data_evento", "19/01/2026 08:00:00");
  }

  @Test
  void pontaAPontaPublicaSolicitacoesStatusEOfertaComMarcaDaguaPorArquivo() throws IOException {
    Path in = Path.of(config.file().inputDir());
    Files.createDirectories(in);
    drop(in, "solicitacoes_202601.csv", resource("samples/solicitacoes_202601.csv"));
    drop(in, "agendamentos_202601.xlsx", agendamentosXlsx());
    drop(in, "oferta_202601.csv", resource("samples/oferta_202601.csv"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(published(CanonicalBatch.REGULATION_REQUEST)).isEqualTo(3);
              assertThat(published(CanonicalBatch.REGULATION_STATUS)).isEqualTo(1);
              assertThat(published(CanonicalBatch.PROVIDER_CAPACITY)).isEqualTo(2);
            });

    WireMockCoreResource.server.verify(
        3, postRequestedFor(urlEqualTo("/api/v1/regulation/requests")));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/regulation/requests"))
            .withHeader("X-Tenant-Id", equalTo("ibge_3143302"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withHeader(
                "Idempotency-Key",
                equalTo(IdempotencyKeys.of("SOL-2026-0001", "05/01/2026 09:15:00")))
            .withRequestBody(matchingJsonPath("$.source.system", equalTo("SISREG")))
            .withRequestBody(
                matchingJsonPath("$.source.source_record_id", equalTo("SOL-2026-0001")))
            .withRequestBody(matchingJsonPath("$.status", equalTo("requested")))
            .withRequestBody(matchingJsonPath("$.priority", equalTo("urgent")))
            .withRequestBody(matchingJsonPath("$.kind", equalTo("consultation")))
            .withRequestBody(matchingJsonPath("$.requested_service_code", equalTo("0301010072")))
            .withRequestBody(matchingJsonPath("$.specialty", equalTo("CARDIOLOGIA")))
            .withRequestBody(
                matchingJsonPath("$.requested_at", equalTo("2026-01-05T09:15:00-03:00")))
            .withRequestBody(matchingJsonPath("$.citizen_ref.identifier_system", equalTo("CNS")))
            .withRequestBody(
                matchingJsonPath("$.citizen_ref.identifier_value", equalTo("898001234567891"))));
    // linha 3: sem CNS/CPF → identificador cai para o código do paciente no SISREG
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/regulation/requests"))
            .withRequestBody(
                matchingJsonPath("$.source.source_record_id", equalTo("SOL-2026-0003")))
            .withRequestBody(matchingJsonPath("$.status", equalTo("returned")))
            .withRequestBody(matchingJsonPath("$.priority", equalTo("emergency")))
            .withRequestBody(matchingJsonPath("$.kind", equalTo("surgery")))
            .withRequestBody(matchingJsonPath("$.decision_reason", equalTo("Falta exame prévio")))
            .withRequestBody(matchingJsonPath("$.citizen_ref.identifier_system", equalTo("SISREG")))
            .withRequestBody(matchingJsonPath("$.citizen_ref.identifier_value", equalTo("PAC-3"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo(STATUS_BY_SOURCE))
            .withRequestBody(matchingJsonPath("$.status", equalTo("scheduled")))
            .withRequestBody(matchingJsonPath("$.provider_cnes", equalTo("2131234")))
            .withRequestBody(
                matchingJsonPath("$.occurred_at", equalTo("2026-01-19T08:00:00-03:00")))
            .withRequestBody(
                matchingJsonPath("$.scheduled_at", equalTo("2026-01-20T14:30:00-03:00")))
            .withRequestBody(matchingJsonPath("$.regulator_id", equalTo("OPERADOR X")))
            .withRequestBody(
                matchingJsonPath("$.source.source_record_id", equalTo("SOL-2026-0002"))));
    assertThat(
            WireMockCoreResource.server
                .findAll(postRequestedFor(urlEqualTo(STATUS_BY_SOURCE)))
                .get(0)
                .getBodyAsString())
        .doesNotContain("target_ref");
    // oferta: uma mensagem (e um POST) por linha da planilha
    WireMockCoreResource.server.verify(
        2, postRequestedFor(urlEqualTo("/api/v1/regulation/capacity")));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/regulation/capacity"))
            .withRequestBody(
                matchingJsonPath(
                    "$.items[?(@.provider_cnes == '2131234' && @.service_code == '0301010072' && @.competence == '202601' && @.offered == 120 && @.used == 85 && @.available == 35)]"))
            .withRequestBody(matchingJsonPath("$.items[0].source_system", equalTo("SISREG")))
            .withRequestBody(
                matchingJsonPath("$.items[0].updated_at", equalTo("2025-12-31T18:00:00-03:00"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/regulation/capacity"))
            .withRequestBody(
                matchingJsonPath(
                    "$.items[?(@.service_code == '0205010048' && @.offered == 40 && @.available == 0)]")));

    // Marca d'água por arquivo: mesmo conteúdo com outro nome não é reprocessado.
    int before = routes.registry().size();
    drop(in, "solicitacoes_202601_copia.csv", resource("samples/solicitacoes_202601.csv"));
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> !Files.exists(in.resolve("solicitacoes_202601_copia.csv")));
    assertThat(routes.registry().size()).isEqualTo(before);
    WireMockCoreResource.server.verify(
        3, postRequestedFor(urlEqualTo("/api/v1/regulation/requests")));
  }

  private long published(String entity) {
    return ledger.count(entity, IntegrationMessageStatus.PUBLISHED, null);
  }

  private static void drop(Path dir, String name, byte[] content) throws IOException {
    Path tmp = dir.resolve(name + ".part");
    Files.write(tmp, content);
    Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
  }

  /** Planilha de agendamentos com datas em células de data e CNES numérico. */
  static byte[] agendamentosXlsx() throws IOException {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("Agendamentos");
      CellStyle date = wb.createCellStyle();
      date.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
      Row header = sheet.createRow(0);
      String[] cols = {
        "Código da Solicitação",
        "CNES Executante",
        "Data do Agendamento",
        "Data Marcação",
        "Operador Marcação",
        "Observação"
      };
      for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue("SOL-2026-0002");
      row.createCell(1).setCellValue(2131234d);
      row.createCell(2).setCellValue(LocalDateTime.of(2026, 1, 20, 14, 30));
      row.getCell(2).setCellStyle(date);
      row.createCell(3).setCellValue(LocalDateTime.of(2026, 1, 19, 8, 0));
      row.getCell(3).setCellStyle(date);
      row.createCell(4).setCellValue("OPERADOR X");
      row.createCell(5).setCellValue("");
      wb.write(out);
      return out.toByteArray();
    }
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = SisregConnectorTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
