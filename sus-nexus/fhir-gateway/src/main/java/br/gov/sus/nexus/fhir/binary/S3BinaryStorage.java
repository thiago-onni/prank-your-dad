package br.gov.sus.nexus.fhir.binary;

import java.net.URI;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Armazenamento em S3 (AWS SDK v2, cliente síncrono). Compatível com MinIO via {@code endpoint} e
 * {@code path-style}. Chave = {@code <tenant>/<id>}; o conteúdo é cifrado em repouso pela política
 * do bucket (SSE) — o gateway não guarda credenciais além da configuração.
 */
public class S3BinaryStorage implements BinaryStorage {

  private final S3Client client;
  private final String bucket;

  public S3BinaryStorage(S3Client client, String bucket) {
    this.client = client;
    this.bucket = bucket;
  }

  /** Constrói o cliente a partir da configuração (sem chamadas de rede no construtor). */
  public static S3BinaryStorage fromConfig(
      String bucket,
      String region,
      Optional<String> endpoint,
      boolean pathStyle,
      Optional<String> accessKey,
      Optional<String> secretKey) {
    var builder =
        S3Client.builder()
            .region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
    endpoint.filter(e -> !e.isBlank()).ifPresent(e -> builder.endpointOverride(URI.create(e)));
    if (accessKey.filter(a -> !a.isBlank()).isPresent()
        && secretKey.filter(k -> !k.isBlank()).isPresent()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(accessKey.get(), secretKey.get())));
    }
    return new S3BinaryStorage(builder.build(), bucket);
  }

  @Override
  public String put(String tenantId, String id, String contentType, byte[] content) {
    String key = tenantId + "/" + id;
    try {
      client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
          RequestBody.fromBytes(content));
    } catch (S3Exception e) {
      throw new BinaryStorageException("Falha ao gravar objeto no S3 (" + e.statusCode() + ")", e);
    }
    return key;
  }

  @Override
  public Optional<byte[]> get(String key) {
    try {
      ResponseBytes<GetObjectResponse> bytes =
          client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
      return Optional.of(bytes.asByteArray());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      throw new BinaryStorageException("Falha ao ler objeto do S3 (" + e.statusCode() + ")", e);
    }
  }

  @Override
  public String describe() {
    return "s3:" + bucket;
  }
}
