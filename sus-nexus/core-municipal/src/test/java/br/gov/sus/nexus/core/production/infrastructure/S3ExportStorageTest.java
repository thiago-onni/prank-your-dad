package br.gov.sus.nexus.core.production.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.core.production.application.ExportStorage;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link S3ExportStorage} contra um S3/MinIO falso (WireMock, path-style): chave por
 * tenant/competência/lote, SSE, escrita condicional sem sobrescrita, SHA-256 conferível pelo
 * servidor, URI {@code s3://}; leitura restrita ao bucket; seleção por configuração.
 */
class S3ExportStorageTest {

  static final String BUCKET = "production-exports";
  static final String TENANT = "ibge_3143302";
  static final byte[] CONTENT =
      "01#BPA#202610...\r\n03123456720261089800123456567...\r\n"
          .getBytes(StandardCharsets.US_ASCII);

  WireMockServer s3;
  S3ExportStorage storage;

  @BeforeEach
  void start() {
    s3 = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    s3.start();
    storage = storage("AES256", Optional.empty(), "");
  }

  @AfterEach
  void stop() {
    storage.close();
    s3.stop();
  }

  S3ExportStorage storage(String sse, Optional<String> kms, String prefix) {
    return S3ExportStorage.fromConfig(
        BUCKET,
        "us-east-1",
        Optional.of(s3.baseUrl()),
        true,
        Optional.of("sus-production-export"),
        Optional.of("segredo-de-teste"),
        prefix,
        sse,
        kms);
  }

  static String key(String prefix) {
    return "/" + BUCKET + "/" + prefix + TENANT + "/202610/pbat_01ABC/arquivo.txt";
  }

  @Test
  void storesWithTenantCompetenceBatchKeySseChecksumAndNoOverwrite() {
    s3.stubFor(
        put(urlEqualTo(key("")))
            .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"x\"")));
    ExportStorage.StoredFile f =
        storage.store(TENANT, "202610", "pbat_01ABC", "arquivo.txt", CONTENT);

    String sha = ExportStorage.sha256(CONTENT);
    assertThat(f.ref())
        .isEqualTo("s3://" + BUCKET + "/" + TENANT + "/202610/pbat_01ABC/arquivo.txt");
    assertThat(f.sha256()).isEqualTo(sha).matches("^[a-f0-9]{64}$");
    assertThat(f.sizeBytes()).isEqualTo(CONTENT.length);
    s3.verify(
        putRequestedFor(urlEqualTo(key("")))
            .withHeader("x-amz-server-side-encryption", equalTo("AES256"))
            .withHeader("If-None-Match", equalTo("*"))
            .withHeader(
                "x-amz-checksum-sha256",
                equalTo(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha))))
            .withHeader("x-amz-meta-sha256", equalTo(sha))
            .withHeader("Authorization", containing("sus-production-export"))
            // corpo assinado em aws-chunked (streaming SigV4): conteúdo íntegro + tamanho
            // decodificado
            .withHeader("x-amz-decoded-content-length", equalTo(String.valueOf(CONTENT.length)))
            .withRequestBody(containing("01#BPA#202610...\r\n031234567")));
  }

  @Test
  void kmsAndPrefixAndNoSse() {
    S3ExportStorage kms = storage("aws:kms", Optional.of("chave-producao"), "core/");
    s3.stubFor(put(urlEqualTo(key("core/"))).willReturn(aResponse().withStatus(200)));
    assertThat(kms.store(TENANT, "202610", "pbat_01ABC", "arquivo.txt", CONTENT).ref())
        .isEqualTo("s3://" + BUCKET + "/core/" + TENANT + "/202610/pbat_01ABC/arquivo.txt");
    s3.verify(
        putRequestedFor(urlEqualTo(key("core/")))
            .withHeader("x-amz-server-side-encryption", equalTo("aws:kms"))
            .withHeader("x-amz-server-side-encryption-aws-kms-key-id", equalTo("chave-producao")));
    kms.close();

    S3ExportStorage none = storage("none", Optional.empty(), "");
    s3.resetRequests();
    s3.stubFor(put(urlEqualTo(key(""))).willReturn(aResponse().withStatus(200)));
    none.store(TENANT, "202610", "pbat_01ABC", "arquivo.txt", CONTENT);
    s3.verify(
        putRequestedFor(urlEqualTo(key(""))).withHeader("x-amz-server-side-encryption", absent()));
    none.close();
    assertThatThrownBy(() -> storage("SSE-C", Optional.empty(), ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void existingObjectIsNeverOverwritten() {
    s3.stubFor(
        put(urlEqualTo(key("")))
            .willReturn(
                aResponse()
                    .withStatus(412)
                    .withHeader("Content-Type", "application/xml")
                    .withBody(
                        "<Error><Code>PreconditionFailed</Code><Message>exists</Message></Error>")));
    assertThatThrownBy(() -> storage.store(TENANT, "202610", "pbat_01ABC", "arquivo.txt", CONTENT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("já existe");
  }

  @Test
  void readsOnlyFromConfiguredBucket() {
    s3.stubFor(
        get(urlPathEqualTo(key(""))).willReturn(aResponse().withStatus(200).withBody(CONTENT)));
    assertThat(storage.read("s3://" + BUCKET + "/" + TENANT + "/202610/pbat_01ABC/arquivo.txt"))
        .isEqualTo(CONTENT);
    assertThatThrownBy(() -> storage.read("s3://outro-bucket/" + TENANT + "/x.txt"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.read("file:///etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsPathTraversalAndInvalidKeys() {
    assertThatThrownBy(() -> storage.store("../ibge_3143302", "202610", "pbat_1", "a.txt", CONTENT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.store(TENANT, "2026-10", "pbat_1", "a.txt", CONTENT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.store(TENANT, "202610", "pbat/../x", "a.txt", CONTENT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.store(TENANT, "202610", "pbat_1", "../a.txt", CONTENT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void producerSelectsImplementationByConfig() throws Exception {
    Path dir = Files.createTempDirectory("prod-exports");
    ExportStorageProducer producer = new ExportStorageProducer();
    producer.exportDir = dir.toString();
    producer.bucket = BUCKET;
    producer.region = "us-east-1";
    producer.endpoint = Optional.of(s3.baseUrl());
    producer.pathStyle = true;
    producer.accessKey = Optional.empty();
    producer.secretKey = Optional.empty();
    producer.prefix = Optional.empty();
    producer.sse = "AES256";
    producer.kmsKeyId = Optional.empty();

    producer.kind = "file";
    ExportStorage file = producer.create();
    assertThat(file).isInstanceOf(FileExportStorage.class);
    ExportStorage.StoredFile stored =
        file.store(TENANT, "202610", "pbat_1", "arquivo.txt", CONTENT);
    assertThat(stored.ref()).startsWith("file:").contains(TENANT + "/202610/pbat_1/arquivo.txt");
    assertThat(file.read(stored.ref())).isEqualTo(CONTENT);
    assertThatThrownBy(() -> file.store(TENANT, "202610", "pbat_1", "arquivo.txt", CONTENT))
        .isInstanceOf(java.io.UncheckedIOException.class);

    producer.kind = "s3";
    ExportStorage s3Storage = producer.create();
    assertThat(s3Storage).isInstanceOf(S3ExportStorage.class);
    assertThat(((S3ExportStorage) s3Storage).bucket()).isEqualTo("production-exports");
    producer.close(s3Storage);

    producer.kind = "ftp";
    assertThatThrownBy(producer::create).isInstanceOf(IllegalStateException.class);
  }
}
