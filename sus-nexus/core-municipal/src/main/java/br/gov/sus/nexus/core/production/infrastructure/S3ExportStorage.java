package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.production.application.ExportStorage;
import java.net.URI;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * {@link ExportStorage} em object storage S3/MinIO (AWS SDK v2 síncrono, {@code
 * sus.production.export-storage=s3}). Chave {@code
 * [prefixo]<tenant>/<competência>/<lote>/<arquivo>}; referência {@code s3://<bucket>/<chave>}.
 *
 * <ul>
 *   <li><b>Criptografia em repouso</b> por objeto: {@code x-amz-server-side-encryption} ({@code
 *       AES256} = SSE-S3/MinIO KMS padrão, ou {@code aws:kms} com chave) — {@code none} só em dev
 *       sem KMS.
 *   <li><b>Sem sobrescrita</b>: {@code If-None-Match: *} (escrita condicional) — reexportar gera
 *       novo nome.
 *   <li><b>Integridade</b>: SHA-256 calculado no core, enviado em {@code x-amz-checksum-sha256} (o
 *       servidor confere) e em metadado {@code sha256}.
 *   <li>O arquivo contém CNS em claro: o bucket deve ter acesso restrito (somente a credencial do
 *       core; sem listagem pública; retenção/versionamento conforme a política do município).
 * </ul>
 */
public class S3ExportStorage implements ExportStorage, AutoCloseable {

  private final S3Client client;
  private final String bucket;
  private final String prefix;
  private final String sse;
  private final Optional<String> kmsKeyId;

  public S3ExportStorage(
      S3Client client, String bucket, String prefix, String sse, Optional<String> kmsKeyId) {
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalArgumentException("bucket de exportação obrigatório");
    }
    this.client = client;
    this.bucket = bucket;
    this.prefix = normalizePrefix(prefix);
    this.sse = sse == null || sse.isBlank() ? "AES256" : sse.trim();
    this.kmsKeyId = kmsKeyId == null ? Optional.empty() : kmsKeyId.filter(k -> !k.isBlank());
    if (!Set.of("AES256", "aws:kms", "none").contains(this.sse)) {
      throw new IllegalArgumentException("sse inválido (AES256 | aws:kms | none): " + this.sse);
    }
  }

  /** Constrói o cliente a partir da configuração (sem chamadas de rede no construtor). */
  public static S3ExportStorage fromConfig(
      String bucket,
      String region,
      Optional<String> endpoint,
      boolean pathStyle,
      Optional<String> accessKey,
      Optional<String> secretKey,
      String prefix,
      String sse,
      Optional<String> kmsKeyId) {
    var builder =
        S3Client.builder()
            .region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
            // compatível com MinIO: checksum só quando exigido (o SHA-256 vai explícito)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    endpoint.filter(e -> !e.isBlank()).ifPresent(e -> builder.endpointOverride(URI.create(e)));
    if (accessKey.filter(a -> !a.isBlank()).isPresent()
        && secretKey.filter(k -> !k.isBlank()).isPresent()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(accessKey.get(), secretKey.get())));
    }
    return new S3ExportStorage(builder.build(), bucket, prefix, sse, kmsKeyId);
  }

  @Override
  public StoredFile store(
      String tenantId, String competence, String batchId, String name, byte[] content) {
    ExportStorage.checkKey(tenantId, competence, batchId, name);
    String key = prefix + tenantId + "/" + competence + "/" + batchId + "/" + name;
    String sha256 = ExportStorage.sha256(content);
    PutObjectRequest.Builder put =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(name.endsWith(".csv") ? "text/csv" : "text/plain")
            .contentLength((long) content.length)
            .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)))
            .metadata(Map.of("sha256", sha256, "tenant", tenantId, "batch", batchId))
            .ifNoneMatch("*");
    switch (sse) {
      case "AES256" -> put.serverSideEncryption(ServerSideEncryption.AES256);
      case "aws:kms" -> {
        put.serverSideEncryption(ServerSideEncryption.AWS_KMS);
        kmsKeyId.ifPresent(put::ssekmsKeyId);
      }
      default -> {
        // none: sem cabeçalho de SSE (dev sem KMS)
      }
    }
    try {
      client.putObject(put.build(), RequestBody.fromBytes(content));
    } catch (S3Exception e) {
      if (e.statusCode() == 412) {
        throw new IllegalStateException("arquivo de exportação já existe: " + key, e);
      }
      throw new IllegalStateException(
          "falha ao gravar arquivo de exportação no object storage (" + e.statusCode() + ")", e);
    }
    return new StoredFile("s3://" + bucket + "/" + key, sha256, content.length);
  }

  @Override
  public byte[] read(String ref) {
    URI uri = URI.create(ref);
    if (!"s3".equals(uri.getScheme()) || !bucket.equals(uri.getHost())) {
      throw new IllegalArgumentException("referência fora do bucket de exportação");
    }
    String key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
    if (!key.startsWith(prefix) || key.contains("..")) {
      throw new IllegalArgumentException("referência fora do prefixo de exportação");
    }
    try {
      return client
          .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
          .asByteArray();
    } catch (NoSuchKeyException e) {
      throw new IllegalArgumentException("arquivo de exportação inexistente", e);
    } catch (S3Exception e) {
      throw new IllegalStateException(
          "falha ao ler arquivo de exportação do object storage (" + e.statusCode() + ")", e);
    }
  }

  public String bucket() {
    return bucket;
  }

  @Override
  public void close() {
    client.close();
  }

  private static String normalizePrefix(String prefix) {
    if (prefix == null || prefix.isBlank()) {
      return "";
    }
    String p = prefix.trim();
    if (!p.matches("^[A-Za-z0-9_./-]+$") || p.contains("..") || p.startsWith("/")) {
      throw new IllegalArgumentException("prefixo de exportação inválido");
    }
    return p.endsWith("/") ? p : p + "/";
  }
}
