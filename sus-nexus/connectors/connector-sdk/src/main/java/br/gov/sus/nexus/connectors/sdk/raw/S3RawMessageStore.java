package br.gov.sus.nexus.connectors.sdk.raw;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Raw zone em S3/MinIO (AWS SDK v2). Chave: {@code
 * <prefix>/<connector_id>/<yyyy>/<MM>/<dd>/<message_id>.raw}; hash e metadados vão como user
 * metadata do objeto. Não coberto por teste de rede.
 */
public class S3RawMessageStore implements RawMessageStore {

  private final S3Client s3;
  private final String bucket;
  private final String prefix;

  public S3RawMessageStore(S3Client s3, String bucket, String prefix) {
    this.s3 = s3;
    this.bucket = bucket;
    this.prefix = prefix == null || prefix.isBlank() ? "raw" : prefix.replaceAll("/+$", "");
  }

  @Override
  public RawMessageRef store(String connectorId, String messageId, RawMessage message) {
    Instant now = Instant.now();
    LocalDate day = now.atOffset(ZoneOffset.UTC).toLocalDate();
    String key =
        String.format(
            "%s/%s/%04d/%02d/%02d/%s.raw",
            prefix,
            connectorId,
            day.getYear(),
            day.getMonthValue(),
            day.getDayOfMonth(),
            messageId);
    String sha = Hashes.sha256Hex(message.content());
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("sha256", sha);
    meta.put("connector-id", connectorId);
    meta.put("source-record-id", nullSafe(message.sourceRecordId()));
    meta.put("source-record-version", nullSafe(message.sourceRecordVersion()));
    meta.put("entity-type", nullSafe(message.entityType()));
    meta.put("received-at", message.receivedAt().toString());
    s3.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(message.contentType())
            .metadata(meta)
            .build(),
        RequestBody.fromBytes(message.content()));
    return new RawMessageRef("s3://" + bucket + "/" + key, sha, message.content().length, now);
  }

  @Override
  public Optional<byte[]> read(RawMessageRef ref) {
    String uri = ref.uri();
    String withoutScheme = uri.substring("s3://".length());
    int slash = withoutScheme.indexOf('/');
    String b = withoutScheme.substring(0, slash);
    String key = withoutScheme.substring(slash + 1);
    try {
      ResponseBytes<GetObjectResponse> bytes =
          s3.getObjectAsBytes(GetObjectRequest.builder().bucket(b).key(key).build());
      return Optional.of(bytes.asByteArray());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }
}
