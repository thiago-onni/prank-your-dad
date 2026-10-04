package br.gov.sus.nexus.connectors.sdk.raw;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Raw zone em sistema de arquivos: {@code <dir>/<connector_id>/<yyyy>/<MM>/<dd>/<message_id>.raw} +
 * {@code .meta.json} com hash, content-type e metadados da fonte.
 */
public class FileSystemRawMessageStore implements RawMessageStore {

  private final Path baseDir;
  private final ObjectMapper mapper;

  public FileSystemRawMessageStore(Path baseDir, ObjectMapper mapper) {
    this.baseDir = baseDir;
    this.mapper = mapper;
  }

  @Override
  public RawMessageRef store(String connectorId, String messageId, RawMessage message) {
    Instant now = Instant.now();
    LocalDate day = now.atOffset(ZoneOffset.UTC).toLocalDate();
    Path dir =
        baseDir
            .resolve(connectorId)
            .resolve(String.valueOf(day.getYear()))
            .resolve(String.format("%02d", day.getMonthValue()))
            .resolve(String.format("%02d", day.getDayOfMonth()));
    String sha = Hashes.sha256Hex(message.content());
    try {
      Files.createDirectories(dir);
      Path raw = dir.resolve(messageId + ".raw");
      Files.write(raw, message.content());
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("message_id", messageId);
      meta.put("connector_id", connectorId);
      meta.put("source_record_id", message.sourceRecordId());
      meta.put("source_record_version", message.sourceRecordVersion());
      meta.put("entity_type", message.entityType());
      meta.put("content_type", message.contentType());
      meta.put("sha256", sha);
      meta.put("size_bytes", message.content().length);
      meta.put("received_at", message.receivedAt().toString());
      meta.put("stored_at", now.toString());
      meta.put("metadata", message.metadata());
      mapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(dir.resolve(messageId + ".meta.json").toFile(), meta);
      return new RawMessageRef(raw.toUri().toString(), sha, message.content().length, now);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao gravar raw zone em " + dir, e);
    }
  }

  @Override
  public Optional<byte[]> read(RawMessageRef ref) {
    try {
      Path path = Path.of(java.net.URI.create(ref.uri()));
      if (!Files.exists(path)) return Optional.empty();
      return Optional.of(Files.readAllBytes(path));
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler raw zone " + ref.uri(), e);
    }
  }

  @Override
  public Map<String, String> describe(RawMessageRef ref) {
    try {
      Path raw = Path.of(java.net.URI.create(ref.uri()));
      String name = raw.getFileName().toString();
      Path meta =
          raw.resolveSibling(
              (name.endsWith(".raw") ? name.substring(0, name.length() - 4) : name) + ".meta.json");
      if (!Files.exists(meta)) return Map.of();
      Map<String, String> out = new LinkedHashMap<>();
      com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(meta.toFile());
      node.fields()
          .forEachRemaining(
              e -> {
                if (e.getValue().isObject()) {
                  e.getValue()
                      .fields()
                      .forEachRemaining(
                          m -> out.put(e.getKey() + "." + m.getKey(), m.getValue().asText()));
                } else if (!e.getValue().isNull()) {
                  out.put(e.getKey(), e.getValue().asText());
                }
              });
      return out;
    } catch (IOException | IllegalArgumentException e) {
      return Map.of();
    }
  }

  public Path baseDir() {
    return baseDir;
  }
}
