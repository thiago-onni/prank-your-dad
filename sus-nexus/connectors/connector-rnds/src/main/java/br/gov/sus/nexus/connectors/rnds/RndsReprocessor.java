package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.rnds.RndsDispatcher.Outcome;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmission;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStore;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.raw.RawMessageRef;
import br.gov.sus.nexus.connectors.sdk.raw.RawMessageStore;
import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessCommand;
import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessResult;
import br.gov.sus.nexus.connectors.sdk.reprocess.Reprocessor;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * Reprocessamento RNDS (comando {@code sus.integration.reprocess.requested}, tratado pelo {@code
 * ReprocessCommandHandler} do SDK): reenvia somente submissões {@code failed} — retry esgotado ou
 * erro antes do envio, i.e. nunca aceitas pela RNDS. O envelope original do evento é relido da raw
 * zone (SHA-256 conferido com o ledger) e volta ao pipeline pelo {@link RndsDispatcher}, reabrindo
 * a mesma {@code integration_message}; {@code rnds_submission} e o ledger espelho no core são
 * atualizados como em um envio normal.
 *
 * <p>{@code accepted} → nada a fazer; {@code rejected}/{@code invalid} → não reprocessável
 * (corrigir a origem gera novo evento); {@code pending}/{@code retrying} → em andamento.
 */
@ApplicationScoped
public class RndsReprocessor implements Reprocessor {

  private final RndsDispatcher dispatcher;
  private final RndsSubmissionStore submissions;
  private final IntegrationMessageLedger ledger;
  private final RawMessageStore rawStore;
  private final RndsConnector connector;

  @Inject
  public RndsReprocessor(
      RndsDispatcher dispatcher,
      RndsSubmissionStore submissions,
      IntegrationMessageLedger ledger,
      RawMessageStore rawStore,
      RndsConnector connector) {
    this.dispatcher = dispatcher;
    this.submissions = submissions;
    this.ledger = ledger;
    this.rawStore = rawStore;
    this.connector = connector;
  }

  @Override
  public ReprocessResult reprocess(ReprocessCommand command) {
    Optional<IntegrationMessage> message = ledger.findById(command.messageId());
    // sourceRecordId da mensagem = event_id do evento de origem (chave de rnds_submission)
    String eventId =
        message.map(IntegrationMessage::sourceRecordId).orElse(command.sourceRecordId());
    Optional<RndsSubmission> submission =
        eventId == null ? Optional.empty() : submissions.findByEventId(eventId);
    if (submission.isEmpty()) {
      return ReprocessResult.of(
          ReprocessResult.Status.NOT_FOUND, command.messageId(), "submissão RNDS desconhecida");
    }
    RndsSubmission s = submission.get();
    switch (s.status()) {
      case ACCEPTED -> {
        return ReprocessResult.of(
            ReprocessResult.Status.ALREADY_DONE,
            command.messageId(),
            "já aceita pela RNDS (protocolo registrado)");
      }
      case REJECTED, INVALID -> {
        return ReprocessResult.of(
            ReprocessResult.Status.NOT_REPROCESSABLE,
            command.messageId(),
            "submissão " + s.status().apiValue() + ": corrigir na origem (novo evento)");
      }
      case PENDING, RETRYING -> {
        return ReprocessResult.of(
            ReprocessResult.Status.NOT_REPROCESSABLE,
            command.messageId(),
            "submissão em andamento (" + s.status().apiValue() + ")");
      }
      default -> {
        // FAILED: segue
      }
    }
    String rawRef = message.map(IntegrationMessage::rawRef).orElse(command.rawRef());
    if (rawRef == null) {
      return ReprocessResult.of(
          ReprocessResult.Status.NOT_FOUND, command.messageId(), "evento sem raw_ref");
    }
    String expectedSha = message.map(IntegrationMessage::rawSha256).orElse(null);
    Optional<byte[]> bytes = rawStore.read(new RawMessageRef(rawRef, expectedSha, 0, null));
    if (bytes.isEmpty()) {
      return ReprocessResult.of(
          ReprocessResult.Status.NOT_FOUND, command.messageId(), "envelope ausente na raw zone");
    }
    String sha = Hashes.sha256Hex(bytes.get());
    if (expectedSha != null && !expectedSha.equals(sha)) {
      return ReprocessResult.of(
          ReprocessResult.Status.NOT_REPROCESSABLE,
          command.messageId(),
          "SHA-256 do envelope não confere com o ledger");
    }
    String messageId = ensureLedger(command, message, s, rawRef, sha);
    Outcome outcome =
        dispatcher.reprocess(s.model(), new String(bytes.get(), StandardCharsets.UTF_8), messageId);
    return switch (outcome) {
      case ACCEPTED -> ReprocessResult.of(ReprocessResult.Status.REPROCESSED, messageId, "aceita");
      case DEAD_LETTERED ->
          ReprocessResult.of(
              ReprocessResult.Status.DEAD_LETTERED,
              messageId,
              submissions
                  .findByEventId(s.eventId())
                  .map(RndsSubmission::outcomeSummary)
                  .orElse("falhou"));
      case IGNORED_DISABLED ->
          ReprocessResult.of(
              ReprocessResult.Status.NOT_REPROCESSABLE, messageId, "modelo desabilitado");
      default ->
          ReprocessResult.of(
              ReprocessResult.Status.NOT_REPROCESSABLE, messageId, outcome.name().toLowerCase());
    };
  }

  /** Garante a mensagem no ledger local (ledger em memória após reinício) com o mesmo id. */
  private String ensureLedger(
      ReprocessCommand command,
      Optional<IntegrationMessage> message,
      RndsSubmission s,
      String rawRef,
      String sha) {
    if (message.isPresent()) return message.get().id();
    IntegrationMessage rebuilt =
        new IntegrationMessage(
            command.messageId(),
            connector.descriptor().connectorId(),
            connector.descriptor().sourceSystem(),
            s.eventId(),
            null,
            RndsConnector.entityType(s.model()),
            IntegrationMessageStatus.DEAD_LETTERED,
            rawRef,
            sha,
            command.correlationId() == null ? Ids.correlation() : command.correlationId(),
            Instant.now(),
            null,
            0,
            null);
    ledger.save(rebuilt);
    return rebuilt.id();
  }
}
