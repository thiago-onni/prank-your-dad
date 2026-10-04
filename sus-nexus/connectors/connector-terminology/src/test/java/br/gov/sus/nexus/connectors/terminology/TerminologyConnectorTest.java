package br.gov.sus.nexus.connectors.terminology;

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
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class TerminologyConnectorTest {

  @Inject TerminologyConnector connector;
  @Inject IntegrationMessageLedger ledger;
  @Inject TerminologyConfig config;

  @BeforeEach
  void reset() {
    WireMockCoreResource.server.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    WireMockCoreResource.server.stubFor(
        post(urlPathMatching("/api/v1/terminology/[A-Z0-9]+/codes/upsert"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"created\":2,\"updated\":0}")));
  }

  @Test
  void descriptorCompleto() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().connectorId()).isEqualTo("connector-terminology");
    assertThat(connector.descriptor().fieldMappingVersion()).isEqualTo("1.0.0");
  }

  @Test
  void transformaSigtapLarguraFixa() throws IOException {
    byte[] content = resource("samples/tb_procedimento.txt");
    RawMessage raw =
        new RawMessage(
            "tb_procedimento.txt",
            "v1",
            "code",
            "text/plain",
            content,
            Map.of("table", "tb_procedimento"),
            null);
    CanonicalBatch batch = connector.transform(raw);
    assertThat(batch.entityType()).isEqualTo(CanonicalBatch.CODE);
    assertThat(batch.attributes())
        .containsEntry("system", "SIGTAP")
        .containsEntry("competence", "202601");
    assertThat(batch.records()).hasSize(3);
    assertThat(batch.records().get(0).payload())
        .containsEntry("code", "0101010010")
        .containsEntry("display", "CONSULTA PARA O ACOMPANHAMENTO DO CRESCIMENTO E DESENVOLVIMENTO")
        .containsEntry("competence_from", "202601");
    @SuppressWarnings("unchecked")
    Map<String, Object> attrs =
        (Map<String, Object>) batch.records().get(2).payload().get("attributes");
    assertThat(attrs)
        .containsEntry("tp_complexidade", "2")
        .containsEntry("vl_sa", "0000001000")
        .containsEntry("co_financiamento", "06");
    ValidationReport report = connector.validate(batch);
    assertThat(report.isValid()).isTrue();
  }

  @Test
  void validacaoReprovaCodigoVazio() {
    RawMessage raw =
        RawMessage.ofText("cid10.csv", "code", "codigo;descricao\n;Sem código\n", Map.of());
    ValidationReport report = connector.validate(connector.transform(raw));
    assertThat(report.isValid()).isFalse();
    assertThat(report.errors()).extracting(ValidationReport.Issue::field).contains("code");
  }

  @Test
  void pontaAPontaPorArquivoPublicaEmLotesNoCore() throws IOException {
    Path in = Path.of(config.inputDir());
    Files.createDirectories(in);
    for (String f : List.of("tb_procedimento.txt", "cid10.csv", "cbo.csv", "ciap2.csv")) {
      Path tmp = in.resolve(f + ".part");
      Files.write(tmp, resource("samples/" + f));
      Files.move(tmp, in.resolve(f), StandardCopyOption.ATOMIC_MOVE);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(ledger.count("code", IntegrationMessageStatus.PUBLISHED, null))
                    .isEqualTo(4));

    // batch-size=2 em teste: SIGTAP (3 linhas) gera 2 lotes; CID-10 (3) gera 2; CBO e CIAP-2 (2) 1
    // cada
    WireMockCoreResource.server.verify(
        2, postRequestedFor(urlEqualTo("/api/v1/terminology/SIGTAP/codes/upsert")));
    WireMockCoreResource.server.verify(
        2, postRequestedFor(urlEqualTo("/api/v1/terminology/CID10/codes/upsert")));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/terminology/CBO/codes/upsert"))
            .withHeader("X-Tenant-Id", equalTo("ibge_3143302"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withRequestBody(matchingJsonPath("$.items[0].code", equalTo("225125")))
            .withRequestBody(matchingJsonPath("$.items[0].display", equalTo("Médico clínico")))
            .withRequestBody(
                matchingJsonPath("$.source.connector", equalTo("connector-terminology"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/terminology/CIAP2/codes/upsert"))
            .withRequestBody(matchingJsonPath("$.items[1].code", equalTo("T90"))));
    WireMockCoreResource.server.verify(
        postRequestedFor(urlEqualTo("/api/v1/terminology/SIGTAP/codes/upsert"))
            .withRequestBody(matchingJsonPath("$.competence", equalTo("202601")))
            .withRequestBody(matchingJsonPath("$.version", equalTo("1.0.0"))));
    assertThat(Files.list(in.resolve(".done")).findAny()).isPresent();
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in =
        TerminologyConnectorTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
