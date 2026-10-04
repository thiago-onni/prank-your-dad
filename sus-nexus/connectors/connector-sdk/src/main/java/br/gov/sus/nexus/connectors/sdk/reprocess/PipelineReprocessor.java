package br.gov.sus.nexus.connectors.sdk.reprocess;

import br.gov.sus.nexus.connectors.sdk.api.Connector;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.core.CoreIntegrationMirror;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.raw.RawMessageRef;
import br.gov.sus.nexus.connectors.sdk.raw.RawMessageStore;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.camel.ProducerTemplate;

/**
 * Reprocessamento padrão do SDK: localiza a {@code integration_message} no ledger, relê o bruto na
 * raw zone (conferindo o SHA-256), e reexecuta o pipeline ({@link ConnectorRuntime#INGEST}) com o
 * header {@link PipelineHeaders#REPROCESS_MESSAGE_ID} — a mesma mensagem é reaberta ({@code
 * reprocessing}), sem nova gravação na raw zone, e termina {@code published} ou de volta à DLQ.
 *
 * <ul>
 *   <li>{@code failed}/{@code dead_lettered} → reexecuta;
 *   <li>{@code published}/{@code processed} → nada a fazer (efeitos externos suprimidos, KAF-012),
 *       estado reespelhado no core para encerrar o {@code reprocessing} de lá;
 *   <li>demais estados (em andamento) → não reprocessável;
 *   <li>ausente no ledger (ledger em memória após reinício) → reconstrói a mensagem a partir de
 *       {@code raw_ref} do comando e dos metadados da raw zone, com o mesmo {@code message_id}.
 * </ul>
 */
@DefaultBean
@ApplicationScoped
public class PipelineReprocessor implements Reprocessor {

  private final Connector connector;
  private final IntegrationMessageLedger ledger;
  private final RawMessageStore rawStore;
  private final ProducerTemplate producer;
  private final CoreIntegrationMirror mirror;

  @Inject
  public PipelineReprocessor(
      Connector connector,
      IntegrationMessageLedger ledger,
      RawMessageStore rawStore,
      ProducerTemplate producer,
      CoreIntegrationMirror mirror) {
    this.connector = connector;
    this.ledger = ledger;
    this.rawStore = rawStore;
    this.producer = producer;
    this.mirror = mirror;
  }

  @Override
  public ReprocessResult reprocess(ReprocessCommand command) {
    String owner = connector.descriptor().owner();
    Optional<IntegrationMessage> found = ledger.findById(command.messageId());
    IntegrationMessage message;
    RawMessage raw;
    if (found.isPresent()) {
      message = found.get();
      if (message.isPublished()) {
        mirror.message(message, owner, null);
        return ReprocessResult.of(
            ReprocessResult.Status.ALREADY_DONE, message.id(), "mensagem já publicada");
      }
      if (!message.isFailed()) {
        return ReprocessResult.of(
            ReprocessResult.Status.NOT_REPROCESSABLE,
            message.id(),
            "mensagem em andamento (" + message.status().apiValue() + ")");
      }
      if (message.rawRef() == null) {
        return ReprocessResult.of(
            ReprocessResult.Status.NOT_FOUND, message.id(), "mensagem sem raw_ref");
      }
      RawMessageRef ref = new RawMessageRef(message.rawRef(), message.rawSha256(), 0, null);
      Optional<byte[]> bytes = rawStore.read(ref);
      if (bytes.isEmpty()) {
        return ReprocessResult.of(
            ReprocessResult.Status.NOT_FOUND, message.id(), "bruto ausente na raw zone");
      }
      if (message.rawSha256() != null
          && !message.rawSha256().equals(Hashes.sha256Hex(bytes.get()))) {
        return ReprocessResult.of(
            ReprocessResult.Status.NOT_REPROCESSABLE,
            message.id(),
            "SHA-256 do bruto não confere com o ledger");
      }
      Map<String, String> meta = rawStore.describe(ref);
      raw =
          new RawMessage(
              message.sourceRecordId(),
              message.sourceRecordVersion(),
              message.entityType(),
              meta.getOrDefault("content_type", "application/octet-stream"),
              bytes.get(),
              metadataOf(meta, message.id()),
              Instant.now());
    } else {
      Optional<Rebuilt> rebuilt = rebuild(command);
      if (rebuilt.isEmpty()) {
        return ReprocessResult.of(
            ReprocessResult.Status.NOT_FOUND,
            command.messageId(),
            "mensagem desconhecida no ledger e sem bruto recuperável");
      }
      message = rebuilt.get().message();
      raw = rebuilt.get().raw();
    }

    String messageId = message.id();
    String correlationId = message.correlationId();
    RawMessage body = raw;
    producer.send(
        ConnectorRuntime.INGEST,
        ex -> {
          ex.getIn().setBody(body);
          ex.getIn().setHeader(PipelineHeaders.REPROCESS_MESSAGE_ID, messageId);
          ex.getIn().setHeader(PipelineHeaders.CORRELATION_ID, correlationId);
        });
    IntegrationMessage after = ledger.findById(messageId).orElse(message);
    String reason =
        after.lastError() == null
            ? null
            : Pii.maskText(String.valueOf(after.lastError().message()));
    if (!mirror.pipelineMessages()) {
      mirror.message(after, owner, reason);
    }
    return after.isPublished()
        ? ReprocessResult.of(ReprocessResult.Status.REPROCESSED, messageId, "publicada")
        : ReprocessResult.of(
            ReprocessResult.Status.DEAD_LETTERED, messageId, reason == null ? "falhou" : reason);
  }

  private record Rebuilt(IntegrationMessage message, RawMessage raw) {}

  /** Ledger sem a mensagem: reconstrói a partir da raw zone (mesmo {@code message_id}). */
  private Optional<Rebuilt> rebuild(ReprocessCommand command) {
    if (command.rawRef() == null || command.messageId() == null) return Optional.empty();
    RawMessageRef ref = new RawMessageRef(command.rawRef(), null, 0, null);
    Optional<byte[]> bytes;
    try {
      bytes = rawStore.read(ref);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    if (bytes.isEmpty()) return Optional.empty();
    Map<String, String> meta = rawStore.describe(ref);
    String entity = meta.get("entity_type");
    List<String> supported = connector.descriptor().supportedEntities();
    if ((entity == null || entity.isBlank()) && supported.size() == 1) entity = supported.get(0);
    if (entity == null || entity.isBlank() || !supported.contains(entity)) return Optional.empty();
    String sourceRecordId = blankToNull(meta.get("source_record_id"));
    if (sourceRecordId == null) sourceRecordId = command.sourceRecordId();
    String version = blankToNull(meta.get("source_record_version"));
    IntegrationMessage message =
        new IntegrationMessage(
            command.messageId(),
            connector.descriptor().connectorId(),
            connector.descriptor().sourceSystem(),
            sourceRecordId,
            version,
            entity,
            IntegrationMessageStatus.DEAD_LETTERED,
            command.rawRef(),
            Hashes.sha256Hex(bytes.get()),
            command.correlationId() == null ? Ids.correlation() : command.correlationId(),
            Instant.now(),
            null,
            0,
            null);
    ledger.save(message);
    RawMessage raw =
        new RawMessage(
            sourceRecordId,
            version,
            entity,
            meta.getOrDefault("content_type", "application/octet-stream"),
            bytes.get(),
            metadataOf(meta, message.id()),
            Instant.now());
    return Optional.of(new Rebuilt(message, raw));
  }

  private static Map<String, String> metadataOf(Map<String, String> meta, String messageId) {
    Map<String, String> out = new LinkedHashMap<>();
    meta.forEach(
        (k, v) -> {
          if (k.startsWith("metadata.") && v != null) out.put(k.substring(9), v);
        });
    out.put("reprocess_of", messageId);
    return out;
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v;
  }
}
