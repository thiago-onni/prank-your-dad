package br.gov.sus.nexus.connectors.cnes;

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
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import com.linuxense.javadbf.DBFDataType;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFWriter;
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
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class CnesConnectorTest {

  @Inject CnesConnector connector;
  @Inject IntegrationMessageLedger ledger;
  @Inject CnesConfig config;

  @BeforeEach
  void reset() {
    WireMockCoreResource.server.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo("/api/v1/reference/health-units/upsert"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"created\":2}")));
  }

  @Test
  void descriptorCompleto() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().sourceSystem()).isEqualTo("CNES");
  }

  @Test
  @SuppressWarnings("unchecked")
  void transformaCsvFiltrandoMunicipioGestor() throws IOException {
    RawMessage raw =
        new RawMessage(
            "tbEstabelecimento202601.csv",
            "v1",
            "health_unit",
            "text/csv",
            resource("samples/tbEstabelecimento202601.csv"),
            Map.of("competence", "202601"),
            null);
    CanonicalBatch batch = connector.transform(raw);
    assertThat(batch.records()).hasSize(2);
    Map<String, Object> ubs = batch.records().get(0).payload();
    assertThat(ubs)
        .containsEntry("cnes", "2206997")
        .containsEntry("name", "UBS SAO JUDAS")
        .containsEntry("kind_code", "02")
        .containsEntry("kind_description", "CENTRO DE SAUDE/UNIDADE BASICA")
        .containsEntry("address", "RUA DAS FLORES, 120 - SAO JUDAS CEP 39400000")
        .containsEntry("city_ibge", "314330")
        .containsEntry("active", true);
    assertThat((Map<String, Object>) ubs.get("attributes")).containsEntry("telefone", "3832221111");
    Map<String, Object> upa = batch.records().get(1).payload();
    assertThat(upa)
        .containsEntry("name", "UPA NORTE")
        .containsEntry("kind_description", "PRONTO ATENDIMENTO");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void leDbfGeradoComJavadbf() throws IOException {
    byte[] dbf = buildDbf();
    RawMessage raw =
        new RawMessage(
            "tbEstabelecimento202601.dbf",
            "v1",
            "health_unit",
            "application/octet-stream",
            dbf,
            Map.of(),
            null);
    CanonicalBatch batch = connector.transform(raw);
    assertThat(batch.records()).hasSize(1);
    assertThat(batch.records().get(0).payload())
        .containsEntry("cnes", "2206997")
        .containsEntry("name", "UBS DBF")
        .containsEntry("kind_code", "02")
        .containsEntry("city_ibge", "314330")
        .containsEntry("address", "RUA A");
  }

  @Test
  void validacaoReprovaCnesInvalido() {
    RawMessage raw =
        RawMessage.ofText(
            "x.csv",
            "health_unit",
            "CO_CNES;NO_FANTASIA;TP_UNIDADE;CO_MUNICIPIO_GESTOR\n12;UBS;02;314330\n",
            Map.of());
    assertThat(connector.validate(connector.transform(raw)).errors())
        .extracting(i -> i.code())
        .contains("format");
  }

  @Test
  void pontaAPontaPublicaUnidadesNoCore() throws IOException {
    Path in = Path.of(config.inputDir());
    Files.createDirectories(in);
    Path tmp = in.resolve("tbEstabelecimento202601.csv.part");
    Files.write(tmp, resource("samples/tbEstabelecimento202601.csv"));
    Files.move(tmp, in.resolve("tbEstabelecimento202601.csv"), StandardCopyOption.ATOMIC_MOVE);

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(ledger.count("health_unit", IntegrationMessageStatus.PUBLISHED, null))
                    .isEqualTo(1));

    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/reference/health-units/upsert"))
            .withHeader("X-Tenant-Id", equalTo("ibge_3143302"))
            .withHeader("Idempotency-Key", matching("[a-f0-9]{64}"))
            .withHeader("X-Correlation-Id", matching("corr_.+"))
            .withRequestBody(matchingJsonPath("$.competence", equalTo("202601")))
            .withRequestBody(matchingJsonPath("$.source.system", equalTo("CNES")))
            .withRequestBody(matchingJsonPath("$.items[0].cnes", equalTo("2206997")))
            .withRequestBody(matchingJsonPath("$.items[1].cnes", equalTo("2207004"))));
  }

  private static byte[] buildDbf() throws IOException {
    DBFField[] fields = {
      // nomes truncados a 10 caracteres, como nos DBF oficiais
      field("CO_CNES", 7),
      field("NO_FANTASI", 60),
      field("TP_UNIDADE", 2),
      field("CO_MUNICIP", 6),
      field("NO_LOGRADO", 40)
    };
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DBFWriter writer = new DBFWriter(out, StandardCharsets.UTF_8)) {
      writer.setFields(fields);
      writer.addRecord(new Object[] {"2206997", "UBS DBF", "02", "314330", "RUA A"});
    }
    return out.toByteArray();
  }

  private static DBFField field(String name, int length) {
    DBFField f = new DBFField();
    f.setName(name);
    f.setType(DBFDataType.CHARACTER);
    f.setLength(length);
    return f;
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = CnesConnectorTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
