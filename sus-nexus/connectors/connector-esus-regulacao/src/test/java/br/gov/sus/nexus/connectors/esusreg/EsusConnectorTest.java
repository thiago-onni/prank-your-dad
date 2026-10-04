package br.gov.sus.nexus.connectors.esusreg;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class EsusConnectorTest {

  private static final String REQUESTS = "/esus/api/v1/regulacao/solicitacoes";
  private static final String EVENTS = "/esus/api/v1/regulacao/eventos";
  private static final String SINCE0 = "1970-01-01T00:00:00Z";

  @Inject EsusConnector connector;
  @Inject EsusRoutes routes;
  @Inject IntegrationMessageLedger ledger;
  @Inject EsusConfig config;

  @BeforeEach
  void reset() {
    WireMockCoreResource.server.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo("/api/v1/regulation/requests"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"reg_01HZZZZZZZZZZZZZZZZZZZZZZZ\"}")));
    WireMockCoreResource.server.stubFor(
        post(urlPathMatching("/api/v1/regulation/requests/by-source/.*/status"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"reg_01HZZZZZZZZZZZZZZZZZZZZZZZ\"}")));
    // API e-SUS Regulação: token + páginas vazias por padrão (prioridade baixa)
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo("/esus/oauth/token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"tok-esus\",\"expires_in\":3600}")));
    WireMockCoreResource.server.stubFor(
        get(urlPathMatching("/esus/api/.*"))
            .atPriority(10)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[],\"total\":0}")));
  }

  @Test
  void descriptorCompletoEModoApi() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().supportedEntities())
        .containsExactly("regulation_request", "regulation_status");
    assertThat(config.mode()).isEqualTo("api");
  }

  @Test
  @SuppressWarnings("unchecked")
  void transformaItemJsonAninhadoComCaminhos() {
    String json =
        "{\"id\":\"REG-1\",\"tipo\":\"Consulta especializada\",\"situacao\":\"Aguardando regulação\","
            + "\"prioridade\":\"Amarelo\",\"data_solicitacao\":\"2026-01-05T09:15:00-03:00\","
            + "\"procedimento\":{\"codigo\":\"03.01.01.007-2\",\"sistema\":\"sigtap\"},"
            + "\"especialidade\":\"CARDIOLOGIA\",\"unidade_solicitante\":{\"cnes\":\"2206997\"},"
            + "\"profissional_solicitante\":{\"id\":\"PROF-7\",\"cbo\":\"225125\"},"
            + "\"paciente\":{\"id\":\"P-1\",\"cns\":\"898001234567891\"},"
            + "\"justificativa_presente\":true,\"anexos\":[{\"id\":\"a\"},{\"id\":\"b\"}],"
            + "\"cid\":\"i10\",\"atualizado_em\":\"2026-01-05T09:20:00-03:00\"}";
    CanonicalBatch batch =
        connector.transform(
            new RawMessage(
                "REG-1",
                "2026-01-05T09:20:00-03:00",
                CanonicalBatch.REGULATION_REQUEST,
                EsusConnector.JSON,
                json.getBytes(StandardCharsets.UTF_8),
                Map.of(),
                null));
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat(p)
        .containsEntry("kind", "consultation")
        .containsEntry("status", "requested")
        .containsEntry("priority", "urgent")
        .containsEntry("requested_at", "2026-01-05T09:15:00-03:00")
        .containsEntry("requested_service_code", "0301010072")
        .containsEntry("code_system", "SIGTAP")
        .containsEntry("specialty", "CARDIOLOGIA")
        .containsEntry("requesting_cnes", "2206997")
        .containsEntry("requesting_professional_id", "PROF-7")
        .containsEntry("requesting_professional_cbo", "225125")
        .containsEntry("justification_present", true)
        .containsEntry("attached_documents_count", 2)
        .containsEntry("cid_code", "I10")
        .containsEntry("occurred_at", "2026-01-05T09:20:00-03:00")
        .doesNotContainKey("citizen_identifiers");
    assertThat((Map<String, Object>) p.get("citizen_ref"))
        .containsEntry("identifier_system", "CNS")
        .containsEntry("identifier_value", "898001234567891");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void modoApiPercorreDuasPaginasEEventosComOAuth2() {
    WireMockCoreResource.server.stubFor(
        get(urlPathEqualTo(REQUESTS))
            .withQueryParam("updated_since", equalTo(SINCE0))
            .withQueryParam("page", equalTo("1"))
            .withQueryParam("size", equalTo("2"))
            .willReturn(json(resourceText("samples/api_solicitacoes_p1.json"))));
    WireMockCoreResource.server.stubFor(
        get(urlPathEqualTo(REQUESTS))
            .withQueryParam("updated_since", equalTo(SINCE0))
            .withQueryParam("page", equalTo("2"))
            .willReturn(json(resourceText("samples/api_solicitacoes_p2.json"))));
    WireMockCoreResource.server.stubFor(
        get(urlPathEqualTo(EVENTS))
            .withQueryParam("updated_since", equalTo(SINCE0))
            .withQueryParam("page", equalTo("1"))
            .willReturn(json(resourceText("samples/api_eventos_p1.json"))));
    routes.watermarks().reset();

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(published(CanonicalBatch.REGULATION_REQUEST)).isEqualTo(3);
              assertThat(published(CanonicalBatch.REGULATION_STATUS)).isEqualTo(2);
            });

    WireMockCoreResource.server.verify(
        postRequestedFor(urlEqualTo("/esus/oauth/token"))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(containing("grant_type=client_credentials"))
            .withRequestBody(containing("client_id=sus-nexus-test"))
            .withRequestBody(containing("scope=regulacao.read")));
    WireMockCoreResource.server.verify(
        getRequestedFor(urlPathEqualTo(REQUESTS))
            .withQueryParam("page", equalTo("2"))
            .withHeader("Authorization", equalTo("Bearer tok-esus")));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/regulation/requests"))
            .withRequestBody(matchingJsonPath("$.source.system", equalTo("ESUS_REGULACAO")))
            .withRequestBody(matchingJsonPath("$.source.source_record_id", equalTo("REG-A-003")))
            .withRequestBody(matchingJsonPath("$.status", equalTo("authorized")))
            .withRequestBody(matchingJsonPath("$.kind", equalTo("exam")))
            .withRequestBody(matchingJsonPath("$.provider_cnes", equalTo("2131234")))
            .withRequestBody(matchingJsonPath("$.citizen_ref.identifier_system", equalTo("CPF")))
            .withRequestBody(
                matchingJsonPath("$.citizen_ref.identifier_value", equalTo("12345678909"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(
                urlEqualTo("/api/v1/regulation/requests/by-source/ESUS_REGULACAO/REG-A-001/status"))
            .withRequestBody(matchingJsonPath("$.status", equalTo("scheduled")))
            .withRequestBody(
                matchingJsonPath("$.scheduled_at", equalTo("2026-01-20T14:30:00-03:00")))
            .withRequestBody(matchingJsonPath("$.appointment_source_record_id", equalTo("AG-77")))
            .withRequestBody(matchingJsonPath("$.source.source_record_id", equalTo("EVT-1"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(
                urlEqualTo("/api/v1/regulation/requests/by-source/ESUS_REGULACAO/REG-A-002/status"))
            .withRequestBody(matchingJsonPath("$.status", equalTo("returned")))
            .withRequestBody(matchingJsonPath("$.return_to_origin", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("Falta laudo"))));
    // marca d'água avança para o maior atualizado_em da varredura
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(routes.watermarks().get(CanonicalBatch.REGULATION_REQUEST))
                  .isEqualTo("2026-01-12T10:00:00-03:00");
              assertThat(routes.watermarks().get(CanonicalBatch.REGULATION_STATUS))
                  .isEqualTo("2026-01-12T11:00:00-03:00");
            });
  }

  @Test
  void modoArquivoLeExportacaoJsonECsv() throws IOException {
    Path in = Path.of(config.file().inputDir());
    Files.createDirectories(in);
    drop(in, "solicitacoes_export.json", resource("samples/solicitacoes_export.json"));
    drop(in, "eventos_export.csv", resource("samples/eventos_export.csv"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              WireMockCoreResource.server.verify(
                  1,
                  postRequestedFor(urlEqualTo("/api/v1/regulation/requests"))
                      .withRequestBody(
                          matchingJsonPath("$.source.source_record_id", equalTo("REG-F-001")))
                      .withRequestBody(matchingJsonPath("$.status", equalTo("under_review")))
                      .withRequestBody(matchingJsonPath("$.priority", equalTo("priority")))
                      .withRequestBody(
                          matchingJsonPath("$.citizen_ref.identifier_system", equalTo("CPF"))));
              WireMockCoreResource.server.verify(
                  1,
                  postRequestedFor(
                          urlEqualTo(
                              "/api/v1/regulation/requests/by-source/ESUS_REGULACAO/REG-F-001/status"))
                      .withRequestBody(matchingJsonPath("$.status", equalTo("scheduled")))
                      .withRequestBody(
                          matchingJsonPath("$.occurred_at", equalTo("2026-02-02T10:00:00-03:00")))
                      .withRequestBody(matchingJsonPath("$.provider_cnes", equalTo("2131234")))
                      .withRequestBody(
                          matchingJsonPath("$.source.source_record_id", equalTo("EVT-F-1"))));
            });
  }

  private long published(String entity) {
    return ledger.count(entity, IntegrationMessageStatus.PUBLISHED, null);
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  private static void drop(Path dir, String name, byte[] content) throws IOException {
    Path tmp = dir.resolve(name + ".part");
    Files.write(tmp, content);
    Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
  }

  private static String resourceText(String name) {
    try {
      return new String(resource(name), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = EsusConnectorTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
